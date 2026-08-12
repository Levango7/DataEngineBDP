package provider

import (
	"context"
	"fmt"
)

// ============ 百度文心一言 适配器 ============
//
// 调用百度智能云千帆大模型平台 OpenAI 兼容接口。
// 默认 endpoint: https://qianfan.baidubce.com/v2
// 文心一言在千帆平台提供 OpenAI 兼容协议（/v2/chat/completions），
// 鉴权使用 Bearer API Key（即千帆 IAM 鉴权后的 access token 或 API Key）。

// WenxinProvider 百度文心一言适配器。
type WenxinProvider struct {
	cfg baseConfig
}

// WenxinConfig 文心 Provider 配置。
type WenxinConfig struct {
	Endpoint string // base URL，默认 https://qianfan.baidubce.com/v2
	APIKey   string // 千帆 API Key / access token
	Models   []ModelInfo
}

// NewWenxinProvider 构造文心一言适配器。
func NewWenxinProvider(cfg WenxinConfig) *WenxinProvider {
	endpoint := cfg.Endpoint
	if endpoint == "" {
		endpoint = "https://qianfan.baidubce.com/v2"
	}
	return &WenxinProvider{
		cfg: baseConfig{
			name:       "wenxin",
			endpoint:   endpoint,
			apiKey:     cfg.APIKey,
			models:     cfg.Models,
			authHeader: "Authorization",
			authPrefix: "Bearer ",
		},
	}
}

// Name 返回 Provider 标识。
func (p *WenxinProvider) Name() string { return p.cfg.name }

// ChatCompletion 调用文心一言对话补全（OpenAI 兼容协议）。
func (p *WenxinProvider) ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
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

// Embeddings 调用文心一言向量化（OpenAI 兼容协议）。
func (p *WenxinProvider) Embeddings(ctx context.Context, req EmbeddingRequest) (*EmbeddingResponse, error) {
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
func (p *WenxinProvider) Models(ctx context.Context) ([]ModelInfo, error) {
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
func (p *WenxinProvider) HealthCheck(ctx context.Context) error {
	return p.cfg.healthCheck(ctx)
}
