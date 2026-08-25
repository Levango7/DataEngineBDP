// Package gateway 实现大模型网关核心：路由 / 负载均衡 / Token 计量 / 安全审计。
//
// 网关只做治理，不直接持有模型——与封装层"客户只见能力不见底座"思想一致。
// 路由：根据模型名 / 租户 / 优先级路由到对应 Provider 实例。
// 负载均衡：同一模型的多个实例间按权重轮询。
// 计量：按租户 / 模型统计 Token 用量与调用延迟。
// 审计：记录请求日志、敏感词过滤、安全审计。
package gateway

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
)

// ============ Gateway 核心结构 ============

// Gateway 大模型网关核心。
type Gateway struct {
	mu        sync.RWMutex
	providers map[string]provider.LLMProvider // name -> provider
	router    *Router
	balancer  *LoadBalancer
	meter     *TokenMeter
	auditor   *Auditor
}

// New 构造网关实例。
func New(auditor *Auditor) *Gateway {
	return &Gateway{
		providers: make(map[string]provider.LLMProvider),
		router:    NewRouter(),
		balancer:  NewLoadBalancer(),
		meter:     NewTokenMeter(),
		auditor:   auditor,
	}
}

// RegisterProvider 注册一个 Provider 实例。
func (g *Gateway) RegisterProvider(p provider.LLMProvider, weight int) {
	g.mu.Lock()
	defer g.mu.Unlock()
	name := p.Name()
	g.providers[name] = p
	g.balancer.Add(name, weight)
}

// UnregisterProvider 注销 Provider。
func (g *Gateway) UnregisterProvider(name string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if _, ok := g.providers[name]; !ok {
		return false
	}
	delete(g.providers, name)
	g.balancer.Remove(name)
	g.router.RemoveProvider(name)
	return true
}

// AddRoute 添加路由规则。
func (g *Gateway) AddRoute(rule RouteRule) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.router.Add(rule)
}

// ListProviders 返回所有 Provider 名称。
func (g *Gateway) ListProviders() []string {
	g.mu.RLock()
	defer g.mu.RUnlock()
	names := make([]string, 0, len(g.providers))
	for n := range g.providers {
		names = append(names, n)
	}
	sort.Strings(names)
	return names
}

// Provider 返回指定名称的 Provider。
func (g *Gateway) Provider(name string) (provider.LLMProvider, bool) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	p, ok := g.providers[name]
	return p, ok
}

// Meter 暴露 Token 计量器。
func (g *Gateway) Meter() *TokenMeter { return g.meter }

// Auditor 暴露审计器。
func (g *Gateway) Auditor() *Auditor { return g.auditor }

// ============ 对话补全 ============

// ChatCompletion 执行对话补全：路由 → 负载均衡 → 调用 → 计量 → 审计。
func (g *Gateway) ChatCompletion(ctx context.Context, req provider.ChatRequest) (*provider.ChatResponse, error) {
	start := time.Now()

	// 1. 路由：根据模型名 + 租户找到 Provider 名。
	providerName, err := g.router.Route(req.Model, req.TenantID)
	if err != nil {
		return nil, err
	}

	// 2. 负载均衡：在同名 Provider 多实例间选一个（当前实现：单实例直接返回）。
	selected, err := g.balancer.Pick(providerName)
	if err != nil {
		return nil, err
	}

	g.mu.RLock()
	p, ok := g.providers[selected]
	g.mu.RUnlock()
	if !ok {
		return nil, fmt.Errorf("%w: %s", provider.ErrProviderNotFound, selected)
	}

	// 3. 审计前置：敏感词检查。
	if g.auditor != nil {
		if err := g.auditor.CheckRequest(req.TenantID, req.UserID, req.Model, req.Messages); err != nil {
			return nil, err
		}
	}

	// 4. 调用 Provider。
	resp, err := p.ChatCompletion(ctx, req)
	latency := time.Since(start)

	// 5. 计量 + 审计后置。
	if resp != nil {
		g.meter.Record(req.TenantID, req.Model, int64(resp.Usage.TotalTokens), latency)
	} else {
		g.meter.Record(req.TenantID, req.Model, 0, latency)
	}
	if g.auditor != nil {
		g.auditor.RecordChat(req.TenantID, req.UserID, req.Model, resp, err, latency)
	}
	return resp, err
}

// ============ 向量嵌入 ============

// Embeddings 执行向量嵌入：路由 → 调用 → 计量 → 审计。
func (g *Gateway) Embeddings(ctx context.Context, req provider.EmbeddingRequest) (*provider.EmbeddingResponse, error) {
	start := time.Now()

	providerName, err := g.router.Route(req.Model, req.TenantID)
	if err != nil {
		return nil, err
	}
	selected, err := g.balancer.Pick(providerName)
	if err != nil {
		return nil, err
	}
	g.mu.RLock()
	p, ok := g.providers[selected]
	g.mu.RUnlock()
	if !ok {
		return nil, fmt.Errorf("%w: %s", provider.ErrProviderNotFound, selected)
	}

	if g.auditor != nil {
		if err := g.auditor.CheckEmbedding(req.TenantID, req.UserID, req.Model, req.Input); err != nil {
			return nil, err
		}
	}

	resp, err := p.Embeddings(ctx, req)
	latency := time.Since(start)

	if resp != nil {
		g.meter.Record(req.TenantID, req.Model, int64(resp.Usage.TotalTokens), latency)
	} else {
		g.meter.Record(req.TenantID, req.Model, 0, latency)
	}
	if g.auditor != nil {
		g.auditor.RecordEmbedding(req.TenantID, req.UserID, req.Model, resp, err, latency)
	}
	return resp, err
}

// ============ 模型列表 ============

// ListModels 汇总所有 Provider 的可路由模型。
//
// 并发安全：先在读锁内快照 provider 列表，再在锁外调用上游
// （上游调用含网络 IO，持锁调用会阻塞 Register/Unregister 写锁）。
func (g *Gateway) ListModels(ctx context.Context) ([]provider.ModelInfo, error) {
	g.mu.RLock()
	providers := make([]provider.LLMProvider, 0, len(g.providers))
	for _, p := range g.providers {
		providers = append(providers, p)
	}
	g.mu.RUnlock()

	var all []provider.ModelInfo
	for _, p := range providers {
		ms, err := p.Models(ctx)
		if err != nil {
			// 单个 Provider 失败不阻塞整体列表。
			continue
		}
		all = append(all, ms...)
	}
	return all, nil
}

// ============ 健康检查 ============

// HealthCheck 检查所有 Provider 健康状态，返回不健康的 Provider 列表。
//
// 与 ListModels 相同的快照策略：锁外执行上游健康探测（网络 IO）。
func (g *Gateway) HealthCheck(ctx context.Context) map[string]error {
	g.mu.RLock()
	providers := make(map[string]provider.LLMProvider, len(g.providers))
	for name, p := range g.providers {
		providers[name] = p
	}
	g.mu.RUnlock()

	result := make(map[string]error)
	for name, p := range providers {
		if err := p.HealthCheck(ctx); err != nil {
			result[name] = err
		}
	}
	return result
}
