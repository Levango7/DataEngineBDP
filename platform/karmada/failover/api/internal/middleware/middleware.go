package middleware

// HTTP 中间件：认证、日志、CORS。
//
// 与 karmada-api 中间件保持一致，便于复用 JWT 配置。

import (
	"net/http"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// JWT 配置（与各组件保持一致）。
var (
	jwtSecret = []byte(os.Getenv("JWT_SECRET"))
	jwtIssuer = os.Getenv("JWT_ISSUER")
)

func init() {
	// 默认值（与 docker 测试 conftest 保持一致）。
	if len(jwtSecret) == 0 {
		jwtSecret = []byte("dev-secret-key-change-in-production-at-least-256-bits")
	}
	if jwtIssuer == "" {
		jwtIssuer = "shuqing-bigdata"
	}
}

// AuthMiddleware JWT 认证中间件。
//
// 校验 Authorization: Bearer <token> 头，解析 JWT 并将 tenantId 注入 context。
func AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing authorization header"})
			return
		}

		// 解析 Bearer token。
		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid authorization header format"})
			return
		}

		tokenString := parts[1]

		// 解析并校验 JWT。
		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, jwt.ErrSignatureInvalid
			}
			return jwtSecret, nil
		})
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}

		// 提取 claims。
		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid claims"})
			return
		}

		// 校验 issuer。
		iss, _ := claims["iss"].(string)
		if iss != jwtIssuer {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid issuer"})
			return
		}

		// 注入 tenantId 到 context。
		tenantID, _ := claims["tenantId"].(string)
		if tenantID == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing tenantId in token"})
			return
		}
		c.Set("tenantId", tenantID)

		c.Next()
	}
}

// LoggingMiddleware 请求日志中间件。
func LoggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()
	}
}

// CorsMiddleware CORS 中间件。
func CorsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type")

		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}