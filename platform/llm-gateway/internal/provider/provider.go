// Package provider 定义大模型 Provider 的统一抽象以及各厂商适配器。
//
// 设计策略：采用接口抽象 + Mock 实现策略。LLMProvider 接口定义统一能力，
// 各厂商适配器（OpenAI / 文心 / 通义 / 智谱）通过 HTTP 调用对应大模型 API，
// MockProvider 用于测试与开发环境。真实 API 凭据通过配置注入，不硬编码。
package provider

import (
	"context"
	"errors"
)

// ============ 接口定义 ============

// LLMProvider 抽象大模型服务提供方的统一能力。
//
// 每个适配器需实现该接口：OpenAI / 文心一言 / 通义千问 / 智谱 GLM / Mock。
// 网关层通过该接口调用底层模型服务，屏蔽各厂商协议差异。
type LLMProvider interface {
	// Name 返回 Provider 唯一标识，如 "openai" / "wenxin" / "qianwen" / "zhipu" / "mock"。
	Name() string

	// ChatCompletion 执行对话补全，对应 POST /v1/chat/completions。
	ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error)

	// Embeddings 执行文本向量化，对应 POST /v1/embeddings。
	Embeddings(ctx context.Context, req EmbeddingRequest) (*EmbeddingResponse, error)

	// Models 返回该 Provider 支持的模型列表。
	Models(ctx context.Context) ([]ModelInfo, error)

	// HealthCheck 探活，检测底层 API 是否可达。
	HealthCheck(ctx context.Context) error
}

// ============ 对话补全数据模型 ============

// ChatRequest 对话补全请求，OpenAI 兼容协议。
type ChatRequest struct {
	Model       string    `json:"model"`
	Messages    []Message `json:"messages"`
	Temperature float64   `json:"temperature,omitempty"`
	MaxTokens   int       `json:"max_tokens,omitempty"`
	Stream      bool      `json:"stream,omitempty"`
	TopP        float64   `json:"top_p,omitempty"`
	Stop        []string  `json:"stop,omitempty"`
	// TenantID 从 JWT 提取，不参与 JSON 序列化。
	TenantID string `json:"-"`
	// UserID 调用方用户 ID，从 JWT 提取。
	UserID string `json:"-"`
}

// Message 单条对话消息。
type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// ChatResponse 对话补全响应。
type ChatResponse struct {
	ID      string   `json:"id"`
	Object  string   `json:"object,omitempty"`
	Model   string   `json:"model"`
	Choices []Choice `json:"choices"`
	Usage   Usage    `json:"usage"`
}

// Choice 单个候选回复。
type Choice struct {
	Index        int     `json:"index"`
	Message      Message `json:"message"`
	FinishReason string  `json:"finish_reason,omitempty"`
}

// Usage Token 用量统计。
type Usage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}

// ============ 向量嵌入数据模型 ============

// EmbeddingRequest 文本向量化请求。
type EmbeddingRequest struct {
	Model string   `json:"model"`
	Input []string `json:"input"`
	// TenantID 从 JWT 提取。
	TenantID string `json:"-"`
	UserID   string `json:"-"`
}

// EmbeddingResponse 文本向量化响应。
type EmbeddingResponse struct {
	Object string          `json:"object"`
	Model  string          `json:"model"`
	Data   []EmbeddingData `json:"data"`
	Usage  Usage           `json:"usage"`
}

// EmbeddingData 单条文本的向量结果。
type EmbeddingData struct {
	Object    string    `json:"object"`
	Index     int       `json:"index"`
	Embedding []float64 `json:"embedding"`
}

// ============ 模型元信息 ============

// ModelInfo 描述一个可路由的模型。
type ModelInfo struct {
	ID      string `json:"id"`
	Object  string `json:"object"`
	OwnedBy string `json:"owned_by"`
}

// ============ 通用错误 ============

// Sentinel errors，供调用方做错误判定。
var (
	// ErrProviderNotFound 未找到对应 Provider。
	ErrProviderNotFound = errors.New("provider not found")
	// ErrModelNotFound 未找到对应模型。
	ErrModelNotFound = errors.New("model not found")
	// ErrInvalidRequest 请求参数非法。
	ErrInvalidRequest = errors.New("invalid request")
	// ErrUpstreamUnavailable 上游大模型 API 不可达。
	ErrUpstreamUnavailable = errors.New("upstream provider unavailable")
	// ErrSensitiveContent 命中敏感词。
	ErrSensitiveContent = errors.New("sensitive content detected")
)
