// Package handler 提供统一查询 API 的 HTTP handler。
//
// 包含：
//   - HealthHandler:  健康检查
//   - QueryHandler:   Prometheus 查询代理（平台方/客户方双视图）
package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// HealthHandler 处理健康检查请求。
type HealthHandler struct {
	version string
}

// NewHealthHandler 创建 HealthHandler。
func NewHealthHandler(version string) *HealthHandler {
	return &HealthHandler{version: version}
}

// Health 返回服务健康状态。
//
// 响应：200 {"status":"UP","version":"...","service":"query-api"}
func (h *HealthHandler) Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "UP",
		"service": "query-api",
		"version": h.version,
	})
}