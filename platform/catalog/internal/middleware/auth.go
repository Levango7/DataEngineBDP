package middleware

import (
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// AuthMiddleware 是 Gin 的 JWT 认证中间件。
//
// 从 Authorization 头提取 Bearer token，使用 HMAC-SHA 验证签名与过期时间，
// 解析出 tenantId 与 sub(userId) claim，写入 gin.Context 与 c.Set。
//
// 放行路径：/api/v1/health（由调用方在注册时跳过本中间件即可）。
// 对其他路径要求有效 JWT，否则返回 401。
//
// 配置通过环境变量读取：
//   - JWT_SECRET:  HMAC-SHA 签名密钥，至少 32 字节（256 bit）
//   - JWT_ISSUER:  JWT issuer，校验 iss claim 必须匹配（默认 shuqing-bigdata）
func AuthMiddleware() gin.HandlerFunc {
	secret := os.Getenv("JWT_SECRET")
	if secret == "" {
		// 开发环境默认值，生产环境必须通过环境变量覆盖。
		secret = "dev-secret-key-change-in-production-at-least-256-bits"
	}
	issuer := os.Getenv("JWT_ISSUER")
	if issuer == "" {
		issuer = "shuqing-bigdata"
	}

	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing or non-Bearer Authorization header"})
			return
		}

		tokenString := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))

		// 解析并校验签名与过期时间。
		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			// 强制使用 HMAC 算法，避免 alg=none 绕过。
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

		// 提取 tenantId 与 userId(sub)。
		tenantId, _ := claims["tenantId"].(string)
		userId, _ := claims["sub"].(string)

		// 写入 gin.Context，供后续 handler 通过 c.Get("tenantId") 获取。
		c.Set("tenantId", tenantId)
		c.Set("userId", userId)

		c.Next()
	}
}

// _ 确保 time 包被使用（保留以备后续在中间件中加请求耗时日志）。
var _ = time.Now
