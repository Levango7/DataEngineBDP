package handler

import (
	"net/http"
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
// 说明：
//   - 真实数据需对接作业调度器与 Alertmanager，当前返回占位结构。
//   - 与 OpsHealthHandler（组件健康总览）互补，本 handler 面向业务运维视图。
type OpsHandler struct{}

// NewOpsHandler 创建运维台业务 handler。
func NewOpsHandler() *OpsHandler {
	return &OpsHandler{}
}

// Overview GET /api/v1/ops/overview
//
// 返回前端 OpsOverview 契约：clusterHealth/runningJobCount/todayFailedCount/avgLatencySec。
func (h *OpsHandler) Overview(c *gin.Context) {
	// TODO: 聚合 Prometheus 指标 + 作业调度器状态
	c.JSON(http.StatusOK, gin.H{
		"clusterHealth":    "healthy",
		"runningJobCount":  0,
		"todayFailedCount": 0,
		"avgLatencySec":    0,
	})
}

// Jobs GET /api/v1/ops/jobs
//
// 返回前端 OpsJob[] 契约。
func (h *OpsHandler) Jobs(c *gin.Context) {
	// TODO: 从作业调度器查询运行中作业
	c.JSON(http.StatusOK, []any{})
}

// Alerts GET /api/v1/ops/alerts
//
// 返回前端 Alert[] 契约。
func (h *OpsHandler) Alerts(c *gin.Context) {
	// TODO: 从 Alertmanager 拉取告警
	c.JSON(http.StatusOK, []any{})
}

// HandleAlert POST /api/v1/ops/alerts/{id}/handle
//
// 处理告警（ack/close/silence）。
func (h *OpsHandler) HandleAlert(c *gin.Context) {
	id := c.Param("id")
	// TODO: 转发 Alertmanager 处理
	c.JSON(http.StatusOK, gin.H{
		"id":        id,
		"handled":   true,
		"handledAt": time.Now().UTC().Format(time.RFC3339),
	})
}

// JobLogs GET /api/v1/ops/jobs/{id}/logs
//
// 返回作业运行日志文本。
func (h *OpsHandler) JobLogs(c *gin.Context) {
	id := c.Param("id")
	// TODO: 从日志存储（Loki/ES）查询作业日志
	c.String(http.StatusOK, "# 作业 %s 日志占位\n# TODO: 接入日志存储后返回真实日志\n", id)
}
