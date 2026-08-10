// Package metrics 提供 invocation 计量功能 · 数据引擎大数据平台 T025.
//
// 按 tenant 隔离记录函数 invocation 计量：
//   - Prometheus 指标：serverless_invocation_count / serverless_invocation_duration_seconds
//   - Loki 日志：结构化 JSON 日志，由 Promtail 采集写入 Loki
//
// tenant 隔离：所有指标均带 tenant 标签，支持 PromQL 按租户聚合查询。
package metrics

import (
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

// RuntimeName 运行时名称常量.
const RuntimeName = "go"

// InvocationRecorder 记录函数 invocation 计量.
type InvocationRecorder struct {
	invocationCount    *prometheus.CounterVec
	invocationDuration *prometheus.HistogramVec
	mu                 sync.Mutex
}

// NewInvocationRecorder 创建新的计量记录器并注册到 Prometheus 默认 Registry.
func NewInvocationRecorder() *InvocationRecorder {
	r := &InvocationRecorder{
		invocationCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "serverless_invocation_count",
				Help: "Serverless 函数调用总次数",
			},
			[]string{"tenant", "runtime", "function", "status"},
		),
		invocationDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "serverless_invocation_duration_seconds",
				Help:    "Serverless 函数调用延迟（秒）",
				Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
			},
			[]string{"tenant", "runtime", "function"},
		),
	}
	prometheus.MustRegister(r.invocationCount, r.invocationDuration)
	return r
}

// Record 记录一次 invocation.
//
// tenantId 为租户 ID（用于隔离），functionName 为函数名，
// status 为调用状态（success/error），duration 为调用耗时.
func (r *InvocationRecorder) Record(tenantId, functionName, status string, duration time.Duration) {
	// 1. Prometheus 指标
	r.invocationCount.WithLabelValues(tenantId, RuntimeName, functionName, status).Inc()
	r.invocationDuration.WithLabelValues(tenantId, RuntimeName, functionName).Observe(duration.Seconds())

	// 2. Loki 日志：结构化 JSON，由 Promtail 采集
	//    LogQL 查询示例：{tenant="xxx"} |= "invocation"
	logEntry := map[string]interface{}{
		"type":             "invocation",
		"tenant":           tenantId,
		"runtime":          RuntimeName,
		"function":         functionName,
		"status":           status,
		"duration_seconds": duration.Seconds(),
		"timestamp":        time.Now().Unix(),
	}
	if data, err := json.Marshal(logEntry); err == nil {
		fmt.Fprintln(os.Stdout, string(data))
	}
}

// Warmup 预热：初始化默认指标（降低首次请求开销）.
func (r *InvocationRecorder) Warmup(defaultTenant, defaultFunction string) {
	r.Record(defaultTenant, defaultFunction, "warmup", 0)
	fmt.Fprintf(os.Stderr, "Go invocation metrics warmup done: tenant=%s, function=%s\n",
		defaultTenant, defaultFunction)
}
