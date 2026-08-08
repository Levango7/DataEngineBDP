// Package middleware 提供HTTP中间件，包含JWT鉴权、日志、CORS等。
//
// auth.go 实现基于HS256的JWT签发与校验中间件。
package middleware

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"

	"github.com/shuqing/infra-provider-baremetal/src/internal/model"
)

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
func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
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
