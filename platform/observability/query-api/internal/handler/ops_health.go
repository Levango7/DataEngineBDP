// Package handler 提供统一查询 API 的 HTTP handler。
//
// 包含：
//   - HealthHandler:  健康检查
//   - QueryHandler:   Prometheus 查询代理（平台方/客户方双视图）
//   - OpsHealthHandler: 统一运维台组件健康总览（红黄绿聚合）
package handler

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// ComponentHealth 单个组件健康状态。
type ComponentHealth struct {
	Name      string `json:"name"`
	Group     string `json:"group"`
	Status    string `json:"status"` // UP / WARN / DOWN / UNKNOWN
	URL       string `json:"url"`
	LatencyMs int64  `json:"latencyMs"`
	Detail    string `json:"detail,omitempty"`
}

// ComponentSpec 组件注册表条目。
type ComponentSpec struct {
	Name  string
	Group string
	URL   string
	// ProbeKind: http_json(UP/DEGRADED) | http_ok(任意2xx) | tcp
	ProbeKind string
}

// 默认组件注册表（与 platform/ 自研组件对齐，可通过 OPS_COMPONENTS 环境变量覆盖）。
var defaultComponents = []ComponentSpec{
	{Name: "encaps-layer", Group: "封装层", URL: "http://encaps-layer:8080/actuator/health", ProbeKind: "http_json"},
	{Name: "sql-gateway", Group: "数据引擎", URL: "http://sql-gateway:8081/actuator/health", ProbeKind: "http_json"},
	{Name: "catalog", Group: "数据治理", URL: "http://catalog:8090/healthz", ProbeKind: "http_ok"},
	{Name: "rule-engine", Group: "数据治理", URL: "http://rule-engine:8085/actuator/health", ProbeKind: "http_json"},
	{Name: "metadata-collector", Group: "数据治理", URL: "http://metadata-collector:8087/actuator/health", ProbeKind: "http_json"},
	{Name: "lineage-analyzer", Group: "数据治理", URL: "http://lineage-analyzer:8086/actuator/health", ProbeKind: "http_json"},
	{Name: "tag-engine", Group: "数据治理", URL: "http://tag-engine:8088/actuator/health", ProbeKind: "http_json"},
	{Name: "vector-engine", Group: "智能数据", URL: "http://vector-engine:8091/healthz", ProbeKind: "http_ok"},
	{Name: "llm-gateway", Group: "智能数据", URL: "http://llm-gateway:8092/healthz", ProbeKind: "http_ok"},
	{Name: "knowledge-engine", Group: "智能数据", URL: "http://knowledge-engine:8093/healthz", ProbeKind: "http_ok"},
	{Name: "ai-assistant", Group: "智能数据", URL: "http://ai-assistant:18110/healthz", ProbeKind: "http_ok"},
	{Name: "finops-cost-model", Group: "产品运营", URL: "http://finops-cost-model:18084/actuator/health", ProbeKind: "http_json"},
	{Name: "finops-dashboard", Group: "产品运营", URL: "http://finops-dashboard:18085/actuator/health", ProbeKind: "http_json"},
	{Name: "stream-batch-scheduler", Group: "数据开发", URL: "http://stream-batch-scheduler:18086/actuator/health", ProbeKind: "http_json"},
	{Name: "flink-cdc", Group: "数据集成", URL: "http://flink-cdc:18087/actuator/health", ProbeKind: "http_json"},
	{Name: "infra-orchestrator", Group: "基础设施", URL: "http://infra-orchestrator:18090/actuator/health", ProbeKind: "http_json"},
	{Name: "infra-provider-xinchang", Group: "基础设施", URL: "http://infra-provider-xinchang:18091/actuator/health", ProbeKind: "http_json"},
	{Name: "infra-provider-cloud", Group: "基础设施", URL: "http://infra-provider-cloud:18092/actuator/health", ProbeKind: "http_json"},
	{Name: "infra-provider-private", Group: "基础设施", URL: "http://infra-provider-private:18093/actuator/health", ProbeKind: "http_json"},
	{Name: "infra-provider-baremetal", Group: "基础设施", URL: "http://infra-provider-baremetal:18094/healthz", ProbeKind: "http_ok"},
	{Name: "dqctl", Group: "基础设施", URL: "http://dqctl:18095/healthz", ProbeKind: "http_ok"},
}

// OpsHealthHandler 统一运维台组件健康总览。
type OpsHealthHandler struct {
	components []ComponentSpec
	timeout    time.Duration
}

// NewOpsHealthHandler 创建运维台健康 handler，支持 OPS_COMPONENTS 覆盖注册表。
func NewOpsHealthHandler() *OpsHealthHandler {
	comps := defaultComponents
	if env := os.Getenv("OPS_COMPONENTS"); env != "" {
		comps = parseComponents(env)
	}
	return &OpsHealthHandler{components: comps, timeout: 3 * time.Second}
}

// parseComponents 解析 OPS_COMPONENTS 环境变量（name=url,probeKind 逗号分隔）。
func parseComponents(env string) []ComponentSpec {
	var out []ComponentSpec
	for _, part := range strings.Split(env, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		kv := strings.SplitN(part, "=", 2)
		if len(kv) != 2 {
			continue
		}
		urlProbe := strings.SplitN(kv[1], ";", 2)
		url := strings.TrimSpace(urlProbe[0])
		kind := "http_json"
		if len(urlProbe) == 2 {
			kind = strings.TrimSpace(urlProbe[1])
		}
		out = append(out, ComponentSpec{Name: strings.TrimSpace(kv[0]), Group: "custom", URL: url, ProbeKind: kind})
	}
	return out
}

// Overview 返回 31 组件红黄绿健康总览（并发探活）。
//
// 响应：{"summary":{...}, "components":[...]}
func (h *OpsHealthHandler) Overview(c *gin.Context) {
	results := make([]ComponentHealth, len(h.components))
	var wg sync.WaitGroup

	for i, comp := range h.components {
		wg.Add(1)
		go func(idx int, spec ComponentSpec) {
			defer wg.Done()
			results[idx] = h.probe(spec)
		}(i, comp)
	}
	wg.Wait()

	// 汇总
	summary := map[string]int{"total": len(results), "up": 0, "warn": 0, "down": 0, "unknown": 0}
	for _, r := range results {
		switch r.Status {
		case "UP":
			summary["up"]++
		case "WARN":
			summary["warn"]++
		case "DOWN":
			summary["down"]++
		default:
			summary["unknown"]++
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"summary":    summary,
		"components": results,
	})
}

// probe 探活单个组件。
func (h *OpsHealthHandler) probe(spec ComponentSpec) ComponentHealth {
	client := &http.Client{Timeout: h.timeout}
	start := time.Now()
	resp, err := client.Get(spec.URL)
	latency := time.Since(start).Milliseconds()

	if err != nil {
		return ComponentHealth{
			Name: spec.Name, Group: spec.Group, Status: "DOWN",
			URL: spec.URL, LatencyMs: latency, Detail: err.Error(),
		}
	}
	defer resp.Body.Close()

	status := "UP"
	detail := ""
	switch spec.ProbeKind {
	case "http_ok":
		if resp.StatusCode >= 500 {
			status = "DOWN"
			detail = fmt.Sprintf("HTTP %d", resp.StatusCode)
		} else if resp.StatusCode >= 400 {
			status = "WARN"
			detail = fmt.Sprintf("HTTP %d", resp.StatusCode)
		}
	case "http_json", "http_ok_json":
		// 读取 JSON 并检查 status 字段（actuator /health 返回 {"status":"UP"}）
		var body struct {
			Status string `json:"status"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			status = "WARN"
			detail = "响应解析失败: " + err.Error()
		} else {
			switch strings.ToUpper(body.Status) {
			case "UP", "OK":
				status = "UP"
			case "DOWN":
				status = "DOWN"
				detail = "actuator status=DOWN"
			case "DEGRADED", "WARN":
				status = "WARN"
				detail = "actuator status=" + body.Status
			default:
				status = "UNKNOWN"
				detail = "actuator status=" + body.Status
			}
		}
	}

	return ComponentHealth{
		Name: spec.Name, Group: spec.Group, Status: status,
		URL: spec.URL, LatencyMs: latency, Detail: detail,
	}
}
