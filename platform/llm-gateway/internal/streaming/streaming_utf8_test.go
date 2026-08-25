package streaming

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/token"
	"github.com/stretchr/testify/assert"
)

// TestSplitRuneChunks_PureChinese 纯中文串逐块重组等于原文，且每块都是合法 UTF-8。
func TestSplitRuneChunks_PureChinese(t *testing.T) {
	content := strings.Repeat("数据引擎流式响应中文字符串", 30)
	for _, budget := range []int{2, 4, 8, 16} {
		chunks := splitRuneChunks(content, budget)
		assert.NotEmpty(t, chunks, "budget=%d 应产生至少一个分块", budget)
		var sb strings.Builder
		for _, ch := range chunks {
			assert.True(t, utf8.ValidString(ch), "budget=%d 分块 %q 必须是合法 UTF-8", budget, ch)
			sb.WriteString(ch)
		}
		assert.Equal(t, content, sb.String(), "budget=%d 逐块重组必须还原原文", budget)
	}

	longChunks := splitRuneChunks(content, 16)
	multi := false
	for _, ch := range longChunks {
		if len(ch) < len(content) {
			multi = true
		}
	}
	assert.True(t, multi, "长内容应被切分为多个分块")
}

// TestSplitRuneChunks_MixedContent 中英混合 + emoji（4 字节 rune）不产生非法分块。
func TestSplitRuneChunks_MixedContent(t *testing.T) {
	content := "你好hello世界world🌍数据🎉engine🚀引擎abc中文xyz😀"
	budget := 8
	chunks := splitRuneChunks(content, budget)
	var sb strings.Builder
	for _, ch := range chunks {
		assert.True(t, utf8.ValidString(ch), "分块 %q 必须是合法 UTF-8", ch)
		assert.LessOrEqual(t, utf8.RuneCountInString(ch), budget, "单 rune 超预算外每块字节数不应超预算过多")
		sb.WriteString(ch)
	}
	assert.Equal(t, content, sb.String())

	emojiOnly := "🌍🎉🚀😀🌍🎉🚀😀"
	emojiChunks := splitRuneChunks(emojiOnly, 4)
	var rebuilt strings.Builder
	for _, ch := range emojiChunks {
		assert.True(t, utf8.ValidString(ch), "emoji 分块 %q 必须是合法 UTF-8", ch)
		rebuilt.WriteString(ch)
	}
	assert.Equal(t, emojiOnly, rebuilt.String())
}

// TestSplitRuneChunks_EdgeCases 空串与单字符边界。
func TestSplitRuneChunks_EdgeCases(t *testing.T) {
	assert.Nil(t, splitRuneChunks("", 2), "空串应返回空分块")
	assert.Nil(t, splitRuneChunks("", 16))

	assert.Equal(t, []string{"a"}, splitRuneChunks("a", 16))
	assert.Equal(t, []string{"中"}, splitRuneChunks("中", 16))
	assert.Equal(t, []string{"🌍"}, splitRuneChunks("🌍", 2))

	got := splitRuneChunks("ab中c", 2)
	assert.Equal(t, []string{"ab", "中", "c"}, got)

	var sb strings.Builder
	for _, ch := range got {
		assert.True(t, utf8.ValidString(ch))
		sb.WriteString(ch)
	}
	assert.Equal(t, "ab中c", sb.String())
}

// TestStreamChatChineseSSE 端到端验证中文内容经 SSE 推送后逐块重组无损。
func TestStreamChatChineseSSE(t *testing.T) {
	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodPost, "/v1/chat/completions", nil)

	const content = "你好，世界！Hello 🌍 数据引擎流式回复测试。"
	resp := &provider.MultimodalChatResponse{
		ID:     "chatcmpl-test",
		Object: "chat.completion",
		Model:  "mock",
		Choices: []provider.MultimodalChoice{
			{
				Index:        0,
				Message:      provider.MultimodalMessage{Role: "assistant", Content: content},
				FinishReason: "stop",
			},
		},
		Usage: provider.MultimodalUsage{PromptTokens: 1, CompletionTokens: 10, TotalTokens: 11},
	}

	s := NewSSEStreamer(token.NewCounter())
	err := s.StreamChat(c, provider.MultimodalChatRequest{}, func(_ context.Context, _ provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error) {
		return resp, nil
	})
	assert.NoError(t, err)

	body := w.Body.String()
	var rebuilt strings.Builder
	contentChunks := 0
	lastFinish := ""
	for _, line := range strings.Split(body, "\n") {
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		payload := strings.TrimPrefix(line, "data: ")
		if payload == "[DONE]" {
			continue
		}
		var chunk SSEChunk
		if err := json.Unmarshal([]byte(payload), &chunk); err != nil {
			t.Fatalf("chunk 反序列化失败: %v\n%s", err, payload)
		}
		if raw, ok := chunk.Choices[0].Delta["content"]; ok {
			text := raw.(string)
			assert.True(t, utf8.ValidString(text), "SSE 内容分块 %q 必须是合法 UTF-8", text)
			rebuilt.WriteString(text)
			contentChunks++
			if chunk.Choices[0].FinishReason != "" {
				lastFinish = chunk.Choices[0].FinishReason
			}
		}
	}

	assert.Greater(t, contentChunks, 1, "中文内容应产生多个内容分块")
	assert.Equal(t, content, rebuilt.String(), "SSE 分块重组必须还原原文")
	assert.Equal(t, "stop", lastFinish)
}
