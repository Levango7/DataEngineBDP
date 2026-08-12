package routing

// Package routing 实现四维度路由引擎。
//
// 四维度路由策略：
//  1. 模型维度：按逻辑模型名（gpt-4 / claude / 通义 / 文心 / 自研）路由
//  2. 租户维度：按租户优先级与配额路由（租户级规则优先于全局规则）
//  3. 场景维度：按调用场景（对话 / 微调 / 评测）路由
//  4. 成本维度：按单价与延迟权衡路由（在多个候选 Provider 间选最优）
//
// 路由决策流程：
//  1. 从规则库筛选出匹配（模型, 租户, 场景）的候选规则
//  2. 按优先级降序排序，取最高优先级的一组候选
//  3. 在候选组内按成本维度（单价 + 延迟）选最优 Provider
//  4. 若无匹配规则，回退到默认 Provider
//
// 设计原则：
//   - 规则可热更新（线程安全）
//   - 决策可观测（返回命中规则与决策原因）
//   - 成本维度可扩展（CostEvaluator 接口）

import (
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
)

// ============ 路由规则 ============

// Rule 四维度路由规则。
//
// 各维度匹配条件：
//   - Model：逻辑模型名，"*" 表示通配
//   - TenantID：租户 ID，空表示全局规则
//   - Scene：场景标识（chat / finetune / eval），空表示任意场景
//   - Provider：目标 Provider 名
//
// Priority 优先级（越大越优先），用于在同一组匹配规则间排序。
// Weight 权重，用于在同优先级候选间加权随机（当前实现：取首个）。
type Rule struct {
	ID       string `json:"id"`
	Model    string `json:"model"`    // 逻辑模型名或 "*"
	TenantID string `json:"tenantId"` // 租户 ID（空=全局）
	Scene    string `json:"scene"`    // 场景（空=任意）
	Provider string `json:"provider"` // 目标 Provider
	Priority int    `json:"priority"` // 优先级（越大越优先）
	Weight   int    `json:"weight"`   // 权重（同优先级间）
}

// TenantQuota 租户配额。
type TenantQuota struct {
	TenantID   string `json:"tenantId"`
	Priority   int    `json:"priority"`   // 租户优先级（越大越优先）
	TPMLimit   int64  `json:"tpmLimit"`   // 每分钟 Token 上限
	RPMLimit   int64  `json:"rpmLimit"`   // 每分钟请求上限
	DailyLimit int64  `json:"dailyLimit"` // 每日 Token 上限
}

// ProviderCost Provider 成本信息。
type ProviderCost struct {
	Provider        string  `json:"provider"`
	InputPricePerM  float64 `json:"inputPricePerM"`  // 输入每百万 Token 单价（元）
	OutputPricePerM float64 `json:"outputPricePerM"` // 输出每百万 Token 单价（元）
	AvgLatencyMs    float64 `json:"avgLatencyMs"`    // 平均延迟（毫秒）
}

// Decision 路由决策结果。
type Decision struct {
	Provider    string `json:"provider"`
	RuleID      string `json:"ruleId"`
	Reason      string `json:"reason"`
	MatchedRule *Rule  `json:"matchedRule,omitempty"`
}

// ============ 路由引擎 ============

// Engine 四维度路由引擎。
//
// 线程安全。规则库与租户配额、Provider 成本均可热更新。
type Engine struct {
	mu              sync.RWMutex
	rules           []Rule
	tenantQuota     map[string]*TenantQuota  // tenantId -> quota
	providerCost    map[string]*ProviderCost // provider -> cost
	costEvaluator   CostEvaluator
	defaultProvider string
}

// CostEvaluator 成本评估器接口。
//
// 在多个候选 Provider 间选最优。默认实现：按单价 + 延迟加权评分。
type CostEvaluator interface {
	Select(candidates []Rule, costs map[string]*ProviderCost) (*Rule, string)
}

// NewEngine 构造路由引擎。
func NewEngine() *Engine {
	return &Engine{
		tenantQuota:   make(map[string]*TenantQuota),
		providerCost:  make(map[string]*ProviderCost),
		costEvaluator: &defaultCostEvaluator{latencyWeight: 0.3},
	}
}

// SetDefault 设置默认 Provider。
func (e *Engine) SetDefault(name string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.defaultProvider = name
}

// AddRule 添加路由规则。
func (e *Engine) AddRule(rule Rule) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.rules = append(e.rules, rule)
	sortRules(e.rules)
}

// AddRules 批量添加路由规则。
func (e *Engine) AddRules(rules []Rule) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.rules = append(e.rules, rules...)
	sortRules(e.rules)
}

// RemoveRule 按 ID 移除规则。
func (e *Engine) RemoveRule(id string) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	for i, r := range e.rules {
		if r.ID == id {
			e.rules = append(e.rules[:i], e.rules[i+1:]...)
			return true
		}
	}
	return false
}

// SetTenantQuota 设置租户配额。
func (e *Engine) SetTenantQuota(quota *TenantQuota) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.tenantQuota[quota.TenantID] = quota
}

// SetProviderCost 设置 Provider 成本信息。
func (e *Engine) SetProviderCost(cost *ProviderCost) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.providerCost[cost.Provider] = cost
}

// SetCostEvaluator 替换成本评估器。
func (e *Engine) SetCostEvaluator(ce CostEvaluator) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.costEvaluator = ce
}

// Rules 返回所有规则快照。
func (e *Engine) Rules() []Rule {
	e.mu.RLock()
	defer e.mu.RUnlock()
	out := make([]Rule, len(e.rules))
	copy(out, e.rules)
	return out
}

// TenantQuotas 返回所有租户配额快照。
func (e *Engine) TenantQuotas() map[string]*TenantQuota {
	e.mu.RLock()
	defer e.mu.RUnlock()
	out := make(map[string]*TenantQuota, len(e.tenantQuota))
	for k, v := range e.tenantQuota {
		out[k] = v
	}
	return out
}

// ProviderCosts 返回所有 Provider 成本快照。
func (e *Engine) ProviderCosts() map[string]*ProviderCost {
	e.mu.RLock()
	defer e.mu.RUnlock()
	out := make(map[string]*ProviderCost, len(e.providerCost))
	for k, v := range e.providerCost {
		out[k] = v
	}
	return out
}

// ============ 路由决策 ============

// Route 执行四维度路由决策。
//
// 参数：
//   - model：逻辑模型名
//   - tenantID：租户 ID
//   - scene：场景标识
//
// 决策流程：
//  1. 筛选匹配 (model, tenantID, scene) 的规则
//  2. 按优先级降序取最高一组
//  3. 在候选组内按成本维度选最优
//  4. 回退到默认 Provider
func (e *Engine) Route(model, tenantID, scene string) (*Decision, error) {
	e.mu.RLock()
	defer e.mu.RUnlock()

	// 1. 筛选匹配规则
	candidates := matchRules(e.rules, model, tenantID, scene)
	if len(candidates) == 0 {
		if e.defaultProvider != "" {
			return &Decision{
				Provider: e.defaultProvider,
				Reason:   "default fallback",
			}, nil
		}
		return nil, fmt.Errorf("%w: model=%s tenant=%s scene=%s", provider.ErrModelNotFound, model, tenantID, scene)
	}

	// 2. 按优先级降序取最高一组（candidates 已排序）
	topPriority := candidates[0].Priority
	var topGroup []Rule
	for _, r := range candidates {
		if r.Priority == topPriority {
			topGroup = append(topGroup, r)
		}
	}

	// 3. 成本维度选最优
	if len(topGroup) == 1 {
		r := topGroup[0]
		return &Decision{
			Provider:    r.Provider,
			RuleID:      r.ID,
			Reason:      fmt.Sprintf("matched rule %s (priority=%d)", r.ID, r.Priority),
			MatchedRule: &r,
		}, nil
	}

	selected, reason := e.costEvaluator.Select(topGroup, e.providerCost)
	if selected == nil {
		// 兜底：取首个
		r := topGroup[0]
		return &Decision{
			Provider:    r.Provider,
			RuleID:      r.ID,
			Reason:      "cost evaluator returned nil, fallback to first",
			MatchedRule: &r,
		}, nil
	}
	return &Decision{
		Provider:    selected.Provider,
		RuleID:      selected.ID,
		Reason:      reason,
		MatchedRule: selected,
	}, nil
}

// RouteForRequest 为多模态请求执行路由（便捷方法）。
func (e *Engine) RouteForRequest(req provider.MultimodalChatRequest) (*Decision, error) {
	return e.Route(req.Model, req.TenantID, req.Scene)
}

// CheckQuota 检查租户配额是否允许本次调用。
//
// currentTPM / currentRPM / currentDaily 为当前累计用量。
// 返回 nil 表示允许；返回 error 表示超限。
func (e *Engine) CheckQuota(tenantID string, estimatedTokens int64, currentTPM, currentRPM, currentDaily int64) error {
	e.mu.RLock()
	defer e.mu.RUnlock()
	q, ok := e.tenantQuota[tenantID]
	if !ok {
		return nil // 无配额限制
	}
	if q.TPMLimit > 0 && currentTPM+estimatedTokens > q.TPMLimit {
		return fmt.Errorf("tenant %s TPM quota exceeded: current=%d + estimated=%d > limit=%d", tenantID, currentTPM, estimatedTokens, q.TPMLimit)
	}
	if q.RPMLimit > 0 && currentRPM+1 > q.RPMLimit {
		return fmt.Errorf("tenant %s RPM quota exceeded: current=%d + 1 > limit=%d", tenantID, currentRPM, q.RPMLimit)
	}
	if q.DailyLimit > 0 && currentDaily+estimatedTokens > q.DailyLimit {
		return fmt.Errorf("tenant %s daily quota exceeded: current=%d + estimated=%d > limit=%d", tenantID, currentDaily, estimatedTokens, q.DailyLimit)
	}
	return nil
}

// ============ 匹配与排序 ============

// matchRules 筛选匹配 (model, tenantID, scene) 的规则。
//
// 匹配规则：
//   - Model 字段匹配：精确匹配或 rule.Model == "*"
//   - TenantID 字段匹配：精确匹配或 rule.TenantID == ""（全局规则）
//   - Scene 字段匹配：精确匹配或 rule.Scene == ""（任意场景）
//
// 返回的规则已按 priority 降序排序。
func matchRules(rules []Rule, model, tenantID, scene string) []Rule {
	var matched []Rule
	for _, r := range rules {
		if !matchModel(r.Model, model) {
			continue
		}
		if !matchTenant(r.TenantID, tenantID) {
			continue
		}
		if !matchScene(r.Scene, scene) {
			continue
		}
		matched = append(matched, r)
	}
	// 已按 priority 降序排序（AddRule 时排序），但筛选后仍保持顺序。
	return matched
}

// matchModel 模型匹配：精确或通配。
func matchModel(ruleModel, reqModel string) bool {
	if ruleModel == "*" || ruleModel == "" {
		return true
	}
	return strings.EqualFold(ruleModel, reqModel)
}

// matchTenant 租户匹配：精确或全局。
//
// 优先级：租户级规则（rule.TenantID == tenantID）优先于全局规则（rule.TenantID == ""）。
// 此处先返回所有匹配，由排序保证租户级优先（通过 priority 字段）。
func matchTenant(ruleTenant, reqTenant string) bool {
	if ruleTenant == "" {
		return true // 全局规则匹配任意租户
	}
	return strings.EqualFold(ruleTenant, reqTenant)
}

// matchScene 场景匹配：精确或任意。
func matchScene(ruleScene, reqScene string) bool {
	if ruleScene == "" {
		return true
	}
	return strings.EqualFold(ruleScene, reqScene)
}

// sortRules 按 priority 降序排序。
func sortRules(rules []Rule) {
	sort.SliceStable(rules, func(i, j int) bool {
		return rules[i].Priority > rules[j].Priority
	})
}

// ============ 默认成本评估器 ============

// defaultCostEvaluator 默认成本评估器。
//
// 评分公式：score = inputPricePerM * (1 - latencyWeight) + avgLatencyMs * latencyWeight * 0.001
// 分数越低越优（低价 + 低延迟）。
type defaultCostEvaluator struct {
	latencyWeight float64 // 延迟权重（0~1）
}

func (e *defaultCostEvaluator) Select(candidates []Rule, costs map[string]*ProviderCost) (*Rule, string) {
	if len(candidates) == 0 {
		return nil, "no candidates"
	}
	best := candidates[0]
	bestScore := e.score(best.Provider, costs)
	for i := 1; i < len(candidates); i++ {
		s := e.score(candidates[i].Provider, costs)
		if s < bestScore {
			best = candidates[i]
			bestScore = s
		}
	}
	return &best, fmt.Sprintf("cost-selected: provider=%s score=%.4f (latencyWeight=%.2f)", best.Provider, bestScore, e.latencyWeight)
}

// score 计算 Provider 成本评分（越低越优）。
func (e *defaultCostEvaluator) score(providerName string, costs map[string]*ProviderCost) float64 {
	c, ok := costs[providerName]
	if !ok {
		return 1e9 // 未知成本，给大值
	}
	priceScore := c.InputPricePerM + c.OutputPricePerM
	latencyScore := c.AvgLatencyMs * 0.001
	return priceScore*(1-e.latencyWeight) + latencyScore*e.latencyWeight
}

// ============ 兼容旧 RouteRule ============

// FromRouteRule 从 gateway.RouteRule 转换为 routing.Rule。
//
// 便于从旧路由规则迁移到四维度路由引擎。
func FromRouteRule(model, provider, tenantID string, priority int) Rule {
	return Rule{
		ID:       fmt.Sprintf("legacy-%s-%s-%d", model, provider, priority),
		Model:    model,
		TenantID: tenantID,
		Provider: provider,
		Priority: priority,
		Weight:   1,
	}
}

// Now 返回当前时间（便于测试 mock）。
var Now = time.Now
