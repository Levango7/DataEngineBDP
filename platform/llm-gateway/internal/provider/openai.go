package provider

import (
	"context"
	"fmt"
)

// ============ OpenAI GPT 适配器 ============
//
// 调用 OpenAI 官方 API（或兼容 OpenAI 协议的代理网关）。
// 默认 endpoint: https://api.openai.com

// OpenAIProvider OpenAI GPT 系列适配器。
type OpenAIProvider struct {
	cfg baseConfig
}

// OpenAIConfig OpenAI Provider 配置。
type OpenAIConfig struct {
	Endpoint string // base URL，默认 https://api.openai.com
	APIKey   string // OpenAI API Key
	Models   []ModelInfo
}

// NewOpenAIProvider 构造 OpenAI 适配器。
func NewOpenAIProvider(cfg OpenAIConfig) *OpenAIProvider {
	endpoint := cfg.Endpoint
	if endpoint == "" {
		endpoint = "https://api.openai.com"
	}
	return &OpenAIProvider{
		cfg: baseConfig{
			name:       "openai",
			endpoint:   endpoint,
			apiKey:     cfg.APIKey,
			models:     cfg.Models,
			authHeader: "Authorization",
			authPrefix: "Bearer ",
		},
	}
}

// Name 返回 Provider 标识。
func (p *OpenAIProvider) Name() string { return p.cfg.name }

// ChatCompletion 调用 POST /v1/chat/completions。
func (p *OpenAIProvider) ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
	if req.Model == "" {
		return nil, fmt.Errorf("%w: model is required", ErrInvalidRequest)
	}
	if len(req.Messages) == 0 {
		return nil, fmt.Errorf("%w: messages must not be empty", ErrInvalidRequest)
	}
	var resp ChatResponse
	if err := p.cfg.doJSON(ctx, "POST", "/v1/chat/completions", req, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// Embeddings 调用 POST /v1/embeddings。
func (p *OpenAIProvider) Embeddings(ctx context.Context, req EmbeddingRequest) (*EmbeddingResponse, error) {
	if req.Model == "" {
		return nil, fmt.Errorf("%w: model is required", ErrInvalidRequest)
	}
	if len(req.Input) == 0 {
		return nil, fmt.Errorf("%w: input must not be empty", ErrInvalidRequest)
	}
	var resp EmbeddingResponse
	if err := p.cfg.doJSON(ctx, "POST", "/v1/embeddings", req, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// Models 返回该 Provider 支持的模型列表。
func (p *OpenAIProvider) Models(ctx context.Context) ([]ModelInfo, error) {
	if len(p.cfg.models) > 0 {
		return p.cfg.models, nil
	}
	// 兜底：调用 /v1/models
	var resp struct {
		Data []ModelInfo `json:"data"`
	}
	if err := p.cfg.doJSON(ctx, "GET", "/v1/models", nil, &resp); err != nil {
		return nil, err
	}
	return resp.Data, nil
}

// HealthCheck 探活。
func (p *OpenAIProvider) HealthCheck(ctx context.Context) error {
	return p.cfg.healthCheck(ctx)
}
