package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// nl2sql 端点缺少租户上下文 → 403。
// 防止无租户的请求绕过隔离（来源：test-design-matrix 无 token → 401 用例的延伸）。
func TestNl2Sql_NoTenantContext_403(t *testing.T) {
	router := buildTestRouter(t, nil)
	// 构造一个没有 tenantId claim 的 token（空租户）
	token := makeTestToken(t, "")
	body := `{"query":"查询订单"}`
	w := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/nl2sql", token, body)
	assert.Equal(t, http.StatusForbidden, w.Code)
	assert.Contains(t, w.Body.String(), "租户")
}

// nl2sql 端点带有效租户 → 调用下游并透传 tenantId。
// 验证：请求体 tenantId 字段与 X-Tenant-Id 头均包含 JWT claim 的 tenantId。
func TestNl2Sql_ValidTenant_PassesTenantToDownstream(t *testing.T) {
	var gotBodyTenant, gotHeaderTenant string
	nl2sqlCalled := false
	fakeNl2sql := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		nl2sqlCalled = true
		gotHeaderTenant = r.Header.Get("X-Tenant-Id")
		raw, _ := io.ReadAll(r.Body)
		var payload map[string]string
		_ = json.Unmarshal(raw, &payload)
		gotBodyTenant = payload["tenantId"]
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"sql":"SELECT * FROM orders WHERE tenant_id = 'tenant-a' LIMIT 100;","dialect":"ANSI","tables":["orders"],"confidence":0.9}`)
	}))
	defer fakeNl2sql.Close()

	router := buildTestRouter(t, func(cfg *config.Config) {
		cfg.Nl2SqlURL = fakeNl2sql.URL
	})
	token := makeTestToken(t, "tenant-a")

	body := `{"query":"查询订单","dialect":"ANSI"}`
	w := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/nl2sql", token, body)
	require.Equal(t, http.StatusOK, w.Code, "body=%s", w.Body.String())
	require.True(t, nl2sqlCalled, "nl2sql 下游应被调用")
	assert.Equal(t, "tenant-a", gotBodyTenant, "请求体 tenantId 应等于 JWT claim")
	assert.Equal(t, "tenant-a", gotHeaderTenant, "X-Tenant-Id 头应等于 JWT claim")
}

// 不同租户的 token 调用 nl2sql → 透传不同的 tenantId 给下游。
// 验证租户隔离：tenant-a 和 tenant-b 的请求不会混淆。
func TestNl2Sql_DifferentTenants_DifferentTenantPassed(t *testing.T) {
	var gotTenants []string
	fakeNl2sql := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var payload map[string]string
		_ = json.Unmarshal(raw, &payload)
		gotTenants = append(gotTenants, payload["tenantId"])
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"sql":"SELECT 1","dialect":"ANSI","tables":[],"confidence":0.5}`)
	}))
	defer fakeNl2sql.Close()

	router := buildTestRouter(t, func(cfg *config.Config) {
		cfg.Nl2SqlURL = fakeNl2sql.URL
	})

	body := `{"query":"查询订单"}`
	wA := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/nl2sql",
		makeTestToken(t, "tenant-a"), body)
	require.Equal(t, http.StatusOK, wA.Code)

	wB := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/nl2sql",
		makeTestToken(t, "tenant-b"), body)
	require.Equal(t, http.StatusOK, wB.Code)

	require.Len(t, gotTenants, 2)
	assert.Equal(t, "tenant-a", gotTenants[0])
	assert.Equal(t, "tenant-b", gotTenants[1])
}

// Chat 链路调用 nl2sql 时也应透传 tenantId。
// 验证 assistant.go 中 Nl2Sql 调用已传入 req.TenantID。
func TestChat_Nl2SqlReceivesTenantId(t *testing.T) {
	var gotBodyTenant string
	nl2sqlCalled := false
	fakeNl2sql := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		nl2sqlCalled = true
		raw, _ := io.ReadAll(r.Body)
		var payload map[string]string
		_ = json.Unmarshal(raw, &payload)
		gotBodyTenant = payload["tenantId"]
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"sql":"SELECT 1","dialect":"ANSI","tables":[],"confidence":0.5}`)
	}))
	defer fakeNl2sql.Close()

	router := buildTestRouter(t, func(cfg *config.Config) {
		cfg.Nl2SqlURL = fakeNl2sql.URL
	})
	token := makeTestToken(t, "tenant-chat")

	body := `{"message":"查询订单","enableNl2Sql":true,"enableExec":false}`
	w := doJSON(router, http.MethodPost, "/api/v1/ai-assistant/chat", token, body)
	require.Equal(t, http.StatusOK, w.Code, "body=%s", w.Body.String())
	require.True(t, nl2sqlCalled, "nl2sql 下游应被 Chat 链路调用")
	assert.Equal(t, "tenant-chat", gotBodyTenant, "Chat 链路应透传 tenantId 给 nl2sql")
}
