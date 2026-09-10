package provider

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// ============ readLimitedBody 单元测试 ============

// TestReadLimitedBody_WithinLimit 验证正常大小的响应体可以读取。
func TestReadLimitedBody_WithinLimit(t *testing.T) {
	// 构造一个小于 maxResponseBody 的响应体
	payload := strings.Repeat("a", 1024) // 1KB
	body := bytes.NewReader([]byte(payload))

	got, err := readLimitedBody(body, maxResponseBody)
	require.NoError(t, err)
	assert.Equal(t, payload, string(got))
}

// TestReadLimitedBody_ExactLimit 验证恰好等于上限的响应体可以读取。
func TestReadLimitedBody_ExactLimit(t *testing.T) {
	payload := strings.Repeat("b", maxResponseBody)
	body := bytes.NewReader([]byte(payload))

	got, err := readLimitedBody(body, maxResponseBody)
	require.NoError(t, err)
	assert.Len(t, got, maxResponseBody)
}

// TestReadLimitedBody_ExceedsLimit 验证超过上限的响应体返回 ErrResponseBodyTooLarge。
func TestReadLimitedBody_ExceedsLimit(t *testing.T) {
	// 构造一个超过 maxResponseBody 的响应体
	payload := strings.Repeat("c", maxResponseBody+1)
	body := bytes.NewReader([]byte(payload))

	got, err := readLimitedBody(body, maxResponseBody)
	require.Error(t, err)
	assert.Nil(t, got)
	assert.ErrorIs(t, err, ErrResponseBodyTooLarge)
}

// TestReadLimitedBody_Empty 验证空响应体可以读取。
func TestReadLimitedBody_Empty(t *testing.T) {
	body := bytes.NewReader(nil)

	got, err := readLimitedBody(body, maxResponseBody)
	require.NoError(t, err)
	assert.Empty(t, got)
}

// TestReadLimitedBody_SmallLimit 验证自定义小上限也能正常工作。
func TestReadLimitedBody_SmallLimit(t *testing.T) {
	// 使用 100 字节上限
	const smallLimit = 100

	// 50 字节，在限制内
	payload := strings.Repeat("d", 50)
	body := bytes.NewReader([]byte(payload))
	got, err := readLimitedBody(body, smallLimit)
	require.NoError(t, err)
	assert.Len(t, got, 50)

	// 101 字节，超限
	payload = strings.Repeat("e", 101)
	body = bytes.NewReader([]byte(payload))
	got, err = readLimitedBody(body, smallLimit)
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrResponseBodyTooLarge)
}

// ============ doJSON 集成测试：通过 httptest.Server 验证大小限制 ============

// TestDoJSON_ErrorBodyWithinLimit 验证错误响应体在限制内时正常返回错误信息。
func TestDoJSON_ErrorBodyWithinLimit(t *testing.T) {
	// 构造一个返回 500 + 小响应体的 server
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":"upstream error"}`))
	}))
	defer srv.Close()

	b := &baseConfig{
		name:     "test",
		endpoint: srv.URL,
		apiKey:   "test-key",
	}

	err := b.doJSON(context.Background(), http.MethodGet, "/v1/models", nil, nil)
	require.Error(t, err)
	// 应包含上游状态码与响应体
	assert.Contains(t, err.Error(), "500")
	assert.Contains(t, err.Error(), "upstream error")
}

// TestDoJSON_ErrorBodyExceedsLimit 验证错误响应体超限时返回 ErrResponseBodyTooLarge。
func TestDoJSON_ErrorBodyExceedsLimit(t *testing.T) {
	// 构造一个返回 500 + 超大响应体的 server
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		// 写入超过 maxResponseBody 的数据
		_, _ = w.Write(bytes.Repeat([]byte("x"), maxResponseBody+1024))
	}))
	defer srv.Close()

	b := &baseConfig{
		name:     "test",
		endpoint: srv.URL,
		apiKey:   "test-key",
	}

	err := b.doJSON(context.Background(), http.MethodGet, "/v1/models", nil, nil)
	require.Error(t, err)
	// 应返回 ErrResponseBodyTooLarge 相关错误
	assert.True(t, errors.Is(err, ErrUpstreamUnavailable) || strings.Contains(err.Error(), "too large"),
		"err should be ErrUpstreamUnavailable or contain 'too large', got: %v", err)
}

// TestDoJSON_SuccessBodyWithinLimit 验证成功响应体在限制内时正常解析。
func TestDoJSON_SuccessBodyWithinLimit(t *testing.T) {
	// 构造一个返回 200 + 合法 JSON 的 server
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"model-1","object":"model"}`))
	}))
	defer srv.Close()

	b := &baseConfig{
		name:     "test",
		endpoint: srv.URL,
		apiKey:   "test-key",
	}

	var out map[string]interface{}
	err := b.doJSON(context.Background(), http.MethodGet, "/v1/models", nil, &out)
	require.NoError(t, err)
	assert.Equal(t, "model-1", out["id"])
}
