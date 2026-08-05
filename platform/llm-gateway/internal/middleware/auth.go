package middleware

import (
	"net/http"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// ============ JWT 认证中间件 ============
//
// 从 Authorization 头提取 Bearer token，校验签名与过期时间，
// 解析出 tenantId 与 sub(userId) claim，写入 gin.Context。
//
// 配置通过环境变量读取：
//   - JWT_SECRET:  HMAC-SHA 签名密钥
//   - JWT_ISSUER:  JWT issuer（默认 shuqing-bigdata）
//
// 开发模式：若 JWT_DEV_MODE=true，跳过校验，注入默认 tenantId=dev / userId=dev，
// 便于本地无 JWT 时快速联调。
func AuthMiddleware() gin.HandlerFunc {
	secret := os.Getenv("JWT_SECRET")
	if secret == "" {
		secret = "dev-secret-key-change-in-production-at-least-256-bits"
	}
	issuer := os.Getenv("JWT_ISSUER")
	if issuer == "" {
		issuer = "shuqing-bigdata"
	}
	devMode := strings.EqualFold(os.Getenv("JWT_DEV_MODE"), "true")

	return func(c *gin.Context) {
		// 开发模式：跳过校验，注入默认身份。
		if devMode {
			c.Set("tenantId", "dev")
			c.Set("userId", "dev")
			c.Next()
			return
		}

		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing or non-Bearer Authorization header"})
			return
		}

		tokenString := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))

		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, jwt.ErrSignatureInvalid
			}
			return []byte(secret), nil
		}, jwt.WithIssuer(issuer), jwt.WithExpirationRequired())

		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired JWT token"})
			return
		}

		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid JWT claims"})
			return
		}

		tenantId, _ := claims["tenantId"].(string)
		userId, _ := claims["sub"].(string)

		c.Set("tenantId", tenantId)
		c.Set("userId", userId)

		c.Next()
	}
}
