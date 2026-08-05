package middleware

import (
	"bytes"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

// newTestLogger 创建一个输出到 buffer 的测试 logger。
func newTestLogger() (*slog.Logger, *bytes.Buffer) {
	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo}))
	return logger, &buf
}

// TestLoggingMiddleware_ExecutesNext 测试日志中间件正常执行后续 handler。
func TestLoggingMiddleware_ExecutesNext(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger, _ := newTestLogger()

	r := gin.New()
	r.Use(LoggingMiddleware(logger))
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/test", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}

// TestLoggingMiddleware_CapturesStatus 测试日志中间件捕获响应状态码。
func TestLoggingMiddleware_CapturesStatus(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger, buf := newTestLogger()

	r := gin.New()
	r.Use(LoggingMiddleware(logger))
	r.GET("/notfound", func(c *gin.Context) {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/notfound", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNotFound, w.Code)
	assert.Contains(t, buf.String(), "request completed")
	assert.Contains(t, buf.String(), `"status":404`)
}

// TestLoggingMiddleware_WithTraceId 测试日志中间件从 context 读取 traceId。
func TestLoggingMiddleware_WithTraceId(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger, buf := newTestLogger()

	r := gin.New()
	r.Use(func(c *gin.Context) {
		c.Set("traceId", "test-trace-123")
		c.Next()
	})
	r.Use(LoggingMiddleware(logger))
	r.GET("/traced", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/traced", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Contains(t, buf.String(), "test-trace-123")
}

// TestLoggingMiddleware_RecordsMethodAndPath 测试日志记录请求方法和路径。
func TestLoggingMiddleware_RecordsMethodAndPath(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger, buf := newTestLogger()

	r := gin.New()
	r.Use(LoggingMiddleware(logger))
	r.POST("/api/test", func(c *gin.Context) {
		c.JSON(http.StatusCreated, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/test", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusCreated, w.Code)
	output := buf.String()
	assert.Contains(t, output, "POST")
	assert.Contains(t, output, "/api/test")
}
