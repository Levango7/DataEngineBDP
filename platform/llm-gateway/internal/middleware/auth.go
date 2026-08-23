package middleware

import (
	"log"
	"net/http"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// mustGetenv 读取必需的环境变量，缺失则 fail-fast 退出。
// 安全策略：不再提供任何弱默认值，强制部署方显式配置，
// 避免因遗漏环境变量而使用弱密钥。
func mustGetenv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("FATAL: environment variable %s is required", key)
	}
	return v
}

// ============ JWT 认证中间件 ============
//
// 从 Authorization 头提取 Bearer token，校验签名与过期时间，
// 解析出 tenantId 与 sub(userId) claim，写入 gin.Context。
//
// 配置通过环境变量读取（fail-fast，无默认值）：
//   - JWT_SIGNING_KEY:  HMAC-SHA 签名密钥，必需
//   - JWT_ISSUER:       JWT issuer（默认 shuqing-bigdata）
//
// 开发模式：若 JWT_DEV_MODE=true，跳过校验，注入默认 tenantId=dev / userId=dev，
// 便于本地无 JWT 时快速联调。生产环境切勿开启 JWT_DEV_MODE。
func AuthMiddleware() gin.HandlerFunc {
	devMode := strings.EqualFold(os.Getenv("JWT_DEV_MODE"), "true")

	// 开发模式：跳过校验，注入默认身份，不要求 JWT_SIGNING_KEY。
	if devMode {
		return func(c *gin.Context) {
			c.Set("tenantId", "dev")
			c.Set("userId", "dev")
			c.Next()
		}
	}

	// 安全止血：JWT_SIGNING_KEY 必须显式配置，缺失则启动 fatal。
	secret := mustGetenv("JWT_SIGNING_KEY")
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
