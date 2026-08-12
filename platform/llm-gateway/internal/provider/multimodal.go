package provider

// Package provider 定义多模态消息扩展类型。
//
// 在 OpenAI 兼容协议基础上扩展支持：输入文本+图像+语音+视频，
// 输出文本+图像+语音。ContentPart 用 type 字段区分模态：
//   - text       文本（value 为字符串）
//   - image_url  图像（image_url.url 为 URL 或 base64 data URI）
//   - input_audio 语音（input_audio.data 为 base64）
//   - video_url 视频（video_url.url 为 URL 或 base64 data URI）
//   - output_image 输出图像（生成结果）
//   - output_audio 输出语音（TTS 结果）
//
// 设计原则：与 OpenAI Chat Completions 协议保持兼容，
// 多模态字段使用 OpenAI 已有命名（image_url / input_audio），
// 自研扩展字段（video_url / output_image / output_audio）以 x- 前缀保留。

// ============ 多模态消息内容 ============

// ContentPart 单条消息的一个内容片段，支持多模态。
//
// OpenAI 兼容字段：
//   - Type="text"        → Text 字段为文本内容
//   - Type="image_url"   → ImageURL 字段为图像 URL 或 data URI
//   - Type="input_audio" → InputAudio 字段为 base64 音频
//
// 自研扩展字段（视频输入、图像/语音输出）：
//   - Type="video_url"     → VideoURL 字段
//   - Type="output_image"  → OutputImage 字段（生成图像）
//   - Type="output_audio"  → OutputAudio 字段（生成语音）
type ContentPart struct {
	Type string `json:"type"`

	// Text 文本内容（Type="text" 时使用）。
	Text string `json:"text,omitempty"`

	// ImageURL 图像输入（Type="image_url" 时使用）。
	ImageURL *ImageURLPart `json:"image_url,omitempty"`

	// InputAudio 语音输入（Type="input_audio" 时使用）。
	InputAudio *InputAudioPart `json:"input_audio,omitempty"`

	// VideoURL 视频输入（Type="video_url" 时使用，自研扩展）。
	VideoURL *VideoURLPart `json:"video_url,omitempty"`

	// OutputImage 图像输出（Type="output_image" 时使用，自研扩展）。
	OutputImage *OutputImagePart `json:"output_image,omitempty"`

	// OutputAudio 语音输出（Type="output_audio" 时使用，自研扩展）。
	OutputAudio *OutputAudioPart `json:"output_audio,omitempty"`
}

// ImageURLPart 图像 URL 输入片段。
//
// URL 支持 http(s):// 远程地址或 data:image/...;base64,... 数据 URI。
// Detail 控制图像处理精度："low" / "high" / "auto"（默认 auto）。
type ImageURLPart struct {
	URL    string `json:"url"`
	Detail string `json:"detail,omitempty"` // low / high / auto
}

// InputAudioPart 语音输入片段。
//
// Data 为 base64 编码的音频字节。Format 取值：mp3 / wav / ogg / flac。
type InputAudioPart struct {
	Data   string `json:"data"`
	Format string `json:"format,omitempty"` // mp3 / wav / ogg / flac
}

// VideoURLPart 视频输入片段（自研扩展）。
//
// URL 支持 http(s):// 远程地址或 data:video/...;base64,... 数据 URI。
// DurationMs 视频时长（毫秒），用于 Token 计量；若为 0 则由计量器按字节估算。
type VideoURLPart struct {
	URL        string `json:"url"`
	DurationMs int64  `json:"durationMs,omitempty"`
}

// OutputImagePart 图像输出片段（自研扩展）。
//
// URL 为生成图像的下载地址或 data URI。
// Size 取值：256x256 / 512x512 / 1024x1024 等。
type OutputImagePart struct {
	URL  string `json:"url"`
	Size string `json:"size,omitempty"`
}

// OutputAudioPart 语音输出片段（自研扩展）。
//
// Data 为 base64 编码的合成语音。Format 同 InputAudioPart.Format。
type OutputAudioPart struct {
	Data   string `json:"data"`
	Format string `json:"format,omitempty"`
}

// ============ 多模态消息 ============

// MultimodalMessage 多模态对话消息。
//
// 与 Message 兼容：Role 字段一致；Content 字段保留为纯文本快捷方式，
// Parts 字段为多模态内容片段列表。两者互斥：
//   - 若 Parts 非空，按多模态处理；
//   - 否则按 Content 纯文本处理。
type MultimodalMessage struct {
	Role    string        `json:"role"`
	Content string        `json:"content,omitempty"`
	Parts   []ContentPart `json:"parts,omitempty"`
}

// ============ 多模态请求/响应 ============

// MultimodalChatRequest 多模态对话补全请求。
//
// 在 ChatRequest 基础上扩展支持多模态消息。
// 当 MultimodalMessages 非空时按多模态处理；否则回退到 Messages 纯文本。
type MultimodalChatRequest struct {
	Model    string              `json:"model"`
	Messages []MultimodalMessage `json:"messages,omitempty"`
	// 兼容字段：若调用方使用 OpenAI 标准 messages（纯文本），仍可解析。
	RawMessages []Message `json:"raw_messages,omitempty"`

	Temperature float64  `json:"temperature,omitempty"`
	MaxTokens   int      `json:"max_tokens,omitempty"`
	Stream      bool     `json:"stream,omitempty"`
	TopP        float64  `json:"top_p,omitempty"`
	Stop        []string `json:"stop,omitempty"`

	// Scene 路由场景标识（对话/微调/评测），用于四维度路由。
	Scene string `json:"scene,omitempty"`
	// ModalityOut 期望输出模态：text / image / audio / text+image 等。
	ModalityOut []string `json:"modality_out,omitempty"`

	// TenantID / UserID 从 JWT 提取，不参与 JSON 序列化。
	TenantID string `json:"-"`
	UserID   string `json:"-"`
}

// MultimodalChatResponse 多模态对话补全响应。
//
// 在 ChatResponse 基础上扩展支持多模态输出。
type MultimodalChatResponse struct {
	ID      string             `json:"id"`
	Object  string             `json:"object,omitempty"`
	Model   string             `json:"model"`
	Choices []MultimodalChoice `json:"choices"`
	Usage   MultimodalUsage    `json:"usage"`
	// Provider 实际路由到的 Provider 名（用于路由可观测）。
	Provider string `json:"provider,omitempty"`
	// RouteReason 路由决策说明（命中哪条规则）。
	RouteReason string `json:"route_reason,omitempty"`
}

// MultimodalChoice 多模态候选回复。
type MultimodalChoice struct {
	Index        int               `json:"index"`
	Message      MultimodalMessage `json:"message"`
	FinishReason string            `json:"finish_reason,omitempty"`
}

// MultimodalUsage 多模态 Token 用量统计。
//
// 各模态独立计量，TotalTokens 为各模态折算后的总和。
type MultimodalUsage struct {
	// 文本 Token
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	// 图像 Token（按分辨率折算）
	ImageTokens int `json:"image_tokens"`
	// 语音 Token（按时长折算）
	AudioTokens int `json:"audio_tokens"`
	// 视频 Token（按时长折算）
	VideoTokens int `json:"video_tokens"`
	// 各模态折算后总和
	TotalTokens int `json:"total_tokens"`
}

// ============ 转换辅助 ============

// ToMessages 将多模态消息转换为纯文本消息列表。
//
// 用于回退到只支持文本的 Provider：将所有文本片段拼接为 Content，
// 非文本片段以占位符标注（如 [image] / [audio] / [video]）。
func ToMessages(mm []MultimodalMessage) []Message {
	out := make([]Message, 0, len(mm))
	for _, m := range mm {
		if len(m.Parts) == 0 {
			out = append(out, Message{Role: m.Role, Content: m.Content})
			continue
		}
		// 拼接多模态片段为纯文本
		var sb []byte
		for _, p := range m.Parts {
			switch p.Type {
			case "text":
				sb = append(sb, p.Text...)
			case "image_url":
				sb = append(sb, []byte("[image]")...)
			case "input_audio":
				sb = append(sb, []byte("[audio]")...)
			case "video_url":
				sb = append(sb, []byte("[video]")...)
			case "output_image":
				sb = append(sb, []byte("[output-image]")...)
			case "output_audio":
				sb = append(sb, []byte("[output-audio]")...)
			}
		}
		out = append(out, Message{Role: m.Role, Content: string(sb)})
	}
	return out
}

// FromMessages 将纯文本消息列表转换为多模态消息列表。
func FromMessages(msgs []Message) []MultimodalMessage {
	out := make([]MultimodalMessage, 0, len(msgs))
	for _, m := range msgs {
		out = append(out, MultimodalMessage{Role: m.Role, Content: m.Content})
	}
	return out
}

// HasModality 检查多模态消息中是否包含指定模态。
//
// modality 取值：text / image_url / input_audio / video_url /
// output_image / output_audio。
func HasModality(mm []MultimodalMessage, modality string) bool {
	for _, m := range mm {
		if m.Content != "" && modality == "text" {
			return true
		}
		for _, p := range m.Parts {
			if p.Type == modality {
				return true
			}
		}
	}
	return false
}

// ModalitySummary 返回多模态消息中各模态的片段计数。
//
// 返回 map[modality]count，便于日志与计量。
func ModalitySummary(mm []MultimodalMessage) map[string]int {
	summary := make(map[string]int)
	for _, m := range mm {
		if m.Content != "" {
			summary["text"]++
		}
		for _, p := range m.Parts {
			summary[p.Type]++
		}
	}
	return summary
}
