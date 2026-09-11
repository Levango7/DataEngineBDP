package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
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

// maxResponseBody 是读取上游 HTTP 响应体的最大字节数（50MB）。
// LLM 响应可能较大（长文本补全 / 多模态），故上限设为 50MB。
// 超过此上限的响应体将返回 ErrResponseBodyTooLarge，防止内存耗尽 DoS。
const maxResponseBody = 50 << 20

// ErrResponseBodyTooLarge 在响应体超过 maxResponseBody 时返回。
var ErrResponseBodyTooLarge = errors.New("response body too large")

// readLimitedBody 读取 r 的内容，最多 maxBytes 字节。
// 若实际长度超过 maxBytes 则返回 ErrResponseBodyTooLarge。
// 该函数用于替代裸 io.ReadAll，避免无限制读取导致的内存耗尽 DoS。
func readLimitedBody(r io.Reader, maxBytes int) ([]byte, error) {
	// 多读 1 字节以判断是否超限：若读到 maxBytes+1 字节则说明超限。
	raw, err := io.ReadAll(io.LimitReader(r, int64(maxBytes)+1))
	if err != nil {
		return nil, err
	}
	if len(raw) > maxBytes {
		return nil, fmt.Errorf("%w: limit=%d bytes", ErrResponseBodyTooLarge, maxBytes)
	}
	return raw, nil
}

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
		raw, err := readLimitedBody(resp.Body, maxResponseBody)
		if err != nil {
			return fmt.Errorf("%w: upstream status %d, body read failed: %v", ErrUpstreamUnavailable, resp.StatusCode, err)
		}
		return fmt.Errorf("%w: upstream status %d, body=%s", ErrUpstreamUnavailable, resp.StatusCode, string(raw))
	}

	if out == nil {
		return nil
	}
	// 2xx 成功响应也限制读取大小，防超大响应导致内存耗尽 DoS
	raw, err := readLimitedBody(resp.Body, maxResponseBody)
	if err != nil {
		return fmt.Errorf("read response body: %w", err)
	}
	if err := json.Unmarshal(raw, out); err != nil {
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
