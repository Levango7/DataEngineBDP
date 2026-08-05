package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// ============ CORS 中间件 ============

// CorsMiddleware 是 Gin 的 CORS 中间件。
//
// 允许的前端源通过环境变量 CORS_ORIGINS 配置，逗号分隔；
// 默认放行 Vite 开发服务器 http://localhost:5173。
func CorsMiddleware() gin.HandlerFunc {
	allowedOrigins := "http://localhost:5173,http://localhost:8080"
	origins := make(map[string]struct{})
	for _, o := range strings.Split(allowedOrigins, ",") {
		o = strings.TrimSpace(o)
		if o != "" {
			origins[o] = struct{}{}
		}
	}

	return func(c *gin.Context) {
		origin := c.GetHeader("Origin")
		if _, ok := origins[origin]; ok {
			c.Header("Access-Control-Allow-Origin", origin)
			c.Header("Access-Control-Allow-Credentials", "true")
			c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS")
			c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With")
			c.Header("Access-Control-Max-Age", "3600")
		}

		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}
