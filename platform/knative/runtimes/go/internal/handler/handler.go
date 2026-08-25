// Package handler 提供函数调用处理逻辑 · 数据引擎大数据平台 T025.
package handler

import (
	"fmt"
	"net/http"
	"regexp"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/function-runtime-go/internal/metrics"
)

const (
	statusSuccess        = "success"
	statusError          = "error"
	invalidTenantLabel   = "invalid"
	unnamedFunctionLabel = "unnamed"
	maxLabelLength       = 63
)

var labelPattern = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)

func sanitizeLabel(value, fallback string) string {
	if len(value) > maxLabelLength {
		value = value[:maxLabelLength]
	}
	if !labelPattern.MatchString(value) {
		return fallback
	}
	return value
}

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
	tenantLabel := sanitizeLabel(tenantId, invalidTenantLabel)
	functionLabel := sanitizeLabel(functionName, unnamedFunctionLabel)

	var event map[string]interface{}
	if err := c.ShouldBindJSON(&event); err != nil {
		event = map[string]interface{}{}
	}

	result, execErr := safeInvoke(functionName, event)
	duration := time.Since(start)

	status := statusSuccess
	if execErr != nil {
		status = statusError
	}

	// invocation 计量
	h.recorder.Record(tenantLabel, functionLabel, status, duration)

	if execErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": execErr.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

// invokeFunction 内置 echo 函数（示例）.
var invokeFunction = func(functionName string, event map[string]interface{}) (map[string]interface{}, error) {
	return map[string]interface{}{
		"runtime":  "go",
		"function": functionName,
		"echo":     event,
		"message":  "Hello from Go function runtime",
	}, nil
}

// safeInvoke 执行用户函数并捕获 panic，统一转换为 error 返回.
func safeInvoke(functionName string, event map[string]interface{}) (result map[string]interface{}, err error) {
	defer func() {
		if r := recover(); r != nil {
			result = nil
			err = fmt.Errorf("function panicked: %v", r)
		}
	}()
	return invokeFunction(functionName, event)
}
