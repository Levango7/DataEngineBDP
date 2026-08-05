package middleware

import (
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
)

// generateTestToken 生成一个有效的测试 JWT token。
func generateTestToken(secret, issuer, tenantID, userID string) string {
	claims := jwt.MapClaims{
		"iss":      issuer,
		"sub":      userID,
		"tenantId": tenantID,
		"exp":      time.Now().Add(time.Hour).Unix(),
		"iat":      time.Now().Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	s, _ := token.SignedString([]byte(secret))
	return s
}

// TestAuthMiddleware_ValidToken 测试有效 JWT token 通过认证。
func TestAuthMiddleware_ValidToken(t *testing.T) {
	gin.SetMode(gin.TestMode)
	secret := "test-secret-key-at-least-256-bits-long"
	issuer := "shuqing-bigdata"
	os.Setenv("JWT_SECRET", secret)
	os.Setenv("JWT_ISSUER", issuer)
	defer os.Unsetenv("JWT_SECRET")
	defer os.Unsetenv("JWT_ISSUER")

	token := generateTestToken(secret, issuer, "tenant-001", "user-001")

	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/protected", func(c *gin.Context) {
		tenantId, _ := c.Get("tenantId")
		userId, _ := c.Get("userId")
		c.JSON(http.StatusOK, gin.H{
			"tenantId": tenantId,
			"userId":   userId,
		})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}

// TestAuthMiddleware_MissingHeader 测试缺少 Authorization 头。
func TestAuthMiddleware_MissingHeader(t *testing.T) {
	gin.SetMode(gin.TestMode)
	os.Setenv("JWT_SECRET", "test-secret-key-at-least-256-bits-long")
	defer os.Unsetenv("JWT_SECRET")

	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/protected", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// TestAuthMiddleware_InvalidToken 测试无效 JWT token。
func TestAuthMiddleware_InvalidToken(t *testing.T) {
	gin.SetMode(gin.TestMode)
	os.Setenv("JWT_SECRET", "test-secret-key-at-least-256-bits-long")
	defer os.Unsetenv("JWT_SECRET")

	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/protected", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer invalid.token.here")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// TestAuthMiddleware_NonBearerScheme 测试非 Bearer 认证方案。
func TestAuthMiddleware_NonBearerScheme(t *testing.T) {
	gin.SetMode(gin.TestMode)
	os.Setenv("JWT_SECRET", "test-secret-key-at-least-256-bits-long")
	defer os.Unsetenv("JWT_SECRET")

	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/protected", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Basic dXNlcjpwYXNz")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// TestAuthMiddleware_ExpiredToken 测试过期 JWT token。
func TestAuthMiddleware_ExpiredToken(t *testing.T) {
	gin.SetMode(gin.TestMode)
	secret := "test-secret-key-at-least-256-bits-long"
	issuer := "shuqing-bigdata"
	os.Setenv("JWT_SECRET", secret)
	os.Setenv("JWT_ISSUER", issuer)
	defer os.Unsetenv("JWT_SECRET")
	defer os.Unsetenv("JWT_ISSUER")

	// 生成一个已过期的 token
	claims := jwt.MapClaims{
		"iss":      issuer,
		"sub":      "user-001",
		"tenantId": "tenant-001",
		"exp":      time.Now().Add(-time.Hour).Unix(), // 1小时前过期
		"iat":      time.Now().Add(-2 * time.Hour).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	s, _ := token.SignedString([]byte(secret))

	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/protected", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+s)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// TestAuthMiddleware_ExtractsClaims 测试认证中间件正确提取 tenantId 和 userId。
func TestAuthMiddleware_ExtractsClaims(t *testing.T) {
	gin.SetMode(gin.TestMode)
	secret := "test-secret-key-at-least-256-bits-long"
	issuer := "shuqing-bigdata"
	os.Setenv("JWT_SECRET", secret)
	os.Setenv("JWT_ISSUER", issuer)
	defer os.Unsetenv("JWT_SECRET")
	defer os.Unsetenv("JWT_ISSUER")

	token := generateTestToken(secret, issuer, "tenant-42", "user-42")

	var gotTenant, gotUser string
	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/protected", func(c *gin.Context) {
		t, _ := c.Get("tenantId")
		u, _ := c.Get("userId")
		gotTenant, _ = t.(string)
		gotUser, _ = u.(string)
		c.JSON(http.StatusOK, gin.H{})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, "tenant-42", gotTenant)
	assert.Equal(t, "user-42", gotUser)
}
