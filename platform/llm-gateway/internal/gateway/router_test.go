package gateway

import (
	"testing"

	"github.com/stretchr/testify/assert"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
)

// ============ 路由器测试 ============

// TestRouter_GlobalRule 验证全局规则匹配。
func TestRouter_GlobalRule(t *testing.T) {
	r := NewRouter()
	r.Add(RouteRule{Model: "gpt-4", Provider: "openai", Priority: 1})

	name, err := r.Route("gpt-4", "tenant-a")
	assert.NoError(t, err)
	assert.Equal(t, "openai", name)
}

// TestRouter_TenantRulePrecedence 验证租户级规则优先于全局规则。
func TestRouter_TenantRulePrecedence(t *testing.T) {
	r := NewRouter()
	r.Add(RouteRule{Model: "gpt-4", Provider: "openai", Priority: 1})
	r.Add(RouteRule{Model: "gpt-4", Provider: "wenxin", TenantID: "vip-tenant", Priority: 10})

	// 普通租户走全局规则
	name, err := r.Route("gpt-4", "normal-tenant")
	assert.NoError(t, err)
	assert.Equal(t, "openai", name)

	// VIP 租户走租户级规则
	name, err = r.Route("gpt-4", "vip-tenant")
	assert.NoError(t, err)
	assert.Equal(t, "wenxin", name)
}

// TestRouter_PriorityOrder 验证高优先级规则优先匹配。
func TestRouter_PriorityOrder(t *testing.T) {
	r := NewRouter()
	r.Add(RouteRule{Model: "gpt-4", Provider: "low", Priority: 1})
	r.Add(RouteRule{Model: "gpt-4", Provider: "high", Priority: 100})

	name, err := r.Route("gpt-4", "")
	assert.NoError(t, err)
	assert.Equal(t, "high", name)
}

// TestRouter_DefaultProvider 验证默认 Provider 兜底。
func TestRouter_DefaultProvider(t *testing.T) {
	r := NewRouter()
	r.SetDefault("mock")

	name, err := r.Route("unknown-model", "tenant")
	assert.NoError(t, err)
	assert.Equal(t, "mock", name)
}

// TestRouter_NotFound 验证无匹配规则返回 ErrModelNotFound。
func TestRouter_NotFound(t *testing.T) {
	r := NewRouter()
	_, err := r.Route("unknown-model", "tenant")
	assert.ErrorIs(t, err, provider.ErrModelNotFound)
}

// TestRouter_RemoveProvider 验证移除 Provider 关联规则。
func TestRouter_RemoveProvider(t *testing.T) {
	r := NewRouter()
	r.Add(RouteRule{Model: "gpt-4", Provider: "openai"})
	r.Add(RouteRule{Model: "gpt-3.5", Provider: "openai"})
	r.Add(RouteRule{Model: "wenxin-model", Provider: "wenxin"})

	r.RemoveProvider("openai")

	// openai 的规则应被移除
	_, err := r.Route("gpt-4", "")
	assert.ErrorIs(t, err, provider.ErrModelNotFound)
	_, err = r.Route("gpt-3.5", "")
	assert.ErrorIs(t, err, provider.ErrModelNotFound)

	// wenxin 的规则应保留
	name, err := r.Route("wenxin-model", "")
	assert.NoError(t, err)
	assert.Equal(t, "wenxin", name)
}

// TestRouter_Rules 验证规则快照。
func TestRouter_Rules(t *testing.T) {
	r := NewRouter()
	r.Add(RouteRule{Model: "a", Provider: "p1", Priority: 1})
	r.Add(RouteRule{Model: "b", Provider: "p2", Priority: 2})

	rules := r.Rules()
	assert.Len(t, rules, 2)
	// 按 priority 降序
	assert.Equal(t, "b", rules[0].Model)
	assert.Equal(t, "a", rules[1].Model)
}
