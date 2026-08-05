package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// HealthResponse 是健康检查端点的响应结构。
type HealthResponse struct {
	Status    string `json:"status"`
	Component string `json:"component"`
	Version   string `json:"version"`
}

// HealthHandler 处理健康检查请求。
type HealthHandler struct {
	version string
}

// NewHealthHandler 创建一个新的健康检查 handler。
func NewHealthHandler(version string) *HealthHandler {
	return &HealthHandler{version: version}
}

// Health 返回服务健康状态。
// GET /api/v1/health
func (h *HealthHandler) Health(c *gin.Context) {
	c.JSON(http.StatusOK, HealthResponse{
		Status:    "UP",
		Component: "catalog",
		Version:   h.version,
	})
}
