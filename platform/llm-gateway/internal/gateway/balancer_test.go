package gateway

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// ============ 负载均衡器测试 ============

// TestLoadBalancer_AddPick 验证添加实例后可 Pick。
func TestLoadBalancer_AddPick(t *testing.T) {
	lb := NewLoadBalancer()
	lb.Add("openai", 1)

	name, err := lb.Pick("openai")
	assert.NoError(t, err)
	assert.Equal(t, "openai", name)
}

// TestLoadBalancer_PickNotFound 验证未注册实例 Pick 报错。
func TestLoadBalancer_PickNotFound(t *testing.T) {
	lb := NewLoadBalancer()
	_, err := lb.Pick("unknown")
	assert.Error(t, err)
}

// TestLoadBalancer_Remove 验证移除实例。
func TestLoadBalancer_Remove(t *testing.T) {
	lb := NewLoadBalancer()
	lb.Add("openai", 1)
	lb.Remove("openai")

	_, err := lb.Pick("openai")
	assert.Error(t, err)
}

// TestLoadBalancer_DefaultWeight 验证权重 <= 0 时默认为 1。
func TestLoadBalancer_DefaultWeight(t *testing.T) {
	lb := NewLoadBalancer()
	lb.Add("p1", 0)
	lb.Add("p2", -5)

	weights := lb.Weights()
	assert.Equal(t, 1, weights["p1"])
	assert.Equal(t, 1, weights["p2"])
}

// TestLoadBalancer_Count 验证选取计数。
func TestLoadBalancer_Count(t *testing.T) {
	lb := NewLoadBalancer()
	lb.Add("openai", 1)

	for i := 0; i < 5; i++ {
		_, _ = lb.Pick("openai")
	}
	assert.Equal(t, int64(5), lb.Count("openai"))
	assert.Equal(t, int64(0), lb.Count("unknown"))
}

// TestLoadBalancer_Weights 验证权重快照。
func TestLoadBalancer_Weights(t *testing.T) {
	lb := NewLoadBalancer()
	lb.Add("a", 3)
	lb.Add("b", 7)

	w := lb.Weights()
	assert.Equal(t, 3, w["a"])
	assert.Equal(t, 7, w["b"])
}
