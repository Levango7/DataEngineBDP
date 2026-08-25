package main

import (
	"bytes"
	"io"
	"log"
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
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/config"
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

// TestSelectStoreMockEmitsDemoModeWarning STORE_TYPE=mock 时启动日志输出演示模式告警。
func TestSelectStoreMockEmitsDemoModeWarning(t *testing.T) {
	var buf bytes.Buffer
	old := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(old)

	t.Setenv("STORE_TYPE", "mock")
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	_, demoMode := selectStore(config.Load(), logger)
	require.True(t, demoMode)
	warnDemoMode(log.Writer(), demoMode, serviceName)

	assert.Contains(t, buf.String(), "演示模式")
	assert.Contains(t, buf.String(), serviceName)
}

// TestSelectStoreUnknownTypeFallsBackToDemoMode 未知 STORE_TYPE 回退 Mock 同样标记演示模式。
func TestSelectStoreUnknownTypeFallsBackToDemoMode(t *testing.T) {
	t.Setenv("STORE_TYPE", "unknown-backend")
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	_, demoMode := selectStore(config.Load(), logger)
	assert.True(t, demoMode)
}

// TestSelectStoreMilvusFallbackFlagsDemoMode 默认构建（无 milvus_enabled）下 milvus 回退 Mock 标记演示模式。
func TestSelectStoreMilvusFallbackFlagsDemoMode(t *testing.T) {
	t.Setenv("STORE_TYPE", "milvus")
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	_, demoMode := selectStore(config.Load(), logger)
	assert.True(t, demoMode)
}

// TestRealStoreSuppressesDemoModeWarning 非内存 Mock 后端不输出演示模式告警。
func TestRealStoreSuppressesDemoModeWarning(t *testing.T) {
	var buf bytes.Buffer
	warnDemoMode(&buf, false, serviceName)

	assert.Empty(t, buf.String())
	assert.NotContains(t, buf.String(), "演示模式")
}
