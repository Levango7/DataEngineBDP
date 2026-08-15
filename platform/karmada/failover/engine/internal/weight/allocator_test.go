package weight

import (
	"testing"
)

// TestNewAllocator 构造函数应返回非 nil。
func TestNewAllocator(t *testing.T) {
	a := NewAllocator()
	if a == nil {
		t.Fatal("expected non-nil allocator")
	}
}

// TestAllocator_Allocate_Simple 简单权重分配应正确。
func TestAllocator_Allocate_Simple(t *testing.T) {
	a := NewAllocator()
	got := a.Allocate(10, map[string]int{"c1": 6, "c2": 4})
	if got["c1"]+got["c2"] != 10 {
		t.Fatalf("expected sum=10, got %d", got["c1"]+got["c2"])
	}
	if got["c1"] != 6 {
		t.Fatalf("expected c1=6, got %d", got["c1"])
	}
	if got["c2"] != 4 {
		t.Fatalf("expected c2=4, got %d", got["c2"])
	}
}

// TestAllocator_Allocate_ZeroTotal total<=0 应返回空 map。
func TestAllocator_Allocate_ZeroTotal(t *testing.T) {
	a := NewAllocator()
	got := a.Allocate(0, map[string]int{"c1": 1})
	if len(got) != 0 {
		t.Fatalf("expected empty for zero total, got %v", got)
	}
}

// TestAllocator_Allocate_EmptyWeights 空权重应返回空 map。
func TestAllocator_Allocate_EmptyWeights(t *testing.T) {
	a := NewAllocator()
	got := a.Allocate(10, map[string]int{})
	if len(got) != 0 {
		t.Fatalf("expected empty for empty weights, got %v", got)
	}
}

// TestAllocator_Allocate_AllZeroWeights 全 0 权重应返回空 map。
func TestAllocator_Allocate_AllZeroWeights(t *testing.T) {
	a := NewAllocator()
	got := a.Allocate(10, map[string]int{"c1": 0, "c2": 0})
	if len(got) != 0 {
		t.Fatalf("expected empty for all-zero weights, got %v", got)
	}
}

// TestAllocator_Allocate_Remainder 有余数时应正确分配且总和一致。
func TestAllocator_Allocate_Remainder(t *testing.T) {
	a := NewAllocator()
	got := a.Allocate(10, map[string]int{"c1": 1, "c2": 1, "c3": 1})
	sum := got["c1"] + got["c2"] + got["c3"]
	if sum != 10 {
		t.Fatalf("expected sum=10, got %d", sum)
	}
}

// TestAllocator_Allocate_SingleCluster 单集群应分配全部。
func TestAllocator_Allocate_SingleCluster(t *testing.T) {
	a := NewAllocator()
	got := a.Allocate(7, map[string]int{"c1": 100})
	if got["c1"] != 7 {
		t.Fatalf("expected c1=7, got %d", got["c1"])
	}
}

// TestAllocator_Allocate_NegativeWeightIgnored 负权重应被忽略。
func TestAllocator_Allocate_NegativeWeightIgnored(t *testing.T) {
	a := NewAllocator()
	got := a.Allocate(10, map[string]int{"c1": 5, "c2": -1})
	if _, ok := got["c2"]; ok {
		t.Fatalf("expected c2 with negative weight to be ignored, got %v", got)
	}
	if got["c1"] != 10 {
		t.Fatalf("expected c1=10, got %d", got["c1"])
	}
}

// TestAllocator_AllocateWithCapacity_NoCapacityConstraint 无容量约束时应等同 Allocate。
func TestAllocator_AllocateWithCapacity_NoCapacityConstraint(t *testing.T) {
	a := NewAllocator()
	got := a.AllocateWithCapacity(10, map[string]int{"c1": 6, "c2": 4}, nil)
	if got["c1"]+got["c2"] != 10 {
		t.Fatalf("expected sum=10, got %d", got["c1"]+got["c2"])
	}
}

// TestAllocator_AllocateWithCapacity_CapacityConstraint 容量约束应生效。
func TestAllocator_AllocateWithCapacity_CapacityConstraint(t *testing.T) {
	a := NewAllocator()
	// c1 权重 8 应分 8 个，但容量只有 5，超出 3 个应分给 c2。
	got := a.AllocateWithCapacity(10, map[string]int{"c1": 8, "c2": 2}, map[string]int{"c1": 5, "c2": 10})
	if got["c1"] > 5 {
		t.Fatalf("expected c1<=5 due to capacity, got %d", got["c1"])
	}
}

// TestAllocator_AllocateForFailover 故障迁移应将源集群权重置 0。
func TestAllocator_AllocateForFailover(t *testing.T) {
	a := NewAllocator()
	allocation, newWeights := a.AllocateForFailover(
		10,
		map[string]int{"c1": 5, "c2": 5},
		"c1", "c2",
	)
	if newWeights["c1"] != 0 {
		t.Fatalf("expected source weight=0, got %d", newWeights["c1"])
	}
	if allocation["c1"] != 0 {
		t.Fatalf("expected source allocation=0, got %d", allocation["c1"])
	}
	if allocation["c2"] != 10 {
		t.Fatalf("expected target allocation=10, got %d", allocation["c2"])
	}
}

// TestAllocator_AllocateForFailover_ZeroTargetWeight 目标集群原权重 0 应被提升。
func TestAllocator_AllocateForFailover_ZeroTargetWeight(t *testing.T) {
	a := NewAllocator()
	_, newWeights := a.AllocateForFailover(
		10,
		map[string]int{"c1": 5, "c2": 0},
		"c1", "c2",
	)
	if newWeights["c2"] == 0 {
		t.Fatal("expected target weight to be promoted from 0")
	}
}

// TestAllocator_AdjustWeights_PositiveDelta 正 delta 应提升权重。
func TestAllocator_AdjustWeights_PositiveDelta(t *testing.T) {
	a := NewAllocator()
	got := a.AdjustWeights(map[string]int{"c1": 5}, map[string]int{"c1": 3})
	if got["c1"] != 8 {
		t.Fatalf("expected c1=8, got %d", got["c1"])
	}
}

// TestAllocator_AdjustWeights_NegativeDelta 负 delta 应降低权重，下限为 0。
func TestAllocator_AdjustWeights_NegativeDelta(t *testing.T) {
	a := NewAllocator()
	got := a.AdjustWeights(map[string]int{"c1": 5}, map[string]int{"c1": -3})
	if got["c1"] != 2 {
		t.Fatalf("expected c1=2, got %d", got["c1"])
	}
	// 超出下限应截断为 0。
	got = a.AdjustWeights(map[string]int{"c1": 5}, map[string]int{"c1": -10})
	if got["c1"] != 0 {
		t.Fatalf("expected c1=0 (clamped), got %d", got["c1"])
	}
}

// TestAllocator_ToWeightAllocation 转换函数应正确填充字段。
func TestAllocator_ToWeightAllocation(t *testing.T) {
	a := NewAllocator()
	got := a.ToWeightAllocation(
		"p1", "dep/nginx", 10,
		map[string]int{"c1": 6, "c2": 4},
		map[string]int{"c1": 60, "c2": 40},
		"initial",
	)
	if got.PolicyName != "p1" {
		t.Fatalf("expected policyName=p1, got %q", got.PolicyName)
	}
	if got.Workload != "dep/nginx" {
		t.Fatalf("expected workload=dep/nginx, got %q", got.Workload)
	}
	if got.TotalReplicas != 10 {
		t.Fatalf("expected totalReplicas=10, got %d", got.TotalReplicas)
	}
	if got.Reason != "initial" {
		t.Fatalf("expected reason=initial, got %q", got.Reason)
	}
}

// TestAllocator_ValidateAllocation_Valid 合法分配应返回 nil。
func TestAllocator_ValidateAllocation_Valid(t *testing.T) {
	a := NewAllocator()
	if err := a.ValidateAllocation(10, map[string]int{"c1": 6, "c2": 4}); err != nil {
		t.Fatalf("expected nil, got %v", err)
	}
}

// TestAllocator_ValidateAllocation_Negative 负分配应返回错误。
func TestAllocator_ValidateAllocation_Negative(t *testing.T) {
	a := NewAllocator()
	err := a.ValidateAllocation(10, map[string]int{"c1": -1, "c2": 11})
	if err == nil {
		t.Fatal("expected error for negative allocation")
	}
}

// TestAllocator_ValidateAllocation_SumMismatch 总和不一致应返回错误。
func TestAllocator_ValidateAllocation_SumMismatch(t *testing.T) {
	a := NewAllocator()
	err := a.ValidateAllocation(10, map[string]int{"c1": 5, "c2": 5, "c3": 1})
	if err == nil {
		t.Fatal("expected error for sum mismatch")
	}
}

// TestAllocationError_Error_WithCluster 带 cluster 的错误消息应正确。
func TestAllocationError_Error_WithCluster(t *testing.T) {
	e := &AllocationError{Cluster: "c1", Reason: "negative"}
	if got := e.Error(); got != "allocation error for cluster c1: negative" {
		t.Fatalf("unexpected error message: %q", got)
	}
}

// TestAllocationError_Error_WithoutCluster 不带 cluster 的错误消息应正确。
func TestAllocationError_Error_WithoutCluster(t *testing.T) {
	e := &AllocationError{Reason: "sum mismatch"}
	if got := e.Error(); got != "allocation error: sum mismatch" {
		t.Fatalf("unexpected error message: %q", got)
	}
}
