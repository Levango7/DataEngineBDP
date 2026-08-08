package handler

// 健康检查 handler。

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// HealthHandler 健康检查处理器。
type HealthHandler struct {
	version string
}

// NewHealthHandler 创建健康检查处理器。
func NewHealthHandler(version string) *HealthHandler {
	return &HealthHandler{version: version}
}

// Health 健康检查端点。
// GET /api/v1/health
func (h *HealthHandler) Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "UP",
		"version": h.version,
		"service": "failover-api",
	})
}
