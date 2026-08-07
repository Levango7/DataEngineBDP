package token

// Package token 实现多模态 Token 计量。
//
// 支持四种模态独立计量并折算为统一 Token 单位：
//   - 文本 Token：按字符数估算（4 字符 ≈ 1 token），生产环境应替换为 tiktoken 等精确分词器
//   - 图像 Token：按分辨率折算（OpenAI 规则：低精度 85 token，高精度按 (宽/512)*(高/512)*170+85）
//   - 语音 Token：按时长折算（每分钟 ≈ 1500 token，对应 Whisper 计费规则）
//   - 视频 Token：按时长折算（每分钟 ≈ 6000 token，按帧采样估算）
//
// 设计原则：
//   - 各模态计量算法可独立替换（接口抽象）
//   - 计量结果可序列化为 OpenAI Usage 兼容结构
//   - 线程安全，供网关并发调用


import (
	"math"
	"strings"
	"sync"
	"time"

	"github.com/shuqing/bigdata/llm-gateway/internal/provider"
)

// ============ 计量常量 ============

const (
	// 文本：4 字符 ≈ 1 token（粗略估算，与 mock provider 一致）。
	textCharsPerToken = 4

	// 图像：低精度固定 85 token（OpenAI gpt-4o 规则）。
	imageLowDetailTokens = 85
	// 图像：高精度基础 token。
	imageHighDetailBaseTokens = 85
	// 图像：高精度 tile 大小（OpenAI 按 512x512 tile 切分）。
	imageTileSize = 512
	// 图像：每个 tile 折算 token 数。
	imageTokensPerTile = 170

	// 图像：默认分辨率（当 URL 为 data URI 且未指定尺寸时）。
	defaultImageWidth  = 1024
	defaultImageHeight = 1024

	// 语音：每分钟折算 token 数（参考 Whisper 计费）。
	audioTokensPerMinute = 1500

	// 视频：每分钟折算 token 数（按帧采样估算）。
	videoTokensPerMinute = 6000

	// base64 编码后字节流：每字节 ≈ 1.33 字符（base64 膨胀因子）。
	base64Expansion = 1.33
)

// ============ 计量器 ============

// Counter 多模态 Token 计量器。
//
// 线程安全。各模态计量算法通过 ModalityCounter 接口抽象，
// 可独立替换（如生产环境替换文本计量为 tiktoken）。
type Counter struct {
	mu sync.RWMutex
	// textCounter 文本 Token 计量算法。
	textCounter TextCounter
	// imageCounter 图像 Token 计量算法。
	imageCounter ImageCounter
	// audioCounter 语音 Token 计量算法。
	audioCounter AudioCounter
	// videoCounter 视频 Token 计量算法。
	videoCounter VideoCounter
}

// NewCounter 构造默认计量器。
func NewCounter() *Counter {
	return &Counter{
		textCounter:  &defaultTextCounter{},
		imageCounter: &defaultImageCounter{},
		audioCounter: &defaultAudioCounter{},
		videoCounter: &defaultVideoCounter{},
	}
}

// SetTextCounter 替换文本计量算法（便于使用 tiktoken 等精确分词器）。
func (c *Counter) SetTextCounter(tc TextCounter) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.textCounter = tc
}

// SetImageCounter 替换图像计量算法。
func (c *Counter) SetImageCounter(ic ImageCounter) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.imageCounter = ic
}

// SetAudioCounter 替换语音计量算法。
func (c *Counter) SetAudioCounter(ac AudioCounter) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.audioCounter = ac
}

// SetVideoCounter 替换视频计量算法。
func (c *Counter) SetVideoCounter(vc VideoCounter) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.videoCounter = vc
}

// ============ 计量接口 ============

// TextCounter 文本 Token 计量接口。
type TextCounter interface {
	Count(text string) int
}

// ImageCounter 图像 Token 计量接口。
//
// width/height 为图像像素尺寸；detail 为精度档位（low/high/auto）。
type ImageCounter interface {
	Count(width, height int, detail string) int
}

// AudioCounter 语音 Token 计量接口。
//
// durationMs 为语音时长（毫秒）。
type AudioCounter interface {
	Count(durationMs int64) int
}

// VideoCounter 视频 Token 计量接口。
//
// durationMs 为视频时长（毫秒）。
type VideoCounter interface {
	Count(durationMs int64) int
}

// ============ 默认实现 ============

// defaultTextCounter 默认文本计量：4 字符 ≈ 1 token。
type defaultTextCounter struct{}

func (t *defaultTextCounter) Count(text string) int {
	if text == "" {
		return 0
	}
	// 按 UTF-8 rune 计数，避免多字节字符被高估。
	n := len([]rune(text)) / textCharsPerToken
	if n == 0 {
		n = 1
	}
	return n
}

// defaultImageCounter 默认图像计量：按 OpenAI gpt-4o 规则折算。
type defaultImageCounter struct{}

func (i *defaultImageCounter) Count(width, height int, detail string) int {
	if width <= 0 || height <= 0 {
		width = defaultImageWidth
		height = defaultImageHeight
	}
	// 低精度：固定 85 token。
	if detail == "low" {
		return imageLowDetailTokens
	}
	// 高精度 / auto：按 tile 数折算。
	// OpenAI 规则：先缩放到最长边 ≤ 2048，再按 512x512 tile 切分。
	w, h := width, height
	if w > 2048 || h > 2048 {
		scale := 2048.0 / math.Max(float64(w), float64(h))
		w = int(float64(w) * scale)
		h = int(float64(h) * scale)
	}
	tilesW := (w + imageTileSize - 1) / imageTileSize
	tilesH := (h + imageTileSize - 1) / imageTileSize
	if tilesW < 1 {
		tilesW = 1
	}
	if tilesH < 1 {
		tilesH = 1
	}
	return imageHighDetailBaseTokens + tilesW*tilesH*imageTokensPerTile
}

// defaultAudioCounter 默认语音计量：每分钟 1500 token。
type defaultAudioCounter struct{}

func (a *defaultAudioCounter) Count(durationMs int64) int {
	if durationMs <= 0 {
		return 0
	}
	minutes := float64(durationMs) / float64(time.Minute.Milliseconds())
	return int(math.Ceil(minutes * audioTokensPerMinute))
}

// defaultVideoCounter 默认视频计量：每分钟 6000 token。
type defaultVideoCounter struct{}

func (v *defaultVideoCounter) Count(durationMs int64) int {
	if durationMs <= 0 {
		return 0
	}
	minutes := float64(durationMs) / float64(time.Minute.Milliseconds())
	return int(math.Ceil(minutes * videoTokensPerMinute))
}

// ============ 统一计量入口 ============

// CountRequest 计量多模态请求的输入 Token。
//
// 遍历所有消息的所有片段，按模态分别计量并求和。
// 返回各模态 Token 数与总和。
func (c *Counter) CountRequest(req provider.MultimodalChatRequest) provider.MultimodalUsage {
	c.mu.RLock()
	defer c.mu.RUnlock()

	var usage provider.MultimodalUsage
	for _, m := range req.Messages {
		// 纯文本 Content 字段
		if m.Content != "" {
			usage.PromptTokens += c.textCounter.Count(m.Content)
		}
		// 多模态片段
		for _, p := range m.Parts {
			switch p.Type {
			case "text":
				usage.PromptTokens += c.textCounter.Count(p.Text)
			case "image_url":
				if p.ImageURL != nil {
					w, h := parseImageSizeFromURI(p.ImageURL.URL)
					usage.ImageTokens += c.imageCounter.Count(w, h, p.ImageURL.Detail)
				}
			case "input_audio":
				if p.InputAudio != nil {
					// base64 字节流估算时长：每分钟约 1MB（mp3 ~16kbps）。
					durationMs := estimateAudioDurationFromBase64(p.InputAudio.Data, p.InputAudio.Format)
					usage.AudioTokens += c.audioCounter.Count(durationMs)
				}
			case "video_url":
				if p.VideoURL != nil {
					durationMs := p.VideoURL.DurationMs
					if durationMs == 0 {
						durationMs = estimateVideoDurationFromURI(p.VideoURL.URL)
					}
					usage.VideoTokens += c.videoCounter.Count(durationMs)
				}
			}
		}
	}
	usage.TotalTokens = usage.PromptTokens + usage.ImageTokens + usage.AudioTokens + usage.VideoTokens
	return usage
}

// CountResponse 计量多模态响应的输出 Token。
//
// 在输入 usage 基础上累加输出文本/图像/语音 Token。
func (c *Counter) CountResponse(resp *provider.MultimodalChatResponse) provider.MultimodalUsage {
	c.mu.RLock()
	defer c.mu.RUnlock()

	usage := resp.Usage
	for _, ch := range resp.Choices {
		// 输出文本
		if ch.Message.Content != "" {
			usage.CompletionTokens += c.textCounter.Count(ch.Message.Content)
		}
		for _, p := range ch.Message.Parts {
			switch p.Type {
			case "text":
				usage.CompletionTokens += c.textCounter.Count(p.Text)
			case "output_image":
				if p.OutputImage != nil {
					w, h := parseImageSizeFromString(p.OutputImage.Size)
					usage.ImageTokens += c.imageCounter.Count(w, h, "high")
				}
			case "output_audio":
				if p.OutputAudio != nil {
					durationMs := estimateAudioDurationFromBase64(p.OutputAudio.Data, p.OutputAudio.Format)
					usage.AudioTokens += c.audioCounter.Count(durationMs)
				}
			}
		}
	}
	usage.TotalTokens = usage.PromptTokens + usage.CompletionTokens + usage.ImageTokens + usage.AudioTokens + usage.VideoTokens
	return usage
}

// CountText 计量纯文本 Token（便捷方法）。
func (c *Counter) CountText(text string) int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.textCounter.Count(text)
}

// CountImage 计量图像 Token（便捷方法）。
func (c *Counter) CountImage(width, height int, detail string) int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.imageCounter.Count(width, height, detail)
}

// CountAudio 计量语音 Token（便捷方法）。
func (c *Counter) CountAudio(durationMs int64) int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.audioCounter.Count(durationMs)
}

// CountVideo 计量视频 Token（便捷方法）。
func (c *Counter) CountVideo(durationMs int64) int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.videoCounter.Count(durationMs)
}

// ============ 辅助函数 ============

// parseImageSizeFromURI 从图像 URL 或 data URI 解析尺寸。
//
// 当前实现：返回默认尺寸（1024x1024）。
// 扩展点：可从 data URI 头部或远程图像元信息解析真实尺寸。
func parseImageSizeFromURI(uri string) (int, int) {
	// data URI 中可能包含尺寸信息（非标准），此处简化为默认值。
	// 生产环境应解码图像头部获取真实尺寸。
	_ = uri
	return defaultImageWidth, defaultImageHeight
}

// parseImageSizeFromString 从尺寸字符串（如 "1024x1024"）解析宽高。
func parseImageSizeFromString(size string) (int, int) {
	if size == "" {
		return defaultImageWidth, defaultImageHeight
	}
	var w, h int
	if _, err := formatSscanf(size, &w, &h); err == nil {
		return w, h
	}
	return defaultImageWidth, defaultImageHeight
}

// formatSscanf 简化的 "WxH" 解析，避免引入 fmt.Sprintf 增加依赖。
func formatSscanf(size string, w, h *int) (int, error) {
	// 形如 "1024x1024"
	parts := splitByChar(size, 'x')
	if len(parts) != 2 {
		return 0, errInvalidSize
	}
	*w = atoi(parts[0])
	*h = atoi(parts[1])
	if *w <= 0 || *h <= 0 {
		return 0, errInvalidSize
	}
	return 2, nil
}

// estimateAudioDurationFromBase64 从 base64 音频数据估算时长（毫秒）。
//
// 简化估算：按 base64 字符数反推字节数，再按码率估算时长。
// mp3 ~16kbps → 每分钟约 120KB → 每字节约 0.5ms。
func estimateAudioDurationFromBase64(data, format string) int64 {
	if data == "" {
		return 0
	}
	// base64 字符数 → 原始字节数
	bytes := int(float64(len(data)) / base64Expansion)
	// 按码率估算时长（毫秒）
	bytesPerMinute := 120 * 1024 // mp3 ~16kbps
	switch strings.ToLower(format) {
	case "wav":
		bytesPerMinute = 1920 * 1024 // wav ~256kbps
	case "flac":
		bytesPerMinute = 600 * 1024 // flac ~80kbps
	case "ogg":
		bytesPerMinute = 120 * 1024
	}
	return int64(bytes) * 60 * 1000 / int64(bytesPerMinute)
}

// estimateVideoDurationFromURI 从视频 URL 估算时长（毫秒）。
//
// 当前实现：返回 0（无法从 URL 估算）。
// 扩展点：可调用 ffprobe 或读取视频元信息获取真实时长。
func estimateVideoDurationFromURI(uri string) int64 {
	_ = uri
	return 0
}

// ============ 字符串辅助（避免引入 strconv 增加复杂度） ============

// errInvalidSize 非法尺寸错误。
var errInvalidSize = newInvalidSizeError()

type invalidSizeError struct{}

func (e *invalidSizeError) Error() string { return "invalid image size" }
func newInvalidSizeError() error          { return &invalidSizeError{} }

// splitByChar 按单字符分隔字符串。
func splitByChar(s string, sep byte) []string {
	var parts []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == sep {
			parts = append(parts, s[start:i])
			start = i + 1
		}
	}
	parts = append(parts, s[start:])
	return parts
}

// atoi 简易字符串转整数（仅支持正整数）。
func atoi(s string) int {
	n := 0
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return -1
		}
		n = n*10 + int(s[i]-'0')
	}
	return n
}