package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// ============ 通用 HTTP 客户端封装 ============
//
// 各厂商适配器共享的 HTTP 调用能力。OpenAI 协议是事实标准，
// 文心 / 通义 / 智谱均提供 OpenAI 兼容接口；各适配器仅差异在
// Endpoint、鉴权头格式与模型名映射。

// httpClient 默认 HTTP 客户端，带 30s 超时。
var httpClient = &http.Client{Timeout: 30 * time.Second}

// baseConfig 各适配器共享的基础配置。
type baseConfig struct {
	name     string
	endpoint string // 不含 /v1/chat/completions 等路径前缀的 base URL
	apiKey   string
	// models 该 Provider 支持的模型列表（用于 Models() 返回）。
	models []ModelInfo
	// authHeader 鉴权头名称，默认 "Authorization"。
	authHeader string
	// authPrefix 鉴权头前缀，默认 "Bearer "。
	authPrefix string
	// extraHeaders 额外自定义头（如智谱的 X-ZhipuAI-SDK 等）。
	extraHeaders map[string]string
}

// doJSON 发起 JSON 请求并解析 JSON 响应。
//
// path 相对路径，如 "/v1/chat/completions"。
// body 请求体，会被 JSON 序列化。
// out 响应体反序列化目标。
func (b *baseConfig) doJSON(ctx context.Context, method, path string, body, out any) error {
	url := b.endpoint + path

	var reqBody io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("marshal request body: %w", err)
		}
		reqBody = bytes.NewReader(raw)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, reqBody)
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	// 鉴权头
	header := b.authHeader
	if header == "" {
		header = "Authorization"
	}
	prefix := b.authPrefix
	if prefix == "" && header == "Authorization" {
		prefix = "Bearer "
	}
	req.Header.Set(header, prefix+b.apiKey)

	// 额外头
	for k, v := range b.extraHeaders {
		req.Header.Set(k, v)
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("%w: %s", ErrUpstreamUnavailable, err.Error())
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		raw, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("%w: upstream status %d, body=%s", ErrUpstreamUnavailable, resp.StatusCode, string(raw))
	}

	if out == nil {
		return nil
	}
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		return fmt.Errorf("decode response: %w", err)
	}
	return nil
}

// healthCheck 通用探活：向 /v1/models 发 GET 请求。
func (b *baseConfig) healthCheck(ctx context.Context) error {
	return b.doJSON(ctx, http.MethodGet, "/v1/models", nil, nil)
}

// estimateTokens 粗略估算 prompt token 数（4 字符 ≈ 1 token）。
// 真实场景应由各厂商返回的 Usage 为准；此处仅用于 Mock 与降级。
func estimateTokens(messages []Message) int {
	total := 0
	for _, m := range messages {
		total += len(m.Content) / 4
	}
	if total == 0 {
		total = 1
	}
	return total
}

// estimateEmbeddingTokens 估算 embedding 输入 token 数。
func estimateEmbeddingTokens(input []string) int {
	total := 0
	for _, s := range input {
		total += len(s) / 4
	}
	if total == 0 {
		total = 1
	}
	return total
}
