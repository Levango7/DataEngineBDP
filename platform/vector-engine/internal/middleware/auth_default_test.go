package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

// setupCleanAuthEnv 清空认证相关环境变量，模拟"未做任何配置"的裸部署。
func setupCleanAuthEnv(t *testing.T) {
	t.Helper()
	for _, k := range []string{"VECTOR_AUTH_REQUIRED", "JWT_DEV_MODE", "JWT_SIGNING_KEY", "JWT_ISSUER"} {
		t.Setenv(k, "")
	}
}

// TestAuthRequiredByDefault 默认（未配置任何环境变量）必须拒绝匿名访问。
func TestAuthRequiredByDefault(t *testing.T) {
	gin.SetMode(gin.TestMode)
	setupCleanAuthEnv(t)
	t.Setenv("JWT_SIGNING_KEY", testSecret)

	r := newProtectedRouter()
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// TestAuthExplicitOptOut 显式 VECTOR_AUTH_REQUIRED=false 才放行匿名。
func TestAuthExplicitOptOut(t *testing.T) {
	gin.SetMode(gin.TestMode)
	setupCleanAuthEnv(t)
	t.Setenv("VECTOR_AUTH_REQUIRED", "false")

	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/anon", func(c *gin.Context) { c.Status(http.StatusOK) })

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/anon", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}

// TestAuthDevModeInjectsIdentity dev 模式注入 dev 身份且不要求密钥。
func TestAuthDevModeInjectsIdentity(t *testing.T) {
	gin.SetMode(gin.TestMode)
	setupCleanAuthEnv(t)
	t.Setenv("JWT_DEV_MODE", "true")

	r := newProtectedRouter()
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/protected", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}
