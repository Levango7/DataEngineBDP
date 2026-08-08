package gateway

// Package gateway 实现多模态网关核心。
//
// 在现有 Gateway 基础上扩展支持多模态对话补全：
//   - 四维度路由决策（模型/租户/场景/成本）
//   - 多模态 Token 计量（文本/图像/语音/视频）
//   - 与现有 Gateway 共享 Provider 注册与负载均衡
//
// 调用链：
//  请求 → 四维度路由 → 负载均衡 → Provider 调用 → 多模态 Token 计量 → 审计

import (
	"context"
	"fmt"
	"time"

	"github.com/shuqing/bigdata/llm-gateway/internal/provider"
	"github.com/shuqing/bigdata/llm-gateway/internal/routing"
	"github.com/shuqing/bigdata/llm-gateway/internal/token"
)

// ============ 多模态网关扩展 ============

// MultimodalExt 多模态网关扩展。
//
// 持有四维度路由引擎与多模态 Token 计量器，复用现有 Gateway 的 Provider 注册与负载均衡。
type MultimodalExt struct {
	gateway      *Gateway
	routing      *routing.Engine
	tokenCounter *token.Counter
}

// NewMultimodalExt 构造多模态网关扩展。
func NewMultimodalExt(gw *Gateway, routingEngine *routing.Engine, counter *token.Counter) *MultimodalExt {
	return &MultimodalExt{
		gateway:      gw,
		routing:      routingEngine,
		tokenCounter: counter,
	}
}

// Routing 暴露四维度路由引擎。
func (e *MultimodalExt) Routing() *routing.Engine { return e.routing }

// TokenCounter 暴露多模态 Token 计量器。
func (e *MultimodalExt) TokenCounter() *token.Counter { return e.tokenCounter }

// ChatCompletion 执行多模态对话补全。
//
// 调用链：四维度路由 → 负载均衡 → Provider 调用 → 多模态 Token 计量 → 审计。
// 返回多模态响应（含路由决策信息与多模态 Token 用量）。
func (e *MultimodalExt) ChatCompletion(ctx context.Context, req provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error) {
	start := time.Now()

	// 1. 四维度路由决策
	decision, err := e.routing.Route(req.Model, req.TenantID, req.Scene)
	if err != nil {
		return nil, err
	}

	// 2. 负载均衡（复用现有 Gateway 的 balancer）
	selected, err := e.gateway.balancer.Pick(decision.Provider)
	if err != nil {
		return nil, fmt.Errorf("%w: %s", provider.ErrProviderNotFound, selected)
	}

	p, ok := e.gateway.Provider(selected)
	if !ok {
		return nil, fmt.Errorf("%w: %s", provider.ErrProviderNotFound, selected)
	}

	// 3. 审计前置：敏感词检查（复用现有 Auditor）
	if e.gateway.auditor != nil {
		if err := e.gateway.auditor.CheckRequest(req.TenantID, req.UserID, req.Model, provider.ToMessages(req.Messages)); err != nil {
			return nil, err
		}
	}

	// 4. 调用 Provider（回退到纯文本 ChatRequest）
	chatReq := provider.ChatRequest{
		Model:       req.Model,
		Messages:    provider.ToMessages(req.Messages),
		Temperature: req.Temperature,
		MaxTokens:   req.MaxTokens,
		Stream:      req.Stream,
		TopP:        req.TopP,
		Stop:        req.Stop,
		TenantID:    req.TenantID,
		UserID:      req.UserID,
	}

	resp, err := p.ChatCompletion(ctx, chatReq)
	latency := time.Since(start)

	// 5. 构造多模态响应
	var mmResp *provider.MultimodalChatResponse
	if resp != nil {
		mmResp = &provider.MultimodalChatResponse{
			ID:          resp.ID,
			Object:      resp.Object,
			Model:       resp.Model,
			Provider:    decision.Provider,
			RouteReason: decision.Reason,
			Choices: []provider.MultimodalChoice{
				{
					Index:        0,
					Message:      provider.MultimodalMessage{Role: "assistant", Content: resp.Choices[0].Message.Content},
					FinishReason: resp.Choices[0].FinishReason,
				},
			},
			Usage: provider.MultimodalUsage{
				PromptTokens:     resp.Usage.PromptTokens,
				CompletionTokens: resp.Usage.CompletionTokens,
				TotalTokens:      resp.Usage.TotalTokens,
			},
		}

		// 多模态 Token 计量：在 Provider 返回的纯文本 Token 基础上，
		// 累加输入图像/语音/视频的折算 Token。
		inputUsage := e.tokenCounter.CountRequest(req)
		mmResp.Usage.ImageTokens += inputUsage.ImageTokens
		mmResp.Usage.AudioTokens += inputUsage.AudioTokens
		mmResp.Usage.VideoTokens += inputUsage.VideoTokens
		mmResp.Usage.TotalTokens = mmResp.Usage.PromptTokens + mmResp.Usage.CompletionTokens +
			mmResp.Usage.ImageTokens + mmResp.Usage.AudioTokens + mmResp.Usage.VideoTokens

		// 记量记录（复用现有 TokenMeter，按租户/模型统计）
		e.gateway.meter.Record(req.TenantID, req.Model, int64(mmResp.Usage.TotalTokens), latency)
	} else {
		e.gateway.meter.Record(req.TenantID, req.Model, 0, latency)
	}

	// 6. 审计后置
	if e.gateway.auditor != nil {
		e.gateway.auditor.RecordChat(req.TenantID, req.UserID, req.Model, resp, err, latency)
	}

	return mmResp, err
}
