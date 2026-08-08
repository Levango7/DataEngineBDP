package token

import (
	"testing"

	"github.com/shuqing/bigdata/llm-gateway/internal/provider"
	"github.com/stretchr/testify/assert"
)

// TestCountText 测试文本 Token 计量。
func TestCountText(t *testing.T) {
	c := NewCounter()

	// 空字符串
	assert.Equal(t, 0, c.CountText(""))

	// 短文本（< 4 字符 → 1 token）
	assert.Equal(t, 1, c.CountText("hi"))

	// 4 字符 → 1 token
	assert.Equal(t, 1, c.CountText("test"))

	// 8 字符 → 2 token
	assert.Equal(t, 2, c.CountText("testtest"))

	// 中文（每个 rune 算 1 字符）
	assert.Equal(t, 1, c.CountText("你好"))       // 2 rune → 1 token
	assert.Equal(t, 1, c.CountText("你好世界"))     // 4 rune → 1 token
	assert.Equal(t, 2, c.CountText("你好世界你好世界")) // 8 rune → 2 token
}

// TestCountImage 测试图像 Token 计量。
func TestCountImage(t *testing.T) {
	c := NewCounter()

	// 低精度：固定 85 token
	assert.Equal(t, imageLowDetailTokens, c.CountImage(1024, 1024, "low"))

	// 高精度：1024x1024 → 2x2 tile → 85 + 4*170 = 765
	tokens := c.CountImage(1024, 1024, "high")
	assert.Equal(t, 765, tokens)

	// auto 等价 high
	assert.Equal(t, tokens, c.CountImage(1024, 1024, "auto"))

	// 小图像 512x512 → 1x1 tile → 85 + 170 = 255
	assert.Equal(t, 255, c.CountImage(512, 512, "high"))

	// 大图像 4096x4096 → 缩放到 2048x2048 → 4x4 tile → 85 + 16*170 = 2805
	bigTokens := c.CountImage(4096, 4096, "high")
	assert.Equal(t, 2805, bigTokens)
}

// TestCountAudio 测试语音 Token 计量。
func TestCountAudio(t *testing.T) {
	c := NewCounter()
	oneMinuteMs := int64(60 * 1000) // 1 分钟 = 60000 毫秒

	// 0 时长
	assert.Equal(t, 0, c.CountAudio(0))

	// 1 分钟 → 1500 token
	assert.Equal(t, 1500, c.CountAudio(oneMinuteMs))

	// 30 秒 → 750 token
	assert.Equal(t, 750, c.CountAudio(30*1000))

	// 2 分钟 → 3000 token
	assert.Equal(t, 3000, c.CountAudio(2*oneMinuteMs))
}

// TestCountVideo 测试视频 Token 计量。
func TestCountVideo(t *testing.T) {
	c := NewCounter()
	oneMinuteMs := int64(60 * 1000)

	// 0 时长
	assert.Equal(t, 0, c.CountVideo(0))

	// 1 分钟 → 6000 token
	assert.Equal(t, 6000, c.CountVideo(oneMinuteMs))

	// 10 秒 → 1000 token
	assert.Equal(t, 1000, c.CountVideo(10*1000))
}

// TestCountRequest 测试多模态请求 Token 计量。
func TestCountRequest(t *testing.T) {
	c := NewCounter()

	req := provider.MultimodalChatRequest{
		Model: "gpt-4",
		Messages: []provider.MultimodalMessage{
			{
				Role: "user",
				Parts: []provider.ContentPart{
					{Type: "text", Text: "请描述这张图片"},
					{Type: "image_url", ImageURL: &provider.ImageURLPart{URL: "https://example.com/img.png", Detail: "low"}},
					{Type: "input_audio", InputAudio: &provider.InputAudioPart{Data: "base64data", Format: "mp3"}},
				},
			},
		},
	}

	usage := c.CountRequest(req)

	// 应有文本 token
	assert.Greater(t, usage.PromptTokens, 0)
	// 应有图像 token（low = 85）
	assert.Equal(t, 85, usage.ImageTokens)
	// 应有语音 token（base64 短数据估算）
	assert.GreaterOrEqual(t, usage.AudioTokens, 0)
	// 总 token = 文本 + 图像 + 语音 + 视频
	assert.Equal(t, usage.PromptTokens+usage.ImageTokens+usage.AudioTokens+usage.VideoTokens, usage.TotalTokens)
}

// TestCountRequestMixed 测试混合多模态请求。
func TestCountRequestMixed(t *testing.T) {
	c := NewCounter()
	twoMinuteMs := int64(2 * 60 * 1000)

	req := provider.MultimodalChatRequest{
		Model: "gpt-4",
		Messages: []provider.MultimodalMessage{
			{
				Role:    "system",
				Content: "你是一个多模态助手",
			},
			{
				Role: "user",
				Parts: []provider.ContentPart{
					{Type: "text", Text: "分析这段视频"},
					{Type: "video_url", VideoURL: &provider.VideoURLPart{URL: "https://example.com/video.mp4", DurationMs: twoMinuteMs}},
				},
			},
		},
	}

	usage := c.CountRequest(req)
	assert.Greater(t, usage.PromptTokens, 0)
	assert.Equal(t, 12000, usage.VideoTokens) // 2 分钟 * 6000
	assert.Equal(t, 0, usage.ImageTokens)
	assert.Equal(t, 0, usage.AudioTokens)
}

// TestCustomCounter 测试自定义计量算法替换。
func TestCustomCounter(t *testing.T) {
	c := NewCounter()

	// 替换文本计量
	c.SetTextCounter(&mockTextCounter{})
	assert.Equal(t, 100, c.CountText("anything"))
}

// mockTextCounter 测试用 mock 文本计量器。
type mockTextCounter struct{}

func (m *mockTextCounter) Count(text string) int {
	return 100
}
