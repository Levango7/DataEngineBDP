package provider

import (
	"context"
	"fmt"
)

// ============ 智谱 GLM 适配器 ============
//
// 调用智谱 AI 开放平台 OpenAI 兼容接口。
// 默认 endpoint: https://open.bigmodel.cn/api/paas/v4
// 智谱 GLM 系列提供 OpenAI 兼容协议（/v4/chat/completions），
// 鉴权使用 Bearer API Key（即智谱 API Key）。

// ZhipuProvider 智谱 GLM 适配器。
type ZhipuProvider struct {
	cfg baseConfig
}

// ZhipuConfig 智谱 Provider 配置。
type ZhipuConfig struct {
	Endpoint string // base URL
	APIKey   string // 智谱 API Key
	Models   []ModelInfo
}

// NewZhipuProvider 构造智谱 GLM 适配器。
func NewZhipuProvider(cfg ZhipuConfig) *ZhipuProvider {
	endpoint := cfg.Endpoint
	if endpoint == "" {
		endpoint = "https://open.bigmodel.cn/api/paas/v4"
	}
	return &ZhipuProvider{
		cfg: baseConfig{
			name:       "zhipu",
			endpoint:   endpoint,
			apiKey:     cfg.APIKey,
			models:     cfg.Models,
			authHeader: "Authorization",
			authPrefix: "Bearer ",
		},
	}
}

// Name 返回 Provider 标识。
func (p *ZhipuProvider) Name() string { return p.cfg.name }

// ChatCompletion 调用智谱 GLM 对话补全（OpenAI 兼容协议）。
func (p *ZhipuProvider) ChatCompletion(ctx context.Context, req ChatRequest) (*ChatResponse, error) {
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

// Embeddings 调用智谱 GLM 向量化（OpenAI 兼容协议）。
func (p *ZhipuProvider) Embeddings(ctx context.Context, req EmbeddingRequest) (*EmbeddingResponse, error) {
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
func (p *ZhipuProvider) Models(ctx context.Context) ([]ModelInfo, error) {
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
func (p *ZhipuProvider) HealthCheck(ctx context.Context) error {
	return p.cfg.healthCheck(ctx)
}
