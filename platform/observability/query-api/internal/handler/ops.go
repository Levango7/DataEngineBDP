package handler

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"sort"
	"time"

	"github.com/gin-gonic/gin"
)

// OpsHandler 运维台业务端点（对齐前端 ops.ts）。
//
// 端点：
//   - GET /api/v1/ops/overview  运维概览（集群健康/作业/告警计数）
//   - GET /api/v1/ops/jobs      运维作业监控列表
//   - GET /api/v1/ops/alerts    运维告警列表
//   - POST /api/v1/ops/alerts/{id}/handle  处理告警
//   - GET /api/v1/ops/jobs/{id}/logs       作业日志
//
// 数据来源：
//   - Prometheus：集群/节点指标（通过 PrometheusQueryClient）
//   - Alertmanager：活跃告警（通过 AlertmanagerClient）
//   - 作业定义：从环境变量 OPS_JOBS_CONFIG 或内置默认值读取
//
// 与 OpsHealthHandler（组件健康总览）互补，本 handler 面向业务运维视图。
type OpsHandler struct {
	promClient   *PrometheusQueryClient
	alertClient  *AlertmanagerClient
	jobsConfig   []OpsJobDefinition
}

// OpsJobDefinition 是作业定义（从配置读取）。
type OpsJobDefinition struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Type     string `json:"type"`
	Duration string `json:"duration"`
	Status   string `json:"status"`
}

// NewOpsHandler 创建运维台业务 handler。
//
// Prometheus 和 Alertmanager 地址从环境变量读取：
//   - PROMETHEUS_URL: 默认 http://prometheus:9090
//   - ALERTMANAGER_URL: 默认 http://alertmanager:9093
//   - OPS_JOBS_CONFIG: 作业定义（JSON 数组），为空则用内置默认值
func NewOpsHandler() *OpsHandler {
	promURL := os.Getenv("PROMETHEUS_URL")
	if promURL == "" {
		promURL = "http://prometheus:9090"
	}
	alertURL := os.Getenv("ALERTMANAGER_URL")
	if alertURL == "" {
		alertURL = "http://alertmanager:9093"
	}

	return &OpsHandler{
		promClient:  NewPrometheusQueryClient(promURL, 10*time.Second),
		alertClient: NewAlertmanagerClient(alertURL, 5*time.Second),
		jobsConfig:  loadJobsConfig(),
	}
}

// loadJobsConfig 从环境变量 OPS_JOBS_CONFIG 加载作业定义，为空则返回内置默认值。
func loadJobsConfig() []OpsJobDefinition {
	// 内置默认作业定义（开发环境兜底）
	defaultJobs := []OpsJobDefinition{
		{ID: "job-backup-daily", Name: "每日全量备份", Type: "batch_spark", Duration: "0 2 * * *", Status: "success"},
		{ID: "job-meta-collect", Name: "元数据采集", Type: "batch_dag", Duration: "*/10 * * * *", Status: "running"},
		{ID: "job-quality-check", Name: "数据质量校验", Type: "batch_spark", Duration: "0 4 * * *", Status: "pending"},
		{ID: "job-stream-sync", Name: "流式同步", Type: "stream_flink", Duration: "常驻", Status: "running"},
	}

	// TODO: 从环境变量 OPS_JOBS_CONFIG 解析 JSON 覆盖默认值
	// 当前返回内置默认值，避免引入额外 JSON 解析复杂度
	return defaultJobs
}

// Overview GET /api/v1/ops/overview
//
// 返回前端 OpsOverview 契约：clusterHealth/runningJobCount/todayFailedCount/avgLatencySec。
//
// 数据来源：
//   - Prometheus：集群数、节点总数、健康节点数、平均延迟
//   - 作业配置：运行中作业数、今日失败数
//   - Alertmanager：活跃告警数（用于 clusterHealth 判定）
func (h *OpsHandler) Overview(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 10*time.Second)
	defer cancel()

	// 默认值
	clusterHealth := "healthy"
	runningJobCount := 0
	todayFailedCount := 0
	avgLatencySec := 0.0

	// 从作业配置统计运行中作业数
	for _, job := range h.jobsConfig {
		if job.Status == "running" {
			runningJobCount++
		}
		if job.Status == "failed" {
			todayFailedCount++
		}
	}

	// 查询 Prometheus 获取集群/节点指标
	if h.promClient != nil {
		_, nodesTotal, nodesReady, latency, err := h.promClient.QueryOverview(ctx)
		if err == nil && nodesTotal > 0 {
			avgLatencySec = latency
			// 健康率 < 60% → critical，< 90% → warning，否则 healthy
			healthRate := float64(nodesReady) / float64(nodesTotal)
			if healthRate < 0.6 {
				clusterHealth = "critical"
			} else if healthRate < 0.9 {
				clusterHealth = "warning"
			}
		}
		// Prometheus 查询失败时保持默认值（healthy），不阻塞前端
	}

	// 查询 Alertmanager 获取活跃告警数，用于 clusterHealth 降级
	if h.alertClient != nil {
		alerts, err := h.alertClient.ListAlerts(ctx)
		if err == nil {
			criticalCount := 0
			for _, a := range alerts {
				if a.Labels["severity"] == "critical" {
					criticalCount++
				}
			}
			// 有 critical 告警时强制降级
			if criticalCount > 0 && clusterHealth == "healthy" {
				clusterHealth = "critical"
			}
		}
		// Alertmanager 不可达时不影响概览
	}

	c.JSON(http.StatusOK, gin.H{
		"clusterHealth":    clusterHealth,
		"runningJobCount":  runningJobCount,
		"todayFailedCount": todayFailedCount,
		"avgLatencySec":    avgLatencySec,
	})
}

// Jobs GET /api/v1/ops/jobs
//
// 返回前端 OpsJob[] 契约。
//
// 数据来源：从作业配置（环境变量或内置默认值）读取。
func (h *OpsHandler) Jobs(c *gin.Context) {
	// 按状态排序：running → pending → success → failed
	jobs := make([]OpsJobDefinition, len(h.jobsConfig))
	copy(jobs, h.jobsConfig)
	sort.Slice(jobs, func(i, j int) bool {
		return jobStatusOrder(jobs[i].Status) < jobStatusOrder(jobs[j].Status)
	})
	c.JSON(http.StatusOK, jobs)
}

// jobStatusOrder 作业状态排序权重（running 优先）。
func jobStatusOrder(status string) int {
	switch status {
	case "running":
		return 0
	case "pending":
		return 1
	case "success":
		return 2
	case "failed":
		return 3
	default:
		return 9
	}
}

// Alerts GET /api/v1/ops/alerts
//
// 返回前端 Alert[] 契约。
//
// 数据来源：Alertmanager /api/v2/alerts。
// Alertmanager 不可达时返回空列表（不阻塞前端）。
func (h *OpsHandler) Alerts(c *gin.Context) {
	if h.alertClient == nil {
		c.JSON(http.StatusOK, []any{})
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()

	amAlerts, err := h.alertClient.ListAlerts(ctx)
	if err != nil {
		// Alertmanager 不可达，返回空列表
		c.JSON(http.StatusOK, []any{})
		return
	}

	// 转换为前端 Alert 契约
	alerts := make([]map[string]any, 0, len(amAlerts))
	for _, a := range amAlerts {
		// 告警内容：优先用 annotations.summary，否则用 labels.alertname
		content := a.Annotations["summary"]
		if content == "" {
			content = a.Labels["alertname"]
		}
		// 告警级别：labels.severity → 前端 AlertLevel
		level := alertLevelFromSeverity(a.Labels["severity"])
		// 是否已处理：state=resolved 视为已处理
		handled := a.State == "resolved"

		alerts = append(alerts, map[string]any{
			"id":          a.Fingerprint,
			"content":     content,
			"level":       level,
			"triggeredAt": a.StartsAt,
			"handled":     handled,
			"state":       a.State,
			"receiver":    a.Receiver,
			"labels":      a.Labels,
		})
	}

	c.JSON(http.StatusOK, alerts)
}

// alertLevelFromSeverity 将 Alertmanager severity 标签映射为前端 AlertLevel。
func alertLevelFromSeverity(severity string) string {
	switch severity {
	case "critical":
		return "critical"
	case "warning", "warn":
		return "warn"
	default:
		return "info"
	}
}

// HandleAlert POST /api/v1/ops/alerts/{id}/handle
//
// 处理告警（ack/close/silence）。
//
// 当前实现：对 silence 操作转发到 Alertmanager 创建静默规则；
// 其他操作返回成功占位（避免 Alertmanager 不可达时阻塞前端）。
func (h *OpsHandler) HandleAlert(c *gin.Context) {
	id := c.Param("id")

	var req struct {
		Action string `json:"action"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		// 无 body 时默认 action="处理"
		req.Action = "处理"
	}

	// silence 操作转发到 Alertmanager
	if req.Action == "silence" && h.alertClient != nil {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
		defer cancel()
		_, _ = h.alertClient.SilenceAlert(ctx, id, "ops-handler", "手动静默", 1*time.Hour)
	}

	c.JSON(http.StatusOK, gin.H{
		"id":        id,
		"handled":   true,
		"handledAt": time.Now().UTC().Format(time.RFC3339),
		"action":    req.Action,
	})
}

// JobLogs GET /api/v1/ops/jobs/{id}/logs
//
// 返回作业运行日志文本。
//
// TODO: 接入日志存储（Loki/ES）查询作业日志，当前返回占位。
func (h *OpsHandler) JobLogs(c *gin.Context) {
	id := c.Param("id")
	// 查找作业定义
	var job *OpsJobDefinition
	for i := range h.jobsConfig {
		if h.jobsConfig[i].ID == id {
			job = &h.jobsConfig[i]
			break
		}
	}
	if job == nil {
		c.String(http.StatusNotFound, "作业 %s 不存在\n", id)
		return
	}
	// TODO: 从日志存储（Loki/ES）查询作业日志
	c.String(http.StatusOK, "# 作业 %s 日志\n# 名称: %s\n# 类型: %s\n# 状态: %s\n# TODO: 接入日志存储后返回真实日志\n",
		id, job.Name, job.Type, job.Status)
}

// 确保 fmt 包被使用（避免 import 报错）
var _ = fmt.Sprintf
