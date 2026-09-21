// Package middleware 提供HTTP中间件，包含JWT鉴权、日志、CORS等。
//
// auth.go 实现基于HS256的JWT签发与校验中间件。
package middleware

import (
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
)

const (
	// minJWTSigningKeyLen JWT HMAC(HS256) 签名密钥最小长度（字节）。
	// 密钥短于 32 字节时抗离线暴力破解能力显著下降。
	// 与 config/config.yaml 中「JWT 签名密钥（≥32 字节）」的约定保持一致。
	minJWTSigningKeyLen = 32

	// jwtSigningKeyEnv JWT 签名密钥环境变量名。
	jwtSigningKeyEnv = "JWT_SIGNING_KEY"

	// jwtDevModeEnv 开发模式开关环境变量名。
	// 仅允许在本地开发/CI 场景置为 true，用于放宽密钥强度校验并打印告警；
	// 生产环境必须保持未设置或 false。
	jwtDevModeEnv = "JWT_DEV_MODE"
)

// checkJWTSigningKey 校验 JWT 签名密钥强度，dev 模式下放宽但必须告警。
//
// 校验规则：
//   - 空密钥：非 dev 模式返回错误（消息含 "required"）；dev 模式放行并告警
//   - 长度 < minJWTSigningKeyLen：非 dev 模式返回错误（消息含 "too short"）；dev 模式放行并告警
//   - 长度 >= minJWTSigningKeyLen：通过，且不产生任何告警
//
// warn 为 dev 模式放行时的告警回调，其首个参数为完整消息（非格式串）；
// 传 nil 表示不告警。
func checkJWTSigningKey(secret string, devMode bool, warn func(string, ...any)) error {
	// dev 模式放行前统一告警，确保弱密钥不会静默生效。
	warnIfDev := func(reason string) {
		if warn == nil {
			return
		}
		warn("SECURITY WARNING: weak JWT signing key (" + reason +
			"); accepted only because JWT_DEV_MODE=true (never enable this in production)")
	}

	if secret == "" {
		if devMode {
			warnIfDev("empty")
			return nil
		}
		return errors.New("JWT signing key is required: set " + jwtSigningKeyEnv +
			" to at least " + fmt.Sprintf("%d", minJWTSigningKeyLen) + " bytes")
	}
	if len(secret) < minJWTSigningKeyLen {
		if devMode {
			warnIfDev(fmt.Sprintf("%d bytes", len(secret)))
			return nil
		}
		return fmt.Errorf("JWT signing key too short: got %d bytes, must be at least %d bytes",
			len(secret), minJWTSigningKeyLen)
	}
	return nil
}

// ValidateJWTSigningKey 校验 JWT 签名密钥强度，是 checkJWTSigningKey 的环境感知入口。
//
// dev 模式由环境变量 JWT_DEV_MODE=true 开启：放宽强度校验，但向 stderr 打印告警。
// 应在应用启动早期（构造 JWTAuthenticator 之前）调用，
// 确保生产环境不会带着弱密钥/空密钥启动服务。
func ValidateJWTSigningKey(secret string) error {
	devMode := strings.EqualFold(strings.TrimSpace(os.Getenv(jwtDevModeEnv)), "true")
	return checkJWTSigningKey(secret, devMode, func(msg string, _ ...any) {
		fmt.Fprintln(os.Stderr, msg)
	})
}

// JWTAuthenticator JWT签发与校验器
type JWTAuthenticator struct {
	secret []byte
	ttl    time.Duration
	issuer string
}

// NewJWTAuthenticator 创建JWT鉴权器
func NewJWTAuthenticator(secret string, ttl time.Duration, issuer string) *JWTAuthenticator {
	return &JWTAuthenticator{
		secret: []byte(secret),
		ttl:    ttl,
		issuer: issuer,
	}
}

// CustomClaims 自定义JWT Claims
type CustomClaims struct {
	Username string `json:"username"`
	Role     string `json:"role"`
	jwt.RegisteredClaims
}

// GenerateToken 为指定用户签发JWT
func (a *JWTAuthenticator) GenerateToken(username, role string) (string, error) {
	now := time.Now()
	claims := CustomClaims{
		Username: username,
		Role:     role,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    a.issuer,
			Subject:   username,
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(a.ttl)),
			NotBefore: jwt.NewNumericDate(now),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(a.secret)
}

// ParseToken 解析并校验JWT
func (a *JWTAuthenticator) ParseToken(tokenString string) (*CustomClaims, error) {
	claims := &CustomClaims{}
	token, err := jwt.ParseWithClaims(tokenString, claims, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("非预期的签名方法")
		}
		return a.secret, nil
	})
	if err != nil {
		return nil, err
	}
	if !token.Valid {
		return nil, errors.New("token无效")
	}
	return claims, nil
}

// AuthMiddleware JWT鉴权中间件
// 校验 Authorization: Bearer <token> 头，将 claims 注入 gin.Context
func (a *JWTAuthenticator) AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, model.APIResponse{
				Code:    http.StatusUnauthorized,
				Message: "缺少Authorization头",
			})
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, model.APIResponse{
				Code:    http.StatusUnauthorized,
				Message: "Authorization头格式错误，应为: Bearer <token>",
			})
			return
		}

		claims, err := a.ParseToken(parts[1])
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, model.APIResponse{
				Code:    http.StatusUnauthorized,
				Message: "Token校验失败",
				Data:    err.Error(),
			})
			return
		}

		c.Set("username", claims.Username)
		c.Set("role", claims.Role)
		c.Next()
	}
}

// OptionalAuthMiddleware 可选JWT鉴权中间件
// 若提供token则校验并注入claims；若未提供则放行(用于健康检查等)
func (a *JWTAuthenticator) OptionalAuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.Next()
			return
		}
		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
			c.Next()
			return
		}
		claims, err := a.ParseToken(parts[1])
		if err == nil {
			c.Set("username", claims.Username)
			c.Set("role", claims.Role)
		}
		c.Next()
	}
}

// RequireRole 角色校验中间件(需配合AuthMiddleware使用)
func RequireRole(roles ...string) gin.HandlerFunc {
	allowed := make(map[string]struct{}, len(roles))
	for _, r := range roles {
		allowed[r] = struct{}{}
	}
	return func(c *gin.Context) {
		role, exists := c.Get("role")
		if !exists {
			c.AbortWithStatusJSON(http.StatusForbidden, model.APIResponse{
				Code:    http.StatusForbidden,
				Message: "无角色信息",
			})
			return
		}
		roleStr, _ := role.(string) //nolint:errcheck // gin上下文值类型断言，空值映射为空串由allowed判断
		if _, ok := allowed[roleStr]; !ok {
			c.AbortWithStatusJSON(http.StatusForbidden, model.APIResponse{
				Code:    http.StatusForbidden,
				Message: "无权限执行此操作",
			})
			return
		}
		c.Next()
	}
}

// CORSMiddleware 跨域中间件
//
// 收敛策略：从环境变量 CORS_ALLOWED_ORIGINS 读取允许的来源，
// 支持单域或逗号分隔多域。生产环境必须显式配置具体域名，禁止使用通配符 "*"。
// 当请求 Origin 命中白名单时回写 Access-Control-Allow-Origin；
// 未配置或未命中时不回写该头，浏览器将拒绝跨域请求（fail-secure）。
//
// 环境变量：
//   - CORS_ALLOWED_ORIGINS: 允许的来源列表，逗号分隔，默认空（拒绝所有跨域）。
func CORSMiddleware() gin.HandlerFunc {
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
				c.Writer.Header().Set("Access-Control-Allow-Origin", origin)
				c.Writer.Header().Set("Vary", "Origin")
			}
		}
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")
		c.Writer.Header().Set("Access-Control-Max-Age", "86400")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

// RecoveryMiddleware panic恢复中间件(补充gin默认Recovery的自定义响应)
func RecoveryMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if r := recover(); r != nil {
				c.AbortWithStatusJSON(http.StatusInternalServerError, model.APIResponse{
					Code:    http.StatusInternalServerError,
					Message: "服务器内部错误",
					Data:    r,
				})
			}
		}()
		c.Next()
	}
}
