package gateway

import (
	"fmt"
	"sort"
	"sync"

	"github.com/shuqing/bigdata/llm-gateway/internal/provider"
)

// ============ 模型路由 ============
//
// 根据模型名 / 租户 / 优先级路由到对应 Provider。
// 路由规则按优先级（priority 越大越优先）排序，租户级规则优先于全局规则。

// RouteRule 路由规则。
type RouteRule struct {
	Model    string // 逻辑模型名
	Provider string // 目标 Provider 名
	TenantID string // 租户 ID（空表示全局规则）
	Priority int    // 优先级（越大越优先）
}

// Router 模型路由器。
type Router struct {
	mu    sync.RWMutex
	rules []RouteRule
	// defaultProvider 默认 Provider，当无匹配规则时使用。
	defaultProvider string
}

// NewRouter 构造路由器。
func NewRouter() *Router {
	return &Router{}
}

// SetDefault 设置默认 Provider。
func (r *Router) SetDefault(name string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.defaultProvider = name
}

// Add 添加路由规则。
func (r *Router) Add(rule RouteRule) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rules = append(r.rules, rule)
	// 按 priority 降序排序，保证匹配时高优先级在前。
	sort.SliceStable(r.rules, func(i, j int) bool {
		return r.rules[i].Priority > r.rules[j].Priority
	})
}

// RemoveProvider 移除所有指向指定 Provider 的规则。
func (r *Router) RemoveProvider(providerName string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	filtered := r.rules[:0]
	for _, rule := range r.rules {
		if rule.Provider != providerName {
			filtered = append(filtered, rule)
		}
	}
	r.rules = filtered
}

// Route 根据模型名 + 租户 ID 路由到 Provider。
//
// 匹配顺序：
//  1. 同时匹配 model 与 tenantId 的规则（按 priority 降序）
//  2. 匹配 model 且 tenantId 为空的全局规则（按 priority 降序）
//  3. 默认 Provider
//  4. 返回 ErrModelNotFound
func (r *Router) Route(model, tenantID string) (string, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	// 1. 租户级规则
	for _, rule := range r.rules {
		if rule.Model == model && rule.TenantID == tenantID && rule.TenantID != "" {
			return rule.Provider, nil
		}
	}
	// 2. 全局规则
	for _, rule := range r.rules {
		if rule.Model == model && rule.TenantID == "" {
			return rule.Provider, nil
		}
	}
	// 3. 默认 Provider
	if r.defaultProvider != "" {
		return r.defaultProvider, nil
	}
	// 4. 未找到
	return "", fmt.Errorf("%w: %s", provider.ErrModelNotFound, model)
}

// Rules 返回当前所有规则（按 priority 降序）。
func (r *Router) Rules() []RouteRule {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]RouteRule, len(r.rules))
	copy(out, r.rules)
	return out
}
