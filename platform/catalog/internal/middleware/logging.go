package middleware

import (
	"log/slog"
	"time"

	"github.com/gin-gonic/gin"
)

// LoggingMiddleware 是统一 JSON 日志中间件。
// 输出格式：JSON，包含 timestamp, level, traceId, method, path, status, duration。
func LoggingMiddleware(logger *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		method := c.Request.Method
		path := c.Request.URL.Path

		c.Next()

		duration := time.Since(start)
		status := c.Writer.Status()

		// 从 gin.Context 获取 traceId（由 tracing 中间件写入）。
		traceId, _ := c.Get("traceId")
		traceIdStr, _ := traceId.(string)

		logger.Info("request completed",
			slog.String("traceId", traceIdStr),
			slog.String("method", method),
			slog.String("path", path),
			slog.Int("status", status),
			slog.Duration("duration", duration),
		)
	}
}
