package cmd

import (
	"errors"
	"fmt"
	"io"
)

// maxResponseBody 是读取 HTTP 响应体的最大字节数（10MB）。
// 适用于 dqctl 各子命令（status/query/apply）读取后端 API 响应。
// 超过此上限的响应体将返回 ErrResponseBodyTooLarge，防止内存耗尽 DoS。
const maxResponseBody = 10 << 20

// ErrResponseBodyTooLarge 在响应体超过限制时返回。
var ErrResponseBodyTooLarge = errors.New("response body too large")

// readLimitedBody 读取 r 的内容，最多 maxBytes 字节。
// 若实际长度超过 maxBytes 则返回 ErrResponseBodyTooLarge。
// 该函数用于替代裸 io.ReadAll，避免无限制读取导致的内存耗尽 DoS。
//
// 用法：
//
//	body, err := readLimitedBody(resp.Body, maxResponseBody)
//	if err != nil {
//	    // 处理错误（含超限）
//	}
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
