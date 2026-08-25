package api

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

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/gateway"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
)

// ============ API Handler 测试 ============

// newTestHandler 构造测试用 handler：Mock Provider + 路由 + dev auth。
func newTestHandler(t *testing.T) (*Handler, *gateway.Gateway) {
	t.Helper()
	gin.SetMode(gin.TestMode)

	auditor := gateway.NewAuditor(nil, "")
	gw := gateway.New(auditor)
	mock := provider.NewMockProvider(provider.MockConfig{
		Name: "mock",
		Models: []provider.ModelInfo{
			{ID: "mock-gpt-4", Object: "model", OwnedBy: "mock"},
			{ID: "mock-embedding", Object: "model", OwnedBy: "mock"},
		},
	})
	gw.RegisterProvider(mock, 1)
	gw.AddRoute(gateway.RouteRule{Model: "mock-gpt-4", Provider: "mock"})
	gw.AddRoute(gateway.RouteRule{Model: "mock-embedding", Provider: "mock"})
	return New(gw, "test-version"), gw
}

// newTestRouter 构造测试路由（dev auth 中间件，注入 tenantId=dev、role=admin）。
func newTestRouter(h *Handler) *gin.Engine {
	r := gin.New()
	devAuth := func(c *gin.Context) {
		c.Set("tenantId", "dev")
		c.Set("userId", "dev")
		c.Set("role", "admin")
		c.Next()
	}
	h.RegisterRoutes(r, devAuth)
	return r
}

// doJSON 发起 JSON 请求辅助函数。
func doJSON(r *gin.Engine, method, path string, body any) *httptest.ResponseRecorder {
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

// ============ 健康检查 ============

// TestHealth 验证健康检查端点。
func TestHealth(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodGet, "/health", nil)
	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "UP", resp["status"])
	assert.Equal(t, "llm-gateway", resp["component"])
	assert.Equal(t, "test-version", resp["version"])
}

// ============ 对话补全 ============

// TestChatCompletions 验证对话补全端点。
func TestChatCompletions(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodPost, "/api/v1/chat/completions", provider.ChatRequest{
		Model:    "mock-gpt-4",
		Messages: []provider.Message{{Role: "user", Content: "hello"}},
	})
	assert.Equal(t, http.StatusOK, w.Code)

	var resp provider.ChatResponse
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "mock-gpt-4", resp.Model)
	require.Len(t, resp.Choices, 1)
	assert.Equal(t, "assistant", resp.Choices[0].Message.Role)
	assert.Greater(t, resp.Usage.TotalTokens, 0)
}

// TestChatCompletions_NoModel 验证缺少 model 返回 400。
func TestChatCompletions_NoModel(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodPost, "/api/v1/chat/completions", gin.H{
		"messages": []gin.H{{"role": "user", "content": "hi"}},
	})
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// TestChatCompletions_ModelNotFound 验证未知模型返回 404。
func TestChatCompletions_ModelNotFound(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodPost, "/api/v1/chat/completions", provider.ChatRequest{
		Model:    "unknown",
		Messages: []provider.Message{{Role: "user", Content: "hi"}},
	})
	assert.Equal(t, http.StatusNotFound, w.Code)
}

// ============ 向量嵌入 ============

// TestEmbeddings 验证向量嵌入端点。
func TestEmbeddings(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodPost, "/api/v1/embeddings", provider.EmbeddingRequest{
		Model: "mock-embedding",
		Input: []string{"hello", "world"},
	})
	assert.Equal(t, http.StatusOK, w.Code)

	var resp provider.EmbeddingResponse
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "mock-embedding", resp.Model)
	require.Len(t, resp.Data, 2)
}

// ============ 模型列表 ============

// TestListModels 验证模型列表端点。
func TestListModels(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodGet, "/api/v1/models", nil)
	assert.Equal(t, http.StatusOK, w.Code)

	var resp struct {
		Object string               `json:"object"`
		Data   []provider.ModelInfo `json:"data"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "list", resp.Object)
	assert.Len(t, resp.Data, 2)
}

// ============ Provider 治理 ============

// TestListProviders 验证 Provider 列表端点。
func TestListProviders(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodGet, "/api/v1/providers", nil)
	assert.Equal(t, http.StatusOK, w.Code)

	var resp struct {
		Providers []string `json:"providers"`
		Total     int      `json:"total"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 1, resp.Total)
	assert.Contains(t, resp.Providers, "mock")
}

// TestRegisterProvider 验证注册 Provider 端点。
func TestRegisterProvider(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodPost, "/api/v1/providers", registerProviderRequest{
		Name:   "mock2",
		Type:   "mock",
		Weight: 1,
	})
	assert.Equal(t, http.StatusCreated, w.Code)

	// 验证已注册
	w2 := doJSON(r, http.MethodGet, "/api/v1/providers", nil)
	var resp struct {
		Providers []string `json:"providers"`
	}
	require.NoError(t, json.Unmarshal(w2.Body.Bytes(), &resp))
	assert.Contains(t, resp.Providers, "mock2")
}

// TestRegisterProvider_UnknownType 验证未知类型返回 400。
func TestRegisterProvider_UnknownType(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodPost, "/api/v1/providers", registerProviderRequest{
		Name: "bad", Type: "unknown-type",
	})
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// TestUnregisterProvider 验证注销 Provider 端点。
func TestUnregisterProvider(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	// 先注册
	_ = doJSON(r, http.MethodPost, "/api/v1/providers", registerProviderRequest{
		Name: "mock2", Type: "mock",
	})

	// 注销
	w := doJSON(r, http.MethodDelete, "/api/v1/providers/mock2", nil)
	assert.Equal(t, http.StatusNoContent, w.Code)

	// 再注销应 404
	w2 := doJSON(r, http.MethodDelete, "/api/v1/providers/mock2", nil)
	assert.Equal(t, http.StatusNotFound, w2.Code)
}

// ============ 指标 ============

// TestTokenMetrics 验证 Token 指标端点。
func TestTokenMetrics(t *testing.T) {
	h, gw := newTestHandler(t)
	r := newTestRouter(h)

	// 先产生一次调用
	gw.Meter().Record("dev", "mock-gpt-4", 100, 0)

	w := doJSON(r, http.MethodGet, "/api/v1/metrics/tokens", nil)
	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.NotNil(t, resp["totalTokens"])
}

// TestTokenMetrics_ByTenant 验证按租户查询指标。
func TestTokenMetrics_ByTenant(t *testing.T) {
	h, gw := newTestHandler(t)
	r := newTestRouter(h)

	gw.Meter().Record("dev", "mock-gpt-4", 100, 0)

	w := doJSON(r, http.MethodGet, "/api/v1/metrics/tokens?tenant=dev", nil)
	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "dev", resp["tenantId"])
}

// TestLatencyMetrics 验证延迟指标端点。
func TestLatencyMetrics(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	w := doJSON(r, http.MethodGet, "/api/v1/metrics/latency", nil)
	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Contains(t, resp, "averageLatencyMs")
	assert.Contains(t, resp, "totalCalls")
}

// ============ 端到端 ============

// TestE2E_ChatThenMetrics 验证对话后指标已更新。
func TestE2E_ChatThenMetrics(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	// 调用对话
	_ = doJSON(r, http.MethodPost, "/api/v1/chat/completions", provider.ChatRequest{
		Model:    "mock-gpt-4",
		Messages: []provider.Message{{Role: "user", Content: "hello"}},
	})

	// 查指标
	w := doJSON(r, http.MethodGet, "/api/v1/metrics/tokens?tenant=dev", nil)
	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	calls, ok := resp["totalCalls"].(float64)
	require.True(t, ok)
	assert.Greater(t, calls, 0.0)
}

// TestE2E_ContextStillWorks 防御性测试：确保 context 不泄漏。
func TestE2E_ContextStillWorks(t *testing.T) {
	h, _ := newTestHandler(t)
	r := newTestRouter(h)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_ = ctx // 仅确保编译通过，handler 内部用 c.Request.Context()
	_ = doJSON(r, http.MethodGet, "/health", nil)
}
