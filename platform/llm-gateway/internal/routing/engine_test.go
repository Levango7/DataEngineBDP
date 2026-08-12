package routing

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// TestRouteByModel 测试按模型维度路由。
func TestRouteByModel(t *testing.T) {
	e := NewEngine()
	e.AddRule(Rule{ID: "r1", Model: "gpt-4", Provider: "openai", Priority: 10})
	e.AddRule(Rule{ID: "r2", Model: "claude", Provider: "anthropic", Priority: 10})

	d, err := e.Route("gpt-4", "", "")
	assert.NoError(t, err)
	assert.Equal(t, "openai", d.Provider)

	d, err = e.Route("claude", "", "")
	assert.NoError(t, err)
	assert.Equal(t, "anthropic", d.Provider)
}

// TestRouteByTenant 测试按租户维度路由（租户级优先）。
func TestRouteByTenant(t *testing.T) {
	e := NewEngine()
	// 全局规则
	e.AddRule(Rule{ID: "global", Model: "gpt-4", Provider: "openai", Priority: 5})
	// 租户级规则（更高优先级）
	e.AddRule(Rule{ID: "tenant-vip", Model: "gpt-4", TenantID: "vip-tenant", Provider: "openai-premium", Priority: 10})

	// 普通租户 → 全局规则
	d, err := e.Route("gpt-4", "normal-tenant", "")
	assert.NoError(t, err)
	assert.Equal(t, "openai", d.Provider)

	// VIP 租户 → 租户级规则
	d, err = e.Route("gpt-4", "vip-tenant", "")
	assert.NoError(t, err)
	assert.Equal(t, "openai-premium", d.Provider)
}

// TestRouteByScene 测试按场景维度路由。
func TestRouteByScene(t *testing.T) {
	e := NewEngine()
	e.AddRule(Rule{ID: "chat", Model: "gpt-4", Scene: "chat", Provider: "openai", Priority: 10})
	e.AddRule(Rule{ID: "finetune", Model: "gpt-4", Scene: "finetune", Provider: "openai-finetune", Priority: 10})
	e.AddRule(Rule{ID: "eval", Model: "gpt-4", Scene: "eval", Provider: "openai-eval", Priority: 10})

	d, err := e.Route("gpt-4", "", "chat")
	assert.NoError(t, err)
	assert.Equal(t, "openai", d.Provider)

	d, err = e.Route("gpt-4", "", "finetune")
	assert.NoError(t, err)
	assert.Equal(t, "openai-finetune", d.Provider)

	d, err = e.Route("gpt-4", "", "eval")
	assert.NoError(t, err)
	assert.Equal(t, "openai-eval", d.Provider)
}

// TestRouteByCost 测试按成本维度路由。
func TestRouteByCost(t *testing.T) {
	e := NewEngine()
	// 两个同优先级候选
	e.AddRule(Rule{ID: "cheap", Model: "gpt-4", Provider: "provider-a", Priority: 10})
	e.AddRule(Rule{ID: "expensive", Model: "gpt-4", Provider: "provider-b", Priority: 10})

	// provider-a 更便宜
	e.SetProviderCost(&ProviderCost{Provider: "provider-a", InputPricePerM: 1.0, OutputPricePerM: 2.0, AvgLatencyMs: 100})
	e.SetProviderCost(&ProviderCost{Provider: "provider-b", InputPricePerM: 5.0, OutputPricePerM: 10.0, AvgLatencyMs: 200})

	d, err := e.Route("gpt-4", "", "")
	assert.NoError(t, err)
	assert.Equal(t, "provider-a", d.Provider)
	assert.Contains(t, d.Reason, "cost-selected")
}

// TestRouteFourDimensions 测试四维度综合路由。
func TestRouteFourDimensions(t *testing.T) {
	e := NewEngine()
	// 全局对话规则
	e.AddRule(Rule{ID: "global-chat", Model: "gpt-4", Scene: "chat", Provider: "openai", Priority: 5})
	// VIP 租户对话规则
	e.AddRule(Rule{ID: "vip-chat", Model: "gpt-4", TenantID: "vip", Scene: "chat", Provider: "openai-vip", Priority: 10})
	// VIP 租户微调规则
	e.AddRule(Rule{ID: "vip-finetune", Model: "gpt-4", TenantID: "vip", Scene: "finetune", Provider: "openai-finetune", Priority: 10})

	// VIP 租户对话 → vip-chat
	d, err := e.Route("gpt-4", "vip", "chat")
	assert.NoError(t, err)
	assert.Equal(t, "openai-vip", d.Provider)

	// VIP 租户微调 → vip-finetune
	d, err = e.Route("gpt-4", "vip", "finetune")
	assert.NoError(t, err)
	assert.Equal(t, "openai-finetune", d.Provider)

	// 普通租户对话 → global-chat
	d, err = e.Route("gpt-4", "normal", "chat")
	assert.NoError(t, err)
	assert.Equal(t, "openai", d.Provider)
}

// TestRouteDefaultFallback 测试默认 Provider 回退。
func TestRouteDefaultFallback(t *testing.T) {
	e := NewEngine()
	e.SetDefault("default-provider")

	d, err := e.Route("unknown-model", "", "")
	assert.NoError(t, err)
	assert.Equal(t, "default-provider", d.Provider)
	assert.Equal(t, "default fallback", d.Reason)
}

// TestRouteNotFound 测试无匹配规则且无默认 Provider。
func TestRouteNotFound(t *testing.T) {
	e := NewEngine()
	_, err := e.Route("unknown-model", "", "")
	assert.Error(t, err)
}

// TestRouteWildcardModel 测试通配模型名。
func TestRouteWildcardModel(t *testing.T) {
	e := NewEngine()
	e.AddRule(Rule{ID: "wildcard", Model: "*", Provider: "fallback", Priority: 1})

	d, err := e.Route("any-model", "", "")
	assert.NoError(t, err)
	assert.Equal(t, "fallback", d.Provider)
}

// TestCheckQuota 测试租户配额检查。
func TestCheckQuota(t *testing.T) {
	e := NewEngine()
	e.SetTenantQuota(&TenantQuota{
		TenantID:   "limited",
		TPMLimit:   1000,
		RPMLimit:   10,
		DailyLimit: 10000,
	})

	// 未超限
	err := e.CheckQuota("limited", 100, 500, 5, 5000)
	assert.NoError(t, err)

	// TPM 超限
	err = e.CheckQuota("limited", 600, 500, 5, 5000)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "TPM quota exceeded")

	// RPM 超限
	err = e.CheckQuota("limited", 100, 500, 10, 5000)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "RPM quota exceeded")

	// Daily 超限（确保 TPM 不超限：current=500 + estimated=6000，TPM limit=10000）
	e.SetTenantQuota(&TenantQuota{
		TenantID:   "limited",
		TPMLimit:   10000,
		RPMLimit:   100,
		DailyLimit: 10000,
	})
	err = e.CheckQuota("limited", 6000, 500, 5, 5000)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "daily quota exceeded")

	// 无配额限制的租户
	err = e.CheckQuota("unlimited", 999999, 999999, 999999, 999999)
	assert.NoError(t, err)
}

// TestRemoveRule 测试移除规则。
func TestRemoveRule(t *testing.T) {
	e := NewEngine()
	e.AddRule(Rule{ID: "r1", Model: "gpt-4", Provider: "openai", Priority: 10})

	assert.True(t, e.RemoveRule("r1"))
	assert.False(t, e.RemoveRule("r1"))

	assert.Len(t, e.Rules(), 0)
}

// TestFromRouteRule 测试从旧 RouteRule 转换。
func TestFromRouteRule(t *testing.T) {
	r := FromRouteRule("gpt-4", "openai", "tenant1", 10)
	assert.Equal(t, "gpt-4", r.Model)
	assert.Equal(t, "openai", r.Provider)
	assert.Equal(t, "tenant1", r.TenantID)
	assert.Equal(t, 10, r.Priority)
}
