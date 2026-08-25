package api

// Package api 实现多模态 OpenAI 兼容 API 控制器。
//
// 在现有 /api/v1/chat/completions 基础上新增 OpenAI 标准端点：
//   - POST /v1/chat/completions  多模态对话补全（OpenAI 兼容）
//   - POST /v1/chat/completions?stream=true  SSE 流式响应
//   - POST /v1/batch/jobs        提交异步批处理任务
//   - GET  /v1/batch/jobs/:id    查询批处理任务状态/结果
//   - GET  /v1/batch/jobs        列出所有批处理任务
//   - GET  /v1/routing/rules     查询路由规则
//   - POST /v1/routing/rules     添加路由规则
//   - GET  /v1/routing/decision  查询路由决策（不实际调用）
//
// 多模态扩展：
//   - 输入：messages[].content 可为字符串（纯文本）或数组（多模态片段）
//   - 输出：choices[].message.content 同上
//   - 自研扩展字段：scene / modality_out

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/middleware"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/routing"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/streaming"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/token"
)

// ============ 多模态 Handler ============

// MultimodalHandler 多模态 API 请求处理器。
//
// 持有四维度路由引擎、Token 计量器、SSE 流式响应器、批处理管理器。
type MultimodalHandler struct {
	routing  *routing.Engine
	counter  *token.Counter
	streamer *streaming.SSEStreamer
	batchMgr *streaming.BatchJobManager
	// chatFunc 实际调用函数（由网关注入，封装路由+调用+计量）。
	chatFunc func(context.Context, provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error)
}

// NewMultimodalHandler 构造多模态 handler。
//
// chatFunc 由网关注入，封装：路由决策 → Provider 调用 → Token 计量 → 审计。
func NewMultimodalHandler(
	routingEngine *routing.Engine,
	counter *token.Counter,
	chatFunc func(context.Context, provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error),
) *MultimodalHandler {
	h := &MultimodalHandler{
		routing:  routingEngine,
		counter:  counter,
		streamer: streaming.NewSSEStreamer(counter),
		chatFunc: chatFunc,
	}
	// 初始化批处理管理器（worker=100，支持并发 ≥100）
	h.batchMgr = streaming.NewBatchJobManager(chatFunc, counter, streaming.DefaultBatchConfig())
	return h
}

// RegisterRoutes 注册多模态 OpenAI 兼容路由。
//
// authMiddleware 用于需要认证的端点。
func (h *MultimodalHandler) RegisterRoutes(r *gin.Engine, authMiddleware gin.HandlerFunc) {
	// OpenAI 兼容端点（需认证）
	v1 := r.Group("/v1")
	v1.Use(authMiddleware)
	{
		v1.POST("/chat/completions", h.ChatCompletions)
		v1.POST("/batch/jobs", h.SubmitBatchJob)
		v1.GET("/batch/jobs", h.ListBatchJobs)
		v1.GET("/batch/jobs/:id", h.GetBatchJob)
		v1.GET("/routing/rules", h.ListRoutingRules)
		// 路由规则属平台治理操作：admin 门禁（与 Provider 注册同级的 SSRF 面）。
		v1.POST("/routing/rules", middleware.RequireRole("admin"), h.AddRoutingRule)
		v1.GET("/routing/decision", h.QueryRoutingDecision)
		v1.POST("/token/estimate", h.EstimateTokens)
	}
}

// Stop 优雅关闭（停止批处理 worker pool）。
func (h *MultimodalHandler) Stop() {
	if h.batchMgr != nil {
		h.batchMgr.Stop()
	}
}

// ============ /v1/chat/completions ============

// ChatCompletions POST /v1/chat/completions
//
// OpenAI 兼容多模态对话补全端点。
// 支持：
//   - 纯文本输入（messages[].content 为字符串）
//   - 多模态输入（messages[].content 为数组，含 text/image_url/input_audio/video_url）
//   - 流式响应（stream:true，SSE）
//   - 路由场景标识（scene 字段）
//   - 期望输出模态（modality_out 字段）
func (h *MultimodalHandler) ChatCompletions(c *gin.Context) {
	var rawReq openAIChatRequest
	if err := c.ShouldBindJSON(&rawReq); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if strings.TrimSpace(rawReq.Model) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "model is required"})
		return
	}
	if len(rawReq.Messages) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "messages must not be empty"})
		return
	}

	// 转换为内部多模态请求
	req := rawReq.toMultimodalRequest()
	req.TenantID = ctxString(c, "tenantId")
	req.UserID = ctxString(c, "userId")

	// 流式响应
	if req.Stream {
		if err := h.streamer.StreamChat(c, req, h.chatFunc); err != nil {
			// SSE 响应头已发送，无法再向客户端返回 JSON 错误，仅记录日志便于排障。
			slog.Warn("sse stream chat failed",
				slog.String("model", req.Model),
				slog.String("tenantId", req.TenantID),
				slog.String("error", err.Error()),
			)
		}
		return
	}

	// 非流式：直接调用
	resp, err := h.chatFunc(c.Request.Context(), req)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}

// ============ /v1/batch/jobs ============

// SubmitBatchJob POST /v1/batch/jobs
//
// 提交异步批处理任务，立即返回 job_id。
// 客户端通过 GET /v1/batch/jobs/:id 轮询查询结果。
func (h *MultimodalHandler) SubmitBatchJob(c *gin.Context) {
	var rawReq openAIChatRequest
	if err := c.ShouldBindJSON(&rawReq); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if strings.TrimSpace(rawReq.Model) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "model is required"})
		return
	}
	if len(rawReq.Messages) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "messages must not be empty"})
		return
	}

	req := rawReq.toMultimodalRequest()
	req.TenantID = ctxString(c, "tenantId")
	req.UserID = ctxString(c, "userId")
	// 批处理任务不支持流式
	req.Stream = false

	jobID := h.batchMgr.Submit(req)
	c.JSON(http.StatusAccepted, gin.H{
		"id":        jobID,
		"status":    "queued",
		"createdAt": time.Now().UTC().Format(time.RFC3339),
	})
}

// GetBatchJob GET /v1/batch/jobs/:id
//
// 查询批处理任务状态与结果。
func (h *MultimodalHandler) GetBatchJob(c *gin.Context) {
	jobID := c.Param("id")
	job, ok := h.batchMgr.Get(jobID)
	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "job not found"})
		return
	}
	c.JSON(http.StatusOK, job)
}

// ListBatchJobs GET /v1/batch/jobs
//
// 列出所有批处理任务。
func (h *MultimodalHandler) ListBatchJobs(c *gin.Context) {
	jobs := h.batchMgr.List()
	submitted, succeeded, failed := h.batchMgr.Stats()
	c.JSON(http.StatusOK, gin.H{
		"jobs":      jobs,
		"total":     len(jobs),
		"submitted": submitted,
		"succeeded": succeeded,
		"failed":    failed,
	})
}

// ============ /v1/routing/* ============

// ListRoutingRules GET /v1/routing/rules
func (h *MultimodalHandler) ListRoutingRules(c *gin.Context) {
	rules := h.routing.Rules()
	quotas := h.routing.TenantQuotas()
	costs := h.routing.ProviderCosts()
	c.JSON(http.StatusOK, gin.H{
		"rules":         rules,
		"tenantQuotas":  quotas,
		"providerCosts": costs,
	})
}

// AddRoutingRuleRequest 添加路由规则请求。
type AddRoutingRuleRequest struct {
	ID       string `json:"id"`
	Model    string `json:"model"`
	TenantID string `json:"tenantId"`
	Scene    string `json:"scene"`
	Provider string `json:"provider"`
	Priority int    `json:"priority"`
	Weight   int    `json:"weight"`
}

// AddRoutingRule POST /v1/routing/rules
func (h *MultimodalHandler) AddRoutingRule(c *gin.Context) {
	var req AddRoutingRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if req.Provider == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "provider is required"})
		return
	}
	if req.Weight <= 0 {
		req.Weight = 1
	}
	h.routing.AddRule(routing.Rule{
		ID:       req.ID,
		Model:    req.Model,
		TenantID: req.TenantID,
		Scene:    req.Scene,
		Provider: req.Provider,
		Priority: req.Priority,
		Weight:   req.Weight,
	})
	c.JSON(http.StatusCreated, gin.H{"status": "created", "rule": req})
}

// QueryRoutingDecision GET /v1/routing/decision
//
// 查询路由决策（不实际调用），便于调试与可观测。
// 参数：model / tenant / scene
func (h *MultimodalHandler) QueryRoutingDecision(c *gin.Context) {
	model := c.Query("model")
	tenant := c.Query("tenant")
	if tenant == "" {
		tenant = ctxString(c, "tenantId")
	}
	scene := c.Query("scene")

	decision, err := h.routing.Route(model, tenant, scene)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, decision)
}

// ============ /v1/token/estimate ============

// EstimateTokensRequest Token 估算请求。
type EstimateTokensRequest struct {
	Model    string                       `json:"model"`
	Messages []provider.MultimodalMessage `json:"messages"`
}

// EstimateTokens POST /v1/token/estimate
//
// 估算多模态请求的 Token 用量（不实际调用）。
func (h *MultimodalHandler) EstimateTokens(c *gin.Context) {
	var req EstimateTokensRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	usage := h.counter.CountRequest(provider.MultimodalChatRequest{
		Model:    req.Model,
		Messages: req.Messages,
	})
	c.JSON(http.StatusOK, gin.H{
		"usage":   usage,
		"summary": provider.ModalitySummary(req.Messages),
	})
}

// ============ OpenAI 请求适配 ============

// openAIChatRequest OpenAI 兼容请求格式。
//
// OpenAI 标准格式：messages[].content 可为字符串或数组。
// Gin 的 ShouldBindJSON 不支持 union 类型，故用 json.RawMessage 自定义解析。
type openAIChatRequest struct {
	Model       string          `json:"model"`
	Messages    []openAIMessage `json:"messages"`
	Temperature float64         `json:"temperature,omitempty"`
	MaxTokens   int             `json:"max_tokens,omitempty"`
	Stream      bool            `json:"stream,omitempty"`
	TopP        float64         `json:"top_p,omitempty"`
	Stop        []string        `json:"stop,omitempty"`
	Scene       string          `json:"scene,omitempty"`
	ModalityOut []string        `json:"modality_out,omitempty"`
}

// openAIMessage OpenAI 消息格式（content 可为字符串或数组）。
type openAIMessage struct {
	Role    string          `json:"role"`
	Content json.RawMessage `json:"content"`
}

// toMultimodalRequest 转换为内部多模态请求。
func (r *openAIChatRequest) toMultimodalRequest() provider.MultimodalChatRequest {
	msgs := make([]provider.MultimodalMessage, 0, len(r.Messages))
	for _, m := range r.Messages {
		mm := provider.MultimodalMessage{Role: m.Role}
		// 尝试解析 content：字符串或数组
		content, parts := parseContent(m.Content)
		mm.Content = content
		mm.Parts = parts
		msgs = append(msgs, mm)
	}
	return provider.MultimodalChatRequest{
		Model:       r.Model,
		Messages:    msgs,
		Temperature: r.Temperature,
		MaxTokens:   r.MaxTokens,
		Stream:      r.Stream,
		TopP:        r.TopP,
		Stop:        r.Stop,
		Scene:       r.Scene,
		ModalityOut: r.ModalityOut,
	}
}

// parseContent 解析 OpenAI content 字段（字符串或数组）。
//
// 返回 (textContent, parts)。若 content 为字符串，返回 (str, nil)；
// 若 content 为数组，解析为多模态片段列表。
func parseContent(raw json.RawMessage) (string, []provider.ContentPart) {
	if len(raw) == 0 {
		return "", nil
	}
	// 尝试字符串
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return s, nil
	}
	// 尝试数组
	var parts []provider.ContentPart
	if err := json.Unmarshal(raw, &parts); err == nil {
		return "", parts
	}
	return "", nil
}

// ============ 错误处理 ============

// writeError 将错误映射到 HTTP 状态码。
func (h *MultimodalHandler) writeError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, provider.ErrModelNotFound):
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
	case errors.Is(err, provider.ErrProviderNotFound):
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
	case errors.Is(err, provider.ErrInvalidRequest):
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
	case errors.Is(err, provider.ErrSensitiveContent):
		c.JSON(http.StatusForbidden, gin.H{"error": err.Error()})
	case errors.Is(err, provider.ErrUpstreamUnavailable):
		c.JSON(http.StatusBadGateway, gin.H{"error": err.Error()})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
	}
}
