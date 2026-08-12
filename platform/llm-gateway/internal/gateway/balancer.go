package gateway

import (
	"fmt"
	"sync"
	"sync/atomic"
)

// ============ 负载均衡 ============
//
// 同一模型的多个实例间按权重轮询（Weighted Round-Robin）。
// 当前实现：以 Provider 名为 key，同一 name 下可注册多个实例（不同权重）。
// 简化版：每个 name 当前只对应一个实例，Pick 直接返回该 name；
// 扩展点：instances[name] 可扩展为多实例列表，按权重轮询。

// instance 单个 Provider 实例的负载均衡元数据。
type instance struct {
	name   string
	weight int
	// 当前轮询计数器
	counter atomic.Int64
}

// LoadBalancer 负载均衡器。
type LoadBalancer struct {
	mu        sync.RWMutex
	instances map[string]*instance // name -> instance
}

// NewLoadBalancer 构造负载均衡器。
func NewLoadBalancer() *LoadBalancer {
	return &LoadBalancer{
		instances: make(map[string]*instance),
	}
}

// Add 添加一个 Provider 实例。
func (lb *LoadBalancer) Add(name string, weight int) {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	if weight <= 0 {
		weight = 1
	}
	lb.instances[name] = &instance{name: name, weight: weight}
}

// Remove 移除一个 Provider 实例。
func (lb *LoadBalancer) Remove(name string) {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	delete(lb.instances, name)
}

// Pick 选一个实例。当前单实例实现：直接返回 name。
// 多实例扩展时，此处按权重轮询选择。
func (lb *LoadBalancer) Pick(name string) (string, error) {
	lb.mu.RLock()
	defer lb.mu.RUnlock()
	ins, ok := lb.instances[name]
	if !ok {
		return "", fmt.Errorf("%w: %s", errNoInstance, name)
	}
	ins.counter.Add(1)
	return ins.name, nil
}

// Weights 返回所有实例的权重快照。
func (lb *LoadBalancer) Weights() map[string]int {
	lb.mu.RLock()
	defer lb.mu.RUnlock()
	out := make(map[string]int, len(lb.instances))
	for n, ins := range lb.instances {
		out[n] = ins.weight
	}
	return out
}

// Count 返回指定实例的累计选取次数。
func (lb *LoadBalancer) Count(name string) int64 {
	lb.mu.RLock()
	defer lb.mu.RUnlock()
	ins, ok := lb.instances[name]
	if !ok {
		return 0
	}
	return ins.counter.Load()
}

// errNoInstance 无可用实例。
var errNoInstance = fmt.Errorf("no available instance")
