package middleware

import (
	"log/slog"
	"time"

	"github.com/gin-gonic/gin"
)

// ============ 日志中间件 ============

// LoggingMiddleware 统一 JSON 日志中间件。
func LoggingMiddleware(logger *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		method := c.Request.Method
		path := c.Request.URL.Path

		c.Next()

		duration := time.Since(start)
		status := c.Writer.Status()

		logger.Info("request completed",
			slog.String("method", method),
			slog.String("path", path),
			slog.Int("status", status),
			slog.Duration("duration", duration),
		)
	}
}
