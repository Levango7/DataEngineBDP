package provider

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"sync/atomic"
	"time"
)

// ============ Mock Provider ============
//
// 用于测试与开发环境的 Mock 适配器，不依赖外部 API。
// 返回确定性但可区分的响应：内容包含模型名与请求摘要，
// Token 用量基于输入估算。支持通过 SetError 注入故障用于错误路径测试。

// MockProvider Mock 大模型适配器。
type MockProvider struct {
	name      string
	models    []ModelInfo
	latency   time.Duration // 模拟延迟
	errorMode atomic.Value  // errorMode 注入错误（nil 表示无错误）
	callCount atomic.Int64  // 调用计数
}

// MockConfig Mock Provider 配置。
type MockConfig struct {
	Name    string
	Models  []ModelInfo
	Latency time.Duration
}

// NewMockProvider 构造 Mock 适配器。
func NewMockProvider(cfg MockConfig) *MockProvider {
	name := cfg.Name
	if name == "" {
		name = "mock"
	}
	if len(cfg.Models) == 0 {
		cfg.Models = []ModelInfo{
			{ID: "mock-gpt-4", Object: "model", OwnedBy: "mock"},
			{ID: "mock-embedding", Object: "model", OwnedBy: "mock"},
		}
	}
	p := &MockProvider{
		name:    name,
		models:  cfg.Models,
		latency: cfg.Latency,
	}
	p.errorMode.Store(noError)
	return p
}

// Name 返回 Provider 标识。
func (p *MockProvider) Name() string { return p.name }

// SetError 注入错误，下次调用返回该错误。传 nil 清除错误。
func (p *MockProvider) SetError(err error) {
	if err == nil {
		p.errorMode.Store(noError)
		return
	}
	p.errorMode.Store(err)
}

// CallCount 返回累计调用次数（ChatCompletion + Embeddings）。
func (p *MockProvider) CallCount() int64 {
	return p.callCount.Load()
}

// sleep 模拟延迟。
func (p *MockProvider) sleep() {
	if p.latency > 0 {
		time.Sleep(p.latency)
	}
}

// loadError 取出当前注入错误。
func (p *MockProvider) loadError() error {
	v := p.errorMode.Load()
	if v == nil {
		return nil
	}
	err, _ := v.(error)
	if errors.Is(err, noError) {
		return nil
	}
	return err
}

// ChatCompletion 返回 Mock 对话补全响应。
func (p *MockProvider) ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
	p.callCount.Add(1)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := p.loadError(); err != nil {
		return nil, err
	}
	if req.Model == "" {
		return nil, fmt.Errorf("%w: model is required", ErrInvalidRequest)
	}
	if len(req.Messages) == 0 {
		return nil, fmt.Errorf("%w: messages must not be empty", ErrInvalidRequest)
	}

	p.sleep()

	promptTokens := estimateTokens(req.Messages)
	// Mock 回复内容：包含模型名与最后一条用户消息摘要，便于断言。
	lastUser := ""
	for i := len(req.Messages) - 1; i >= 0; i-- {
		if req.Messages[i].Role == "user" {
			lastUser = req.Messages[i].Content
			break
		}
	}
	reply := fmt.Sprintf("[mock:%s] echo: %s", req.Model, truncate(lastUser, 64))
	completionTokens := len(reply) / 4
	if completionTokens == 0 {
		completionTokens = 1
	}

	return &ChatResponse{
		ID:     mockID("chatcmpl"),
		Object: "chat.completion",
		Model:  req.Model,
		Choices: []Choice{
			{
				Index:        0,
				Message:      Message{Role: "assistant", Content: reply},
				FinishReason: "stop",
			},
		},
		Usage: Usage{
			PromptTokens:     promptTokens,
			CompletionTokens: completionTokens,
			TotalTokens:      promptTokens + completionTokens,
		},
	}, nil
}

// Embeddings 返回 Mock 向量化响应。
func (p *MockProvider) Embeddings(ctx context.Context, req EmbeddingRequest) (*EmbeddingResponse, error) {
	p.callCount.Add(1)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := p.loadError(); err != nil {
		return nil, err
	}
	if req.Model == "" {
		return nil, fmt.Errorf("%w: model is required", ErrInvalidRequest)
	}
	if len(req.Input) == 0 {
		return nil, fmt.Errorf("%w: input must not be empty", ErrInvalidRequest)
	}

	p.sleep()

	promptTokens := estimateEmbeddingTokens(req.Input)
	data := make([]EmbeddingData, len(req.Input))
	for i, s := range req.Input {
		data[i] = EmbeddingData{
			Object:    "embedding",
			Index:     i,
			Embedding: mockEmbedding(s, 8),
		}
	}
	return &EmbeddingResponse{
		Object: "list",
		Model:  req.Model,
		Data:   data,
		Usage: Usage{
			PromptTokens: promptTokens,
			TotalTokens:  promptTokens,
		},
	}, nil
}

// Models 返回 Mock 支持的模型列表。
func (p *MockProvider) Models(_ context.Context) ([]ModelInfo, error) {
	if err := p.loadError(); err != nil {
		return nil, err
	}
	return p.models, nil
}

// HealthCheck Mock 探活，始终返回 nil（除非注入错误）。
func (p *MockProvider) HealthCheck(_ context.Context) error {
	return p.loadError()
}

// ============ 辅助函数 ============

// truncate 截断字符串到 maxLen 字符。
func truncate(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen] + "..."
}

// mockID 生成形如 prefix-<hex12> 的 ID。
func mockID(prefix string) string {
	var b [6]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
	}
	return fmt.Sprintf("%s-%x", prefix, b[:])
}

// mockEmbedding 基于输入字符串生成确定性向量（维度 dim）。
func mockEmbedding(s string, dim int) []float64 {
	v := make([]float64, dim)
	for i := 0; i < dim; i++ {
		// 简单哈希：字符码累加 + 位置扰动，保证相同输入得到相同向量。
		var sum float64
		for j, c := range s {
			sum += float64(c) * float64(j+1) / 100.0
		}
		v[i] = sum / float64(i+1)
	}
	return v
}

// noError 是 atomic.Value 中表示"无错误"的 sentinel。
// atomic.Value 不允许 Store nil，故用此非 nil 的零值 error。
var noError = errors.New("no-error")
