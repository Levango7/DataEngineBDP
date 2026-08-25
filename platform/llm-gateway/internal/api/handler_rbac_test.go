package api

// handler_rbac_test.go 锁定治理端点的 RBAC 行为：
// Provider 注册/注销与路由规则添加仅限 admin（SSRF 防线）；
// Token 计量非 admin 强制限定自身租户。

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/routing"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/token"
)

// roleAuthMiddleware 构造指定角色/租户的认证替身。
func roleAuthMiddleware(role, tenantID string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Set("role", role)
		c.Set("tenantId", tenantID)
		c.Set("userId", "user-"+role)
		c.Next()
	}
}

func newRBACRouter(h *Handler, role, tenantID string) *gin.Engine {
	r := gin.New()
	h.RegisterRoutes(r, roleAuthMiddleware(role, tenantID))
	mm := NewMultimodalHandler(routing.NewEngine(), token.NewCounter(),
		func(ctx context.Context, req provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error) {
			return &provider.MultimodalChatResponse{}, nil
		})
	mm.RegisterRoutes(r, roleAuthMiddleware(role, tenantID))
	return r
}

func doReq(r *gin.Engine, method, path string, body any) *httptest.ResponseRecorder {
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// TestProviderGovernance_MemberForbidden 普通租户不得注册/注销 Provider（SSRF 防线）。
func TestProviderGovernance_MemberForbidden(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newRBACRouter(h, "user", "tenant-a")

	w := doReq(r, http.MethodPost, "/api/v1/providers",
		map[string]any{"name": "evil", "type": "openai", "endpoint": "http://169.254.169.254/latest", "apiKey": "k"})
	assert.Equal(t, http.StatusForbidden, w.Code, "member 注册 Provider 应被拒绝")

	w = doReq(r, http.MethodDelete, "/api/v1/providers/mock-gpt-4-provider", nil)
	assert.Equal(t, http.StatusForbidden, w.Code, "member 注销 Provider 应被拒绝")
}

// TestProviderGovernance_AdminAllowed admin 可注册 Provider。
func TestProviderGovernance_AdminAllowed(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newRBACRouter(h, "admin", "tenant-a")

	w := doReq(r, http.MethodPost, "/api/v1/providers",
		map[string]any{"name": "ok-mock", "type": "mock", "endpoint": "http://localhost",
			"models": []any{map[string]any{"id": "rbac-m1", "object": "model"}}})
	assert.Equal(t, http.StatusCreated, w.Code)
}

// TestAddRoutingRule_MemberForbidden 普通租户不得添加路由规则。
func TestAddRoutingRule_MemberForbidden(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newRBACRouter(h, "user", "tenant-a")

	w := doReq(r, http.MethodPost, "/v1/routing/rules", map[string]any{"model": "x", "provider": "y"})
	assert.Equal(t, http.StatusForbidden, w.Code)
}

// TestTokenMetrics_NonAdminForcedOwnTenant 非 admin 忽略 ?tenant= 参数，强制查自身租户。
func TestTokenMetrics_NonAdminForcedOwnTenant(t *testing.T) {
	h, gw := newTestHandler(t)
	gw.Meter().Record("tenant-a", "mock-gpt-4", 100, 0)
	gw.Meter().Record("tenant-b", "mock-gpt-4", 999, 0)
	r := newRBACRouter(h, "user", "tenant-a")

	w := doReq(r, http.MethodGet, "/api/v1/metrics/tokens?tenant=tenant-b", nil)
	require.Equal(t, http.StatusOK, w.Code)

	var resp map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "tenant-a", resp["tenantId"], "非 admin 只能看到自身租户数据")
	tokens, ok := resp["totalTokens"].(float64)
	require.True(t, ok)
	assert.Equal(t, float64(100), tokens, "不得泄露他租户用量")
}

// TestTokenMetrics_AdminMayQueryAnyTenant admin 保留跨租户审计能力。
func TestTokenMetrics_AdminMayQueryAnyTenant(t *testing.T) {
	h, gw := newTestHandler(t)
	gw.Meter().Record("tenant-b", "mock-gpt-4", 777, 0)
	r := newRBACRouter(h, "admin", "tenant-a")

	w := doReq(r, http.MethodGet, "/api/v1/metrics/tokens?tenant=tenant-b", nil)
	require.Equal(t, http.StatusOK, w.Code)

	var resp map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "tenant-b", resp["tenantId"])
}
