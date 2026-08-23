package middleware

// HTTP 中间件：认证、日志、CORS。

import (
	"log"
	"net/http"
	"os"
	"strings"
	"sync"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// JWT 配置（与各组件保持一致）。
//
// 安全策略：JWT_SECRET 必须显式配置，缺失则 fail-fast 退出，
// 避免因遗漏环境变量而使用弱密钥（与 observability/query-api 保持一致）。
// 使用 sync.Once 延迟初始化，允许包被导入（如测试）而不立即退出，
// 首次调用 AuthMiddleware 时才校验环境变量。
var (
	jwtSecret []byte
	jwtIssuer string
	jwtOnce   sync.Once
)

func ensureJWTConfig() {
	jwtOnce.Do(func() {
		secret := os.Getenv("JWT_SECRET")
		if len(secret) == 0 {
			log.Fatalf("FATAL: environment variable JWT_SECRET is required (at least 32 bytes)")
		}
		if len(secret) < 32 {
			log.Fatalf("FATAL: JWT_SECRET must be at least 32 bytes, got %d", len(secret))
		}
		jwtSecret = []byte(secret)
		jwtIssuer = os.Getenv("JWT_ISSUER")
		if jwtIssuer == "" {
			jwtIssuer = "shuqing-bigdata"
		}
	})
}

// AuthMiddleware JWT 认证中间件。
//
// 校验 Authorization: Bearer <token> 头，解析 JWT 并将 tenantId 注入 context。
func AuthMiddleware() gin.HandlerFunc {
	ensureJWTConfig()
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
		// gin.Logger() 已提供基础日志，这里简化处理。
		c.Next()
	}
}

// CorsMiddleware CORS 中间件。
//
// 收敛策略：从环境变量 CORS_ALLOWED_ORIGINS 读取允许的来源，
// 支持单域或逗号分隔多域。生产环境必须显式配置具体域名，禁止使用通配符 "*"。
// 当请求 Origin 命中白名单时回写 Access-Control-Allow-Origin；
// 未配置或未命中时不回写该头，浏览器将拒绝跨域请求（fail-secure）。
//
// 环境变量：
//   - CORS_ALLOWED_ORIGINS: 允许的来源列表，逗号分隔，默认空（拒绝所有跨域）。
//     示例：https://console.shuqing.example.com,https://ops.shuqing.example.com
func CorsMiddleware() gin.HandlerFunc {
	raw := os.Getenv("CORS_ALLOWED_ORIGINS")
	allowed := make(map[string]struct{}, 4)
	if raw != "" {
		for _, o := range strings.Split(raw, ",") {
			o = strings.TrimSpace(o)
			if o != "" {
				allowed[o] = struct{}{}
			}
		}
	}

	return func(c *gin.Context) {
		origin := c.GetHeader("Origin")
		if origin != "" {
			if _, ok := allowed[origin]; ok {
				c.Header("Access-Control-Allow-Origin", origin)
				c.Header("Vary", "Origin")
			}
			// 未命中白名单时不回写 Access-Control-Allow-Origin，
			// 浏览器将拒绝跨域请求，实现 fail-secure。
		}
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type")

		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}
