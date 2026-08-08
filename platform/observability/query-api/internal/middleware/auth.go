// Package middleware 提供统一查询 API 的 Gin 中间件。
//
// 包含：
//   - AuthMiddleware: JWT Bearer token 认证
//   - PlatformRoleMiddleware: 平台方角色校验
//   - TenantIsolationMiddleware: 租户隔离（从 JWT 提取 tenantId 并校验）
//   - LoggingMiddleware: 结构化请求日志
//   - CorsMiddleware: CORS 跨域
package middleware

import (
	"log"
	"log/slog"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"

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

// tenantIDPattern 限制 tenant_id 仅允许字母数字下划线短横线，1-64 字符。
// 防止 PromQL 注入（如 tenant_id="x} or up{" 可绕过隔离）。
var tenantIDPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)

// jwtConfig 在包初始化时读取环境变量，避免每个请求重复读取。
type jwtConfig struct {
	secret       string
	issuer       string
	platformRole string
}

func loadJWTConfig() jwtConfig {
	// 安全止血：JWT_SIGNING_KEY 必须显式配置，缺失则启动 fatal。
	secret := mustGetenv("JWT_SIGNING_KEY")
	issuer := os.Getenv("JWT_ISSUER")
	if issuer == "" {
		issuer = "shuqing-bigdata"
	}
	platformRole := os.Getenv("QUERY_API_PLATFORM_ROLE")
	if platformRole == "" {
		platformRole = "platform-ops"
	}
	return jwtConfig{secret: secret, issuer: issuer, platformRole: platformRole}
}

// AuthMiddleware 是 Gin 的 JWT 认证中间件。
//
// 从 Authorization 头提取 Bearer token，使用 HMAC-SHA 验证签名与过期时间，
// 解析出 tenantId / sub(userId) / role claim，写入 gin.Context。
//
// 对缺失或无效 token 返回 401。
func AuthMiddleware() gin.HandlerFunc {
	cfg := loadJWTConfig()

	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing or non-Bearer Authorization header"})
			return
		}

		tokenString := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))

		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			// 强制使用 HMAC 算法，避免 alg=none 绕过。
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, jwt.ErrSignatureInvalid
			}
			return []byte(cfg.secret), nil
		}, jwt.WithIssuer(cfg.issuer), jwt.WithExpirationRequired())

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
		role, _ := claims["role"].(string)

		c.Set("tenantId", tenantId)
		c.Set("userId", userId)
		c.Set("role", role)

		c.Next()
	}
}

// PlatformRoleMiddleware 校验请求者拥有平台方角色。
//
// 用于 /platform/** 路由组，确保只有 platform-ops 角色可访问全平台指标。
// expectedRole 默认为 "platform-ops"，可通过环境变量 QUERY_API_PLATFORM_ROLE 覆盖。
func PlatformRoleMiddleware(expectedRole string) gin.HandlerFunc {
	if expectedRole == "" {
		expectedRole = "platform-ops"
	}

	return func(c *gin.Context) {
		role, exists := c.Get("role")
		if !exists || role != expectedRole {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{
				"error": "platform role required",
				"hint":  "this endpoint requires role=" + expectedRole,
			})
			return
		}
		c.Next()
	}
}

// TenantIsolationMiddleware 是租户隔离核心中间件。
//
// 从 gin.Context 提取 tenantId（由 AuthMiddleware 注入），做以下校验：
//  1. tenantId 非空；
//  2. tenantId 匹配 tenantIDPattern（防 PromQL 注入）；
//
// 校验通过后将 tenantId 写入 c.Set("effectiveTenantId", tenantId)，
// 供后续 handler 在 PromQL 中注入 tenant_id 过滤。
//
// 若 JWT 中 tenantId 为 "platform" 或空，视为平台方误用客户方端点，返回 403。
func TenantIsolationMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		tenantId, _ := c.Get("tenantId")
		tid, ok := tenantId.(string)
		if !ok || tid == "" {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "tenantId missing in JWT"})
			return
		}

		if tid == "platform" {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "platform identity cannot access tenant endpoint"})
			return
		}

		if !tenantIDPattern.MatchString(tid) {
			c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "invalid tenantId format", "tenantId": tid})
			return
		}

		c.Set("effectiveTenantId", tid)
		c.Next()
	}
}

// LoggingMiddleware 是结构化请求日志中间件。
func LoggingMiddleware(logger *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		latency := time.Since(start)

		logger.Info("request",
			"method", c.Request.Method,
			"path", c.Request.URL.Path,
			"status", c.Writer.Status(),
			"latency_ms", latency.Milliseconds(),
			"tenantId", c.GetString("effectiveTenantId"),
			"userId", c.GetString("userId"),
		)
	}
}

// CorsMiddleware 是 CORS 跨域中间件。
//
// 收敛策略：从环境变量 CORS_ALLOWED_ORIGINS 读取允许的来源，
// 支持单域（如 https://console.shuqing.example.com）或逗号分隔多域。
// 生产环境必须显式配置具体域名，禁止使用通配符 "*"。
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
		c.Header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}
