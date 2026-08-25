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

// testSecret 是长度满足要求的测试签名密钥（>= 32 字节）。
const testSecret = "test-secret-key-at-least-256-bits-long"

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

// setupAuthEnv 设置启用认证所需的环境变量，并在测试结束后清理。
func setupAuthEnv(t *testing.T, issuer string) {
	t.Helper()
	_ = os.Setenv("VECTOR_AUTH_REQUIRED", "true")
	_ = os.Setenv("JWT_SIGNING_KEY", testSecret)
	if issuer != "" {
		_ = os.Setenv("JWT_ISSUER", issuer)
	}
	t.Cleanup(func() {
		_ = os.Unsetenv("VECTOR_AUTH_REQUIRED")
		_ = os.Unsetenv("JWT_SIGNING_KEY")
		_ = os.Unsetenv("JWT_ISSUER")
		_ = os.Unsetenv("JWT_DEV_MODE")
	})
}

// newProtectedRouter 构造一个挂载 AuthMiddleware 的测试路由。
func newProtectedRouter() *gin.Engine {
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
	return r
}

// TestAuthMiddleware_NoAuthHeader 测试缺少 Authorization 头返回 401。
func TestAuthMiddleware_NoAuthHeader(t *testing.T) {
	gin.SetMode(gin.TestMode)
	setupAuthEnv(t, "shuqing-bigdata")

	r := newProtectedRouter()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// TestAuthMiddleware_InvalidToken 测试无效 JWT 返回 401。
func TestAuthMiddleware_InvalidToken(t *testing.T) {
	gin.SetMode(gin.TestMode)
	setupAuthEnv(t, "shuqing-bigdata")

	r := newProtectedRouter()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer invalid.token.here")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// TestAuthMiddleware_ValidToken 测试有效 JWT 通过认证。
func TestAuthMiddleware_ValidToken(t *testing.T) {
	gin.SetMode(gin.TestMode)
	issuer := "shuqing-bigdata"
	setupAuthEnv(t, issuer)

	token := generateTestToken(testSecret, issuer, "tenant-001", "user-001")

	r := newProtectedRouter()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}

// TestAuthMiddleware_WrongIssuer 测试错误 issuer 返回 401。
func TestAuthMiddleware_WrongIssuer(t *testing.T) {
	gin.SetMode(gin.TestMode)
	expectedIssuer := "shuqing-bigdata"
	setupAuthEnv(t, expectedIssuer)

	// 使用错误的 issuer 生成 token
	token := generateTestToken(testSecret, "wrong-issuer", "tenant-001", "user-001")

	r := newProtectedRouter()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}
