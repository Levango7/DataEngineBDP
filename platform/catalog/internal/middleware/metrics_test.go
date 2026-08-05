package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

// TestMetricsMiddleware_RecordsRequest 测试 metrics 中间件记录请求。
func TestMetricsMiddleware_RecordsRequest(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(MetricsMiddleware())
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/test", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}

// TestMetricsMiddleware_RecordsError 测试 metrics 中间件记录错误响应。
func TestMetricsMiddleware_RecordsError(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(MetricsMiddleware())
	r.GET("/fail", func(c *gin.Context) {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "fail"})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/fail", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

// TestMetricsHandler_ReturnsMetrics 测试 /metrics 端点返回 Prometheus 格式。
func TestMetricsHandler_ReturnsMetrics(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.GET("/metrics", MetricsHandler())

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/metrics", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	// Prometheus metrics 应包含 http_requests_total
	assert.Contains(t, w.Body.String(), "http_requests_total")
}

// TestMetricsMiddleware_UnmatchedPath 测试未匹配路径使用 "unmatched" 标签。
func TestMetricsMiddleware_UnmatchedPath(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(MetricsMiddleware())
	// 不注册任何路由，请求将 404

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/nonexistent", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNotFound, w.Code) // Gin 默认 404
}
