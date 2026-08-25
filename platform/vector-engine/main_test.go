package main

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/api"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/service"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store/mock"
)

// testJWTSigningKey 测试用签名密钥（>= 32 字节，满足 AuthMiddleware 长度校验）。
const testJWTSigningKey = "unit-test-signing-key-0123456789abcdef"

// newTestServer 构建与生产一致的完整路由（含鉴权中间件）。
func newTestServer(t *testing.T) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)
	t.Setenv("JWT_SIGNING_KEY", testJWTSigningKey)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return newRouter(logger,
		api.NewHealthHandler("test-version", "vector-engine"),
		api.NewVectorHandler(service.NewVectorService(mock.NewMockVectorStore())),
	)
}

// signToken 按平台约定签发有效 JWT（HS256，issuer=shuqing-bigdata）。
func signToken(t *testing.T) string {
	t.Helper()
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"iss":      "shuqing-bigdata",
		"sub":      "tester",
		"tenantId": "tenant-1",
		"exp":      time.Now().Add(time.Hour).Unix(),
	})
	signed, err := token.SignedString([]byte(testJWTSigningKey))
	require.NoError(t, err)
	return signed
}

func TestHealthEndpointAnonymousAccess(t *testing.T) {
	r := newTestServer(t)

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Contains(t, w.Body.String(), `"status":"UP"`)
}

func TestBusinessRouteWithoutToken401(t *testing.T) {
	r := newTestServer(t)

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/collections/col/stats", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

func TestBusinessRouteWithInvalidToken401(t *testing.T) {
	r := newTestServer(t)

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/collections/col/stats", nil)
	req.Header.Set("Authorization", "Bearer invalid.token.here")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

func TestBusinessRouteWithValidToken200(t *testing.T) {
	r := newTestServer(t)

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/vector", nil)
	req.Header.Set("Authorization", "Bearer "+signToken(t))
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}
