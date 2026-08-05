package provider

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// ============ Mock Provider 测试 ============

// TestMockProvider_Name 验证 Name 返回配置的名称。
func TestMockProvider_Name(t *testing.T) {
	p := NewMockProvider(MockConfig{Name: "mock-test"})
	assert.Equal(t, "mock-test", p.Name())
}

// TestMockProvider_Name_Default 验证默认名称。
func TestMockProvider_Name_Default(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	assert.Equal(t, "mock", p.Name())
}

// TestMockProvider_ChatCompletion 验证对话补全返回 Mock 响应。
func TestMockProvider_ChatCompletion(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	resp, err := p.ChatCompletion(context.Background(), ChatRequest{
		Model:    "mock-gpt-4",
		Messages: []Message{{Role: "user", Content: "hello world"}},
	})
	require.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, "mock-gpt-4", resp.Model)
	assert.NotEmpty(t, resp.ID)
	require.Len(t, resp.Choices, 1)
	assert.Equal(t, "assistant", resp.Choices[0].Message.Role)
	assert.Contains(t, resp.Choices[0].Message.Content, "mock-gpt-4")
	assert.Contains(t, resp.Choices[0].Message.Content, "hello world")
	assert.Greater(t, resp.Usage.TotalTokens, 0)
	assert.Equal(t, resp.Usage.PromptTokens+resp.Usage.CompletionTokens, resp.Usage.TotalTokens)
}

// TestMockProvider_ChatCompletion_NoModel 验证缺少 model 返回错误。
func TestMockProvider_ChatCompletion_NoModel(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	_, err := p.ChatCompletion(context.Background(), ChatRequest{
		Messages: []Message{{Role: "user", Content: "hi"}},
	})
	assert.ErrorIs(t, err, ErrInvalidRequest)
}

// TestMockProvider_ChatCompletion_NoMessages 验证缺少 messages 返回错误。
func TestMockProvider_ChatCompletion_NoMessages(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	_, err := p.ChatCompletion(context.Background(), ChatRequest{Model: "mock-gpt-4"})
	assert.ErrorIs(t, err, ErrInvalidRequest)
}

// TestMockProvider_ChatCompletion_InjectedError 验证注入错误路径。
func TestMockProvider_ChatCompletion_InjectedError(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	injected := errors.New("upstream down")
	p.SetError(injected)
	_, err := p.ChatCompletion(context.Background(), ChatRequest{
		Model:    "mock-gpt-4",
		Messages: []Message{{Role: "user", Content: "hi"}},
	})
	assert.ErrorIs(t, err, injected)

	// 清除错误后应恢复正常
	p.SetError(nil)
	resp, err := p.ChatCompletion(context.Background(), ChatRequest{
		Model:    "mock-gpt-4",
		Messages: []Message{{Role: "user", Content: "hi"}},
	})
	require.NoError(t, err)
	assert.NotNil(t, resp)
}

// TestMockProvider_ChatCompletion_ContextCanceled 验证 context 取消。
func TestMockProvider_ChatCompletion_ContextCanceled(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := p.ChatCompletion(ctx, ChatRequest{
		Model:    "mock-gpt-4",
		Messages: []Message{{Role: "user", Content: "hi"}},
	})
	assert.ErrorIs(t, err, context.Canceled)
}

// TestMockProvider_Embeddings 验证向量化。
func TestMockProvider_Embeddings(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	resp, err := p.Embeddings(context.Background(), EmbeddingRequest{
		Model: "mock-embedding",
		Input: []string{"hello", "world"},
	})
	require.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, "mock-embedding", resp.Model)
	require.Len(t, resp.Data, 2)
	assert.Len(t, resp.Data[0].Embedding, 8)
	assert.Equal(t, 0, resp.Data[0].Index)
	assert.Equal(t, 1, resp.Data[1].Index)
	// 相同输入应产生相同向量
	resp2, _ := p.Embeddings(context.Background(), EmbeddingRequest{
		Model: "mock-embedding",
		Input: []string{"hello", "world"},
	})
	assert.Equal(t, resp.Data[0].Embedding, resp2.Data[0].Embedding)
}

// TestMockProvider_Embeddings_NoInput 验证空输入报错。
func TestMockProvider_Embeddings_NoInput(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	_, err := p.Embeddings(context.Background(), EmbeddingRequest{Model: "mock-embedding"})
	assert.ErrorIs(t, err, ErrInvalidRequest)
}

// TestMockProvider_Models 验证模型列表。
func TestMockProvider_Models(t *testing.T) {
	custom := []ModelInfo{{ID: "custom-1", Object: "model", OwnedBy: "test"}}
	p := NewMockProvider(MockConfig{Models: custom})
	models, err := p.Models(context.Background())
	require.NoError(t, err)
	assert.Equal(t, custom, models)
}

// TestMockProvider_Models_Default 验证默认模型列表。
func TestMockProvider_Models_Default(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	models, err := p.Models(context.Background())
	require.NoError(t, err)
	assert.Len(t, models, 2)
}

// TestMockProvider_HealthCheck 验证探活。
func TestMockProvider_HealthCheck(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	assert.NoError(t, p.HealthCheck(context.Background()))

	p.SetError(errors.New("down"))
	assert.Error(t, p.HealthCheck(context.Background()))
}

// TestMockProvider_CallCount 验证调用计数。
func TestMockProvider_CallCount(t *testing.T) {
	p := NewMockProvider(MockConfig{})
	assert.Equal(t, int64(0), p.CallCount())

	_, _ = p.ChatCompletion(context.Background(), ChatRequest{
		Model: "m", Messages: []Message{{Role: "user", Content: "a"}},
	})
	assert.Equal(t, int64(1), p.CallCount())

	_, _ = p.Embeddings(context.Background(), EmbeddingRequest{
		Model: "m", Input: []string{"a"},
	})
	assert.Equal(t, int64(2), p.CallCount())
}

// TestMockProvider_ImplementsInterface 编译期验证 MockProvider 实现 LLMProvider 接口。
func TestMockProvider_ImplementsInterface(t *testing.T) {
	var _ LLMProvider = (*MockProvider)(nil)
}

// TestEstimateTokens 验证 token 估算。
func TestEstimateTokens(t *testing.T) {
	assert.Equal(t, 1, estimateTokens([]Message{{Role: "user", Content: "hi"}}))
	// "hello world test" 长度 16，16/4 = 4
	assert.Equal(t, 4, estimateTokens([]Message{{Role: "user", Content: "hello world test"}}))
}
