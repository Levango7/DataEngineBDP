// Package middleware 提供向量检索引擎的 HTTP 中间件。
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
func mustGetenv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("FATAL: environment variable %s is required", key)
	}
	return v
}

// AuthMiddleware JWT 认证中间件。
//
// 环境变量：
//   - VECTOR_AUTH_REQUIRED      默认要求鉴权（secure-by-default）；
//                               仅显式设为 "false" 才关闭并打印告警
//   - JWT_SIGNING_KEY            HMAC 签名密钥（启用认证时必需，>=32 字节）
//   - JWT_ISSUER                 issuer 校验（默认 shuqing-bigdata）
//   - JWT_DEV_MODE=true          开发模式：跳过校验，注入 dev 身份
//
// 默认拒绝匿名访问：部署遗漏环境变量时向量数据不再裸奔；
// 网关前置鉴权场景必须显式声明 VECTOR_AUTH_REQUIRED=false。
func AuthMiddleware() gin.HandlerFunc {
	// secure-by-default：仅显式 false 关闭；未设置/true/任意其他值均启用
	explicitOff := strings.EqualFold(os.Getenv("VECTOR_AUTH_REQUIRED"), "false")
	devMode := strings.EqualFold(os.Getenv("JWT_DEV_MODE"), "true")

	if explicitOff {
		log.Println("[WARN][vector-engine] VECTOR_AUTH_REQUIRED=false：认证已显式关闭，" +
			"请确保仅用于网关前置鉴权等受控部署场景")
		return func(c *gin.Context) { c.Next() }
	}

	// 开发模式：跳过校验，注入默认身份，不要求 JWT_SIGNING_KEY
	if devMode {
		log.Println("[WARN][vector-engine] JWT_DEV_MODE=true：跳过 JWT 校验并注入 dev 身份，仅限本地开发")
		return func(c *gin.Context) {
			c.Set("tenantId", "dev")
			c.Set("userId", "dev")
			c.Next()
		}
	}

	// 生产模式：必须配置签名密钥
	secret := mustGetenv("JWT_SIGNING_KEY")
	if len(secret) < 32 {
		log.Fatalf("JWT_SIGNING_KEY 长度不足: %d 字节, 要求至少 32 字节 (256 bits)", len(secret))
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
		if tenantId == "" || userId == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing tenantId or userId in JWT claims"})
			return
		}

		c.Set("tenantId", tenantId)
		c.Set("userId", userId)
		c.Next()
	}
}
