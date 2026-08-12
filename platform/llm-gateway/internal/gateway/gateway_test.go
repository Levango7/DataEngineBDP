package gateway

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
)

// ============ 网关集成测试 ============

// newTestGateway 构造测试用网关：注册 Mock Provider + 路由规则。
func newTestGateway(t *testing.T) *Gateway {
	t.Helper()
	auditor := NewAuditor(nil, "")
	gw := New(auditor)
	mock := provider.NewMockProvider(provider.MockConfig{
		Name: "mock",
		Models: []provider.ModelInfo{
			{ID: "mock-gpt-4", Object: "model", OwnedBy: "mock"},
		},
	})
	gw.RegisterProvider(mock, 1)
	gw.AddRoute(RouteRule{Model: "mock-gpt-4", Provider: "mock"})
	return gw
}

// TestGateway_ChatCompletion 验证对话补全全链路。
func TestGateway_ChatCompletion(t *testing.T) {
	gw := newTestGateway(t)
	resp, err := gw.ChatCompletion(context.Background(), provider.ChatRequest{
		Model:    "mock-gpt-4",
		Messages: []provider.Message{{Role: "user", Content: "hello"}},
		TenantID: "t1",
		UserID:   "u1",
	})
	require.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, "mock-gpt-4", resp.Model)

	// 验证计量已记录
	stats := gw.Meter().TenantStats("t1")
	assert.Equal(t, int64(1), stats.TotalCalls)
	assert.Greater(t, stats.TotalTokens, int64(0))
}

// TestGateway_ChatCompletion_ModelNotFound 验证未知模型路由失败。
func TestGateway_ChatCompletion_ModelNotFound(t *testing.T) {
	gw := newTestGateway(t)
	_, err := gw.ChatCompletion(context.Background(), provider.ChatRequest{
		Model:    "unknown-model",
		Messages: []provider.Message{{Role: "user", Content: "hi"}},
	})
	assert.ErrorIs(t, err, provider.ErrModelNotFound)
}

// TestGateway_Embeddings 验证向量化全链路。
func TestGateway_Embeddings(t *testing.T) {
	gw := newTestGateway(t)
	gw.AddRoute(RouteRule{Model: "mock-embedding", Provider: "mock"})
	resp, err := gw.Embeddings(context.Background(), provider.EmbeddingRequest{
		Model:    "mock-embedding",
		Input:    []string{"hello"},
		TenantID: "t1",
	})
	require.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Len(t, resp.Data, 1)
}

// TestGateway_ListModels 验证模型列表聚合。
func TestGateway_ListModels(t *testing.T) {
	gw := newTestGateway(t)
	models, err := gw.ListModels(context.Background())
	require.NoError(t, err)
	assert.Len(t, models, 1)
	assert.Equal(t, "mock-gpt-4", models[0].ID)
}

// TestGateway_ListProviders 验证 Provider 列表。
func TestGateway_ListProviders(t *testing.T) {
	gw := newTestGateway(t)
	names := gw.ListProviders()
	assert.Equal(t, []string{"mock"}, names)
}

// TestGateway_RegisterUnregister 验证动态注册/注销 Provider。
func TestGateway_RegisterUnregister(t *testing.T) {
	gw := newTestGateway(t)
	mock2 := provider.NewMockProvider(provider.MockConfig{Name: "mock2"})
	gw.RegisterProvider(mock2, 1)
	assert.Contains(t, gw.ListProviders(), "mock2")

	assert.True(t, gw.UnregisterProvider("mock2"))
	assert.NotContains(t, gw.ListProviders(), "mock2")

	assert.False(t, gw.UnregisterProvider("nonexistent"))
}

// TestGateway_HealthCheck 验证健康检查。
func TestGateway_HealthCheck(t *testing.T) {
	gw := newTestGateway(t)
	unhealthy := gw.HealthCheck(context.Background())
	assert.Empty(t, unhealthy)
}

// TestGateway_SensitiveContent 验证敏感词拦截。
func TestGateway_SensitiveContent(t *testing.T) {
	auditor := NewAuditor([]string{"password"}, "")
	gw := New(auditor)
	mock := provider.NewMockProvider(provider.MockConfig{Name: "mock"})
	gw.RegisterProvider(mock, 1)
	gw.AddRoute(RouteRule{Model: "mock-gpt-4", Provider: "mock"})

	_, err := gw.ChatCompletion(context.Background(), provider.ChatRequest{
		Model:    "mock-gpt-4",
		Messages: []provider.Message{{Role: "user", Content: "my password is 123456"}},
	})
	assert.ErrorIs(t, err, provider.ErrSensitiveContent)
}

// TestGateway_MultiProviderRouting 验证多 Provider 路由。
func TestGateway_MultiProviderRouting(t *testing.T) {
	auditor := NewAuditor(nil, "")
	gw := New(auditor)

	p1 := provider.NewMockProvider(provider.MockConfig{
		Name:   "openai-mock",
		Models: []provider.ModelInfo{{ID: "gpt-4", Object: "model", OwnedBy: "openai"}},
	})
	p2 := provider.NewMockProvider(provider.MockConfig{
		Name:   "wenxin-mock",
		Models: []provider.ModelInfo{{ID: "ernie-bot", Object: "model", OwnedBy: "wenxin"}},
	})
	gw.RegisterProvider(p1, 1)
	gw.RegisterProvider(p2, 1)
	gw.AddRoute(RouteRule{Model: "gpt-4", Provider: "openai-mock"})
	gw.AddRoute(RouteRule{Model: "ernie-bot", Provider: "wenxin-mock"})

	// 调 gpt-4 应路由到 openai-mock
	resp, err := gw.ChatCompletion(context.Background(), provider.ChatRequest{
		Model:    "gpt-4",
		Messages: []provider.Message{{Role: "user", Content: "hi"}},
	})
	require.NoError(t, err)
	assert.Contains(t, resp.Choices[0].Message.Content, "gpt-4")

	// 调 ernie-bot 应路由到 wenxin-mock
	resp, err = gw.ChatCompletion(context.Background(), provider.ChatRequest{
		Model:    "ernie-bot",
		Messages: []provider.Message{{Role: "user", Content: "hi"}},
	})
	require.NoError(t, err)
	assert.Contains(t, resp.Choices[0].Message.Content, "ernie-bot")
}
