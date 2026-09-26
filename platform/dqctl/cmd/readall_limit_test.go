package cmd

import (
	"bytes"
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
	_, err = readLimitedBody(body, smallLimit) // 超限路径只断言错误，got 未使用
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrResponseBodyTooLarge)
}

// TestMaxResponseBody_Is10MB 验证 dqctl 的响应体上限为 10MB。
func TestMaxResponseBody_Is10MB(t *testing.T) {
	assert.Equal(t, 10<<20, maxResponseBody)
	assert.Equal(t, 10*1024*1024, maxResponseBody)
}
