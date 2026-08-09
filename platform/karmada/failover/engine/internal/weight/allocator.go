package weight

// 副本权重分配器。
//
// 按集群权重 + 容量上限分配副本数：
//   1. 按权重比例计算各集群期望副本数（最大余数法）
//   2. 受各集群 maxReplicas - currentReplicas 容量上限约束
//   3. 超出容量的副本重新分配到其他可用集群
//
// 支持：
//   - 静态权重（StaticWeightList）
//   - 动态调整（运行时修改权重）
//   - 故障迁移（源集群权重置 0，目标集群权重提升）

import (
	"fmt"
	"sort"

	"github.com/Levango7/DataEngineBDP/failover-engine/internal/model"
)

// Allocator 副本权重分配器。
type Allocator struct{}

// NewAllocator 创建副本权重分配器。
func NewAllocator() *Allocator {
	return &Allocator{}
}

// Allocate 按权重分配副本（不考虑容量上限）。
//
// 使用最大余数法保证 sum(allocation) == total。
func (a *Allocator) Allocate(total int, weights map[string]int) map[string]int {
	if total <= 0 || len(weights) == 0 {
		return map[string]int{}
	}

	totalWeight := 0
	for _, w := range weights {
		if w > 0 {
			totalWeight += w
		}
	}
	if totalWeight == 0 {
		return map[string]int{}
	}

	// 按集群名稳定排序。
	keys := make([]string, 0, len(weights))
	for k, w := range weights {
		if w > 0 {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)

	result := make(map[string]int, len(keys))
	allocated := 0
	remainders := make(map[string]float64, len(keys))

	for _, k := range keys {
		exact := float64(total) * float64(weights[k]) / float64(totalWeight)
		floor := int(exact)
		result[k] = floor
		allocated += floor
		remainders[k] = exact - float64(floor)
	}

	// 把剩余副本按余数大小依次分配。
	remaining := total - allocated
	for remaining > 0 {
		best := keys[0]
		for _, k := range keys[1:] {
			if remainders[k] > remainders[best] {
				best = k
			}
		}
		result[best]++
		remainders[best] = -1
		remaining--
	}

	return result
}

// AllocateWithCapacity 按权重分配副本，受容量上限约束。
//
// capacities[k] 表示集群 k 的可用容量（maxReplicas - currentReplicas）。
// 超出容量的副本重新分配到其他可用集群。
func (a *Allocator) AllocateWithCapacity(
	total int,
	weights map[string]int,
	capacities map[string]int,
) map[string]int {
	// 第一轮：按权重分配。
	allocation := a.Allocate(total, weights)

	// 第二轮：检查容量约束，超出部分重新分配。
	for {
		overflow := 0
		for k, v := range allocation {
			cap, ok := capacities[k]
			if ok && v > cap {
				overflow += v - cap
				allocation[k] = cap
			}
		}
		if overflow == 0 {
			break
		}

		// 把溢出的副本分配到还有容量的集群。
		remaining := overflow
		for k := range allocation {
			if remaining <= 0 {
				break
			}
			cap, ok := capacities[k]
			if !ok {
				continue
			}
			current := allocation[k]
			available := cap - current
			if available > 0 {
				add := available
				if add > remaining {
					add = remaining
				}
				allocation[k] += add
				remaining -= add
			}
		}
		// 如果还有剩余（所有集群都满），无法分配，丢弃。
		if remaining > 0 {
			break
		}
	}

	return allocation
}

// AllocateForFailover 故障迁移场景的副本重分配。
//
// 将源集群的副本迁移到目标集群：
//   - 源集群权重置 0
//   - 目标集群权重提升（保持原权重或加倍）
//   - 其他集群权重不变
func (a *Allocator) AllocateForFailover(
	total int,
	weights map[string]int,
	sourceCluster, targetCluster string,
) (map[string]int, map[string]int) {
	// 复制权重，避免修改原 map。
	newWeights := make(map[string]int, len(weights))
	for k, v := range weights {
		newWeights[k] = v
	}

	// 源集群权重置 0。
	newWeights[sourceCluster] = 0

	// 目标集群权重提升（如果原权重为 0，设为源集群原权重）。
	if newWeights[targetCluster] == 0 {
		newWeights[targetCluster] = weights[sourceCluster]
		if newWeights[targetCluster] == 0 {
			newWeights[targetCluster] = 1
		}
	}

	allocation := a.Allocate(total, newWeights)
	return allocation, newWeights
}

// AdjustWeights 动态调整权重。
//
// 输入当前权重与调整项（cluster → delta），返回新权重。
// delta > 0 表示提升权重，delta < 0 表示降低权重，权重下限为 0。
func (a *Allocator) AdjustWeights(
	current map[string]int,
	adjustments map[string]int,
) map[string]int {
	result := make(map[string]int, len(current))
	for k, v := range current {
		result[k] = v
	}
	for k, delta := range adjustments {
		result[k] += delta
		if result[k] < 0 {
			result[k] = 0
		}
	}
	return result
}

// ToWeightAllocation 转换为 WeightAllocation 结构。
func (a *Allocator) ToWeightAllocation(
	policyName, workload string,
	total int,
	allocation, weights map[string]int,
	reason string,
) *model.WeightAllocation {
	return &model.WeightAllocation{
		PolicyName:    policyName,
		Workload:      workload,
		TotalReplicas: total,
		Allocation:    allocation,
		Weights:       weights,
		Reason:        reason,
	}
}

// ValidateAllocation 校验分配方案是否合法。
//
// 检查：
//   - sum(allocation) == total
//   - 所有 allocation[k] >= 0
func (a *Allocator) ValidateAllocation(total int, allocation map[string]int) error {
	sum := 0
	for k, v := range allocation {
		if v < 0 {
			return &AllocationError{
				Cluster: k,
				Reason:  "negative allocation",
			}
		}
		sum += v
	}
	if sum != total {
		return &AllocationError{
			Reason: fmt.Sprintf("sum %d != total %d", sum, total),
		}
	}
	return nil
}

// AllocationError 分配校验错误。
type AllocationError struct {
	Cluster string
	Reason  string
}

// Error 实现 error 接口。
func (e *AllocationError) Error() string {
	if e.Cluster != "" {
		return "allocation error for cluster " + e.Cluster + ": " + e.Reason
	}
	return "allocation error: " + e.Reason
}
