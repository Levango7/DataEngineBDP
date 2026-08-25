package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/config"
	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/middleware"
	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/service"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const testJWTSecret = "unit-test-signing-key-at-least-32-bytes!"

// setTestAuthEnv 注入认证中间件所需环境变量（fail-fast 策略要求显式配置）。
func setTestAuthEnv(t *testing.T) {
	t.Helper()
	t.Setenv("JWT_SIGNING_KEY", testJWTSecret)
	t.Setenv("JWT_ISSUER", "shuqing-bigdata")
}

// makeTestToken 签发测试用 HS256 token（issuer 与中间件校验值一致）。
func makeTestToken(t *testing.T, tenantID string) string {
	t.Helper()
	claims := jwt.MapClaims{
		"sub":      "user-1",
		"tenantId": tenantID,
		"iss":      "shuqing-bigdata",
		"iat":      time.Now().Unix(),
		"exp":      time.Now().Add(time.Hour).Unix(),
	}
	signed, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(testJWTSecret))
	require.NoError(t, err)
	return signed
}

// buildTestRouter 按main.go 的路由结构构建测试路由：
// /health 匿名，业务组挂 AuthMiddleware。mutate 可覆盖下游地址等配置。
func buildTestRouter(t *testing.T, mutate func(*config.Config)) *gin.Engine {
	t.Helper()
	setTestAuthEnv(t)
	gin.SetMode(gin.TestMode)

	cfg := &config.Config{
		Port:          "18110",
		SessionDBPath: "file::memory:?cache=shared",
		LlmGatewayURL: "http://127.0.0.1:1",
		Nl2SqlURL:     "http://127.0.0.1:1",
		SqlGatewayURL: "http://127.0.0.1:1",
	}
	if mutate != nil {
		mutate(cfg)
	}

	store, err := service.NewSessionStore(cfg.SessionDBPath)
	require.NoError(t, err)
	proxy := service.NewDownstreamProxy(cfg)
	svc := service.NewAssistantService(store, proxy, cfg)

	r := gin.New()
	r.GET("/api/v1/health", Health)

	protected := r.Group("/api/v1/ai-assistant")
	protected.Use(middleware.AuthMiddleware())
	RegisterRoutes(protected, svc, cfg)
	return r
}

// bytesReader 字符串转 io.Reader，便于 httptest.NewRequest 使用。
func bytesReader(s string) *bytes.Reader {
	return bytes.NewReader([]byte(s))
}

// doJSON 发送带认证头的 JSON POST 请求。
func doJSON(r *gin.Engine, method, path, token, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, bytesReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// 无 token 访问任意业务端点 → 401。
func TestProtectedRoutes_NoToken_401(t *testing.T) {
	router := buildTestRouter(t, nil)

	cases := []struct {
		method, path string
	}{
		{http.MethodPost, "/api/v1/ai-assistant/chat"},
		{http.MethodPost, "/api/v1/ai-assistant/chat/stream"},
		{http.MethodPost, "/api/v1/ai-assistant/nl2sql"},
		{http.MethodPost, "/api/v1/ai-assistant/execute"},
		{http.MethodGet, "/api/v1/ai-assistant/sessions"},
		{http.MethodPost, "/api/v1/ai-assistant/sessions"},
		{http.MethodGet, "/api/v1/ai-assistant/sessions/some-id"},
		{http.MethodDelete, "/api/v1/ai-assistant/sessions/some-id"},
	}
	for _, tc := range cases {
		w := doJSON(router, tc.method, tc.path, "", `{}`)
		assert.Equal(t, http.StatusUnauthorized, w.Code, "%s %s 应 401，body=%s", tc.method, tc.path, w.Body.String())
	}
}

// 无效 token → 401。
func TestProtectedRoutes_InvalidToken_401(t *testing.T) {
	router := buildTestRouter(t, nil)
	w := doJSON(router, http.MethodGet, "/api/v1/ai-assistant/sessions", "not-a-valid-jwt", "")
	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// /health 匿名可访问 → 200。
func TestHealth_NoToken_200(t *testing.T) {
	router := buildTestRouter(t, nil)
	w := doJSON(router, http.MethodGet, "/api/v1/health", "", "")
	require.Equal(t, http.StatusOK, w.Code)
	assert.Contains(t, w.Body.String(), "UP")
}

// body tenantId 与 claim 不一致 → 403。
func TestExecute_TenantMismatch_403(t *testing.T) {
	router := buildTestRouter(t, nil)
	token := makeTestToken(t, "tenant-a")

	body := `{"sql":"SELECT 1","dialect":"ANSI","tenantId":"other"}`
	w := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/execute", token, body)
	require.Equal(t, http.StatusForbidden, w.Code)
	assert.Contains(t, w.Body.String(), "tenantId")
}

// SSE 流式端点同样强制租户：body tenantId 不一致 → 403。
func TestChatStream_TenantMismatch_403(t *testing.T) {
	router := buildTestRouter(t, nil)
	token := makeTestToken(t, "tenant-a")

	body := `{"message":"查询订单","tenantId":"other"}`
	w := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/chat/stream", token, body)
	require.Equal(t, http.StatusForbidden, w.Code)
	assert.NotContains(t, w.Body.String(), "text/event-stream")
}

// 空 body tenantId → 回填 claim 值透传给 sql-gateway。
func TestExecute_EmptyBodyTenant_BackfilledFromClaim(t *testing.T) {
	var gotTenant string
	gatewayCalled := false
	fakeGateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gatewayCalled = true
		raw, _ := io.ReadAll(r.Body)
		var payload map[string]string
		_ = json.Unmarshal(raw, &payload)
		gotTenant = payload["tenantId"]
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"status":"SUCCESS","columns":[],"rows":[]}`)
	}))
	defer fakeGateway.Close()

	router := buildTestRouter(t, func(cfg *config.Config) {
		cfg.SqlGatewayURL = fakeGateway.URL
	})
	token := makeTestToken(t, "tenant-a")

	body := `{"sql":"SELECT 1","dialect":"ANSI"}`
	w := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/execute", token, body)
	require.Equal(t, http.StatusOK, w.Code)
	require.True(t, gatewayCalled, "sql-gateway 应被调用")
	assert.Equal(t, "tenant-a", gotTenant, "透传给 sql-gateway 的 tenantId 应等于 JWT claim")
}

// body tenantId 与 claim 一致 → 放行并原值透传。
func TestExecute_TenantMatchesClaim_OK(t *testing.T) {
	var gotTenant string
	fakeGateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var payload map[string]string
		_ = json.Unmarshal(raw, &payload)
		gotTenant = payload["tenantId"]
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"status":"SUCCESS","columns":[],"rows":[]}`)
	}))
	defer fakeGateway.Close()

	router := buildTestRouter(t, func(cfg *config.Config) {
		cfg.SqlGatewayURL = fakeGateway.URL
	})
	token := makeTestToken(t, "tenant-a")

	body := `{"sql":"SELECT 1","dialect":"ANSI","tenantId":"tenant-a"}`
	w := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/execute", token, body)
	require.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, "tenant-a", gotTenant)
}
