// Package handler 提供函数调用处理逻辑 · 数擎大数据平台 T025.
package handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/shuqing/bigdata/function-runtime-go/internal/metrics"
)

// Handler 封装函数调用处理逻辑.
type Handler struct {
	recorder        *metrics.InvocationRecorder
	defaultFunction string
	defaultTenant   string
}

// NewHandler 创建新的函数调用处理器.
//
// defaultFunction 为默认函数名，defaultTenant 为默认租户 ID.
func NewHandler(recorder *metrics.InvocationRecorder, defaultFunction, defaultTenant string) *Handler {
	return &Handler{
		recorder:        recorder,
		defaultFunction: defaultFunction,
		defaultTenant:   defaultTenant,
	}
}

// Health 健康检查端点（Knative readinessProbe / livenessProbe 使用）.
func (h *Handler) Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":   "UP",
		"runtime":  "go",
		"function": h.defaultFunction,
	})
}

// Invoke 函数调用入口.
//
// 请求头：
//   - X-Tenant-Id：租户 ID（用于计量隔离）
//   - X-Function-Name：函数名（覆盖默认）
//
// 请求体：任意 JSON，作为 event 传入用户函数.
func (h *Handler) Invoke(c *gin.Context) {
	start := time.Now()

	tenantId := c.GetHeader("X-Tenant-Id")
	if tenantId == "" {
		tenantId = h.defaultTenant
	}
	functionName := c.GetHeader("X-Function-Name")
	if functionName == "" {
		functionName = h.defaultFunction
	}

	var event map[string]interface{}
	if err := c.ShouldBindJSON(&event); err != nil {
		event = map[string]interface{}{}
	}

	// 调用内置 echo 函数（生产环境可动态加载用户函数）
	result := invokeFunction(functionName, event)
	duration := time.Since(start)

	// invocation 计量
	h.recorder.Record(tenantId, functionName, "success", duration)

	c.JSON(http.StatusOK, result)
}

// invokeFunction 内置 echo 函数（示例）.
func invokeFunction(functionName string, event map[string]interface{}) map[string]interface{} {
	return map[string]interface{}{
		"runtime":  "go",
		"function": functionName,
		"echo":     event,
		"message":  "Hello from Go function runtime",
	}
}
