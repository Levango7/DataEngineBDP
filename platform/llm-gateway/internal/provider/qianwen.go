package provider

import (
	"context"
	"fmt"
)

// ============ 阿里通义千问 适配器 ============
//
// 调用阿里云百炼大模型平台 OpenAI 兼容接口。
// 默认 endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1
// 通义千问在百炼平台提供 OpenAI 兼容协议，
// 鉴权使用 Bearer API Key（即百炼 API-Key）。

// QianwenProvider 阿里通义千问适配器。
type QianwenProvider struct {
	cfg baseConfig
}

// QianwenConfig 通义 Provider 配置。
type QianwenConfig struct {
	Endpoint string // base URL
	APIKey   string // 百炼 API Key
	Models   []ModelInfo
}

// NewQianwenProvider 构造通义千问适配器。
func NewQianwenProvider(cfg QianwenConfig) *QianwenProvider {
	endpoint := cfg.Endpoint
	if endpoint == "" {
		endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1"
	}
	return &QianwenProvider{
		cfg: baseConfig{
			name:       "qianwen",
			endpoint:   endpoint,
			apiKey:     cfg.APIKey,
			models:     cfg.Models,
			authHeader: "Authorization",
			authPrefix: "Bearer ",
		},
	}
}

// Name 返回 Provider 标识。
func (p *QianwenProvider) Name() string { return p.cfg.name }

// ChatCompletion 调用通义千问对话补全（OpenAI 兼容协议）。
func (p *QianwenProvider) ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
	if req.Model == "" {
		return nil, fmt.Errorf("%w: model is required", ErrInvalidRequest)
	}
	if len(req.Messages) == 0 {
		return nil, fmt.Errorf("%w: messages must not be empty", ErrInvalidRequest)
	}
	var resp ChatResponse
	if err := p.cfg.doJSON(ctx, "POST", "/chat/completions", req, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// Embeddings 调用通义千问向量化（OpenAI 兼容协议）。
func (p *QianwenProvider) Embeddings(ctx context.Context, req EmbeddingRequest) (*EmbeddingResponse, error) {
	if req.Model == "" {
		return nil, fmt.Errorf("%w: model is required", ErrInvalidRequest)
	}
	if len(req.Input) == 0 {
		return nil, fmt.Errorf("%w: input must not be empty", ErrInvalidRequest)
	}
	var resp EmbeddingResponse
	if err := p.cfg.doJSON(ctx, "POST", "/embeddings", req, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// Models 返回支持的模型列表。
func (p *QianwenProvider) Models(ctx context.Context) ([]ModelInfo, error) {
	if len(p.cfg.models) > 0 {
		return p.cfg.models, nil
	}
	var resp struct {
		Data []ModelInfo `json:"data"`
	}
	if err := p.cfg.doJSON(ctx, "GET", "/models", nil, &resp); err != nil {
		return nil, err
	}
	return resp.Data, nil
}

// HealthCheck 探活。
func (p *QianwenProvider) HealthCheck(ctx context.Context) error {
	return p.cfg.healthCheck(ctx)
}
