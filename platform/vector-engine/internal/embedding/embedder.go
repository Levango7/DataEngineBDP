// Package embedding 提供文本向量化（embedding）抽象。
//
// 两种实现：
//   - HTTPEmbedder：调用外部 OpenAI 兼容 embedding API（生产语义检索）
//   - HashEmbedder：确定性 n-gram 词元特征向量（无外部服务时的可运行降级）
//
// 通过环境变量选择：
//   - VECTOR_EMBEDDING_API      embedding 服务端点（如 https://api.openai.com/v1/embeddings）
//   - VECTOR_EMBEDDING_API_KEY  API Key（可选，多数服务需要 Bearer 鉴权）
//   - VECTOR_EMBEDDING_MODEL    模型名，默认 text-embedding-3-small
//
// 未配置 VECTOR_EMBEDDING_API 时自动降级为 HashEmbedder，并在响应中标注
// "mode": "hash-fallback"，调用方应据此提示"语义检索未启用"。
package embedding

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// Embedder 文本向量化接口。
type Embedder interface {
	// Mode 返回实现模式标识：semantic（真实 embedding）或 hash-fallback（降级）。
	Mode() string
	// Embed 将文本列表转为等长向量列表。
	Embed(ctx context.Context, texts []string) ([][]float32, error)
}

// New 根据环境变量创建 Embedder：
// VECTOR_EMBEDDING_API 已配置 → HTTPEmbedder（semantic）；
// 否则 → HashEmbedder（hash-fallback）。
func New() Embedder {
	api := strings.TrimSpace(os.Getenv("VECTOR_EMBEDDING_API"))
	if api == "" {
		return &HashEmbedder{}
	}
	return &HTTPEmbedder{
		API:   api,
		Key:   strings.TrimSpace(os.Getenv("VECTOR_EMBEDDING_API_KEY")),
		Model: strings.TrimSpace(os.Getenv("VECTOR_EMBEDDING_MODEL")),
	}
}

// ---------- HTTPEmbedder（真实语义检索） ----------

// HTTPEmbedder 调用 OpenAI 兼容 /embeddings 端点。
type HTTPEmbedder struct {
	API   string
	Key   string
	Model string
}

// Mode 实现 Embedder 接口。
func (e *HTTPEmbedder) Mode() string { return "semantic" }

type openAIEmbeddingRequest struct {
	Input []string `json:"input"`
	Model string   `json:"model"`
}

type openAIEmbeddingResponse struct {
	Data []struct {
		Embedding []float32 `json:"embedding"`
	} `json:"data"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// Embed 实现 Embedder 接口。
func (e *HTTPEmbedder) Embed(ctx context.Context, texts []string) ([][]float32, error) {
	model := e.Model
	if model == "" {
		model = "text-embedding-3-small"
	}
	body, err := json.Marshal(openAIEmbeddingRequest{Input: texts, Model: model})
	if err != nil {
		return nil, fmt.Errorf("序列化 embedding 请求失败: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, e.API, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("构造 embedding 请求失败: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if e.Key != "" {
		req.Header.Set("Authorization", "Bearer "+e.Key)
	}

	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("调用 embedding 服务失败: %w", err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20)) // 4MB 上限
	if err != nil {
		return nil, fmt.Errorf("读取 embedding 响应失败: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("embedding 服务返回 %d: %s", resp.StatusCode, truncate(string(raw), 200))
	}
	var parsed openAIEmbeddingResponse
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return nil, fmt.Errorf("解析 embedding 响应失败: %w", err)
	}
	if parsed.Error != nil {
		return nil, fmt.Errorf("embedding 服务错误: %s", parsed.Error.Message)
	}
	if len(parsed.Data) != len(texts) {
		return nil, fmt.Errorf("embedding 返回数量不匹配: 期望 %d 实际 %d", len(texts), len(parsed.Data))
	}
	vectors := make([][]float32, 0, len(parsed.Data))
	for _, d := range parsed.Data {
		vectors = append(vectors, d.Embedding)
	}
	return vectors, nil
}

// ---------- HashEmbedder（确定性降级） ----------

// HashEmbedder 基于 unigram+bigram 字符词元计数生成固定 256 维特征向量。
// 相比旧实现（字符码求和 8 维哈希），对共享词元的文本有更好的相似度区分度，
// 但仍无语义能力——仅保证在无外部 embedding 服务时可运行且结果稳定可复现。
type HashEmbedder struct{}

// FeatureDim 降级特征维度。
const FeatureDim = 256

// Mode 实现 Embedder 接口。
func (e *HashEmbedder) Mode() string { return "hash-fallback" }

// Embed 实现 Embedder 接口。
func (e *HashEmbedder) Embed(_ context.Context, texts []string) ([][]float32, error) {
	vectors := make([][]float32, 0, len(texts))
	for _, t := range texts {
		vectors = append(vectors, textToNgramVector(t, FeatureDim))
	}
	return vectors, nil
}

// textToNgramVector 文本 → 归一化 n-gram 计数向量。
func textToNgramVector(text string, dim int) []float32 {
	vec := make([]float32, dim)
	runes := []rune(strings.ToLower(text))
	if len(runes) == 0 {
		return vec
	}
	// unigram
	for _, r := range runes {
		vec[hashIndex(int(r), dim)]++
	}
	// bigram（相邻字符对）
	for i := 0; i+1 < len(runes); i++ {
		pair := int(runes[i])*31 + int(runes[i+1])
		vec[hashIndex(pair, dim)]++
	}
	// L2 归一化（零向量保持零向量）
	var norm float64
	for _, v := range vec {
		norm += float64(v) * float64(v)
	}
	if norm > 0 {
		sqrt := float32(sqrtFloat64(norm))
		for i := range vec {
			vec[i] /= sqrt
		}
	}
	return vec
}

func hashIndex(v, dim int) int {
	h := fnv.New32a()
	_, _ = h.Write([]byte{byte(v), byte(v >> 8), byte(v >> 16), byte(v >> 24)})
	return int(h.Sum32() % uint32(dim))
}

func sqrtFloat64(x float64) float64 {
	// 牛顿迭代（避免依赖 math 包以外的平方根；Go 标准库 math 可用但保持零依赖语义）
	if x <= 0 {
		return 0
	}
	z := x
	for i := 0; i < 20; i++ {
		z = (z + x/z) / 2
	}
	return z
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
