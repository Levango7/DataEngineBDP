package store

import (
	"errors"
	"testing"

	"github.com/Levango7/DataEngineBDP/karmada-api/internal/model"
)

// TestNewGormStore 构造函数应返回非 nil（仅验证接口契约，不依赖 CGO）。
func TestNewGormStore(t *testing.T) {
	// NewGormStore 需要 *gorm.DB，无法在无 CGO 环境下测试。
	// 此用例验证 mock store 满足 Store 接口契约。
	s := newMockStore()
	if s == nil {
		t.Fatal("expected non-nil store")
	}
}

// TestStore_CreatePropagationPolicy_Success 创建应成功。
func TestStore_CreatePropagationPolicy_Success(t *testing.T) {
	s := newMockStore()
	pp := &model.PropagationPolicy{
		Name:      "policy-1",
		Namespace: "default",
		TenantID:  "tenant-1",
		Spec:      `{"placement":{}}`,
	}
	if err := s.CreatePropagationPolicy(pp); err != nil {
		t.Fatalf("create: %v", err)
	}
	if pp.ID == 0 {
		t.Fatal("expected ID to be set after create")
	}
}

// TestStore_GetPropagationPolicy_Success 创建后应能按 tenant+ns+name 获取。
func TestStore_GetPropagationPolicy_Success(t *testing.T) {
	s := newMockStore()
	pp := &model.PropagationPolicy{
		Name: "policy-get", Namespace: "default", TenantID: "tenant-1", Spec: "{}",
	}
	if err := s.CreatePropagationPolicy(pp); err != nil {
		t.Fatalf("create: %v", err)
	}
	got, err := s.GetPropagationPolicy("tenant-1", "default", "policy-get")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if got.Name != "policy-get" {
		t.Fatalf("expected name=policy-get, got %q", got.Name)
	}
}

// TestStore_GetPropagationPolicy_NotFound 不存在的策略应返回 ErrNotFound。
func TestStore_GetPropagationPolicy_NotFound(t *testing.T) {
	s := newMockStore()
	_, err := s.GetPropagationPolicy("tenant-1", "default", "no-such-policy")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

// TestStore_GetPropagationPolicy_TenantIsolation 不同租户不应互相可见。
func TestStore_GetPropagationPolicy_TenantIsolation(t *testing.T) {
	s := newMockStore()
	pp := &model.PropagationPolicy{
		Name: "policy-iso", Namespace: "default", TenantID: "tenant-1", Spec: "{}",
	}
	if err := s.CreatePropagationPolicy(pp); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := s.GetPropagationPolicy("tenant-2", "default", "policy-iso"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound for cross-tenant, got %v", err)
	}
}

// TestStore_ListPropagationPolicies_Success 列表应返回正确数量。
func TestStore_ListPropagationPolicies_Success(t *testing.T) {
	s := newMockStore()
	for i := 0; i < 5; i++ {
		pp := &model.PropagationPolicy{
			Name:      "policy-" + string(rune('a'+i)),
			Namespace: "default", TenantID: "tenant-1", Spec: "{}",
		}
		if err := s.CreatePropagationPolicy(pp); err != nil {
			t.Fatalf("create %d: %v", i, err)
		}
	}
	pps, total, err := s.ListPropagationPolicies("tenant-1", "default", 10, 0)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 5 {
		t.Fatalf("expected total=5, got %d", total)
	}
	if len(pps) != 5 {
		t.Fatalf("expected 5 items, got %d", len(pps))
	}
}

// TestStore_ListPropagationPolicies_NamespaceFilter namespace 过滤应生效。
func TestStore_ListPropagationPolicies_NamespaceFilter(t *testing.T) {
	s := newMockStore()
	for _, ns := range []string{"default", "kube-system"} {
		pp := &model.PropagationPolicy{
			Name: "p-" + ns, Namespace: ns, TenantID: "tenant-1", Spec: "{}",
		}
		if err := s.CreatePropagationPolicy(pp); err != nil {
			t.Fatalf("create: %v", err)
		}
	}
	pps, total, err := s.ListPropagationPolicies("tenant-1", "kube-system", 10, 0)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 1 {
		t.Fatalf("expected total=1 for kube-system, got %d", total)
	}
	if len(pps) != 1 || pps[0].Namespace != "kube-system" {
		t.Fatalf("expected 1 kube-system item, got %v", pps)
	}
}

// TestStore_UpdatePropagationPolicy_Success 更新应持久化 spec 变更。
func TestStore_UpdatePropagationPolicy_Success(t *testing.T) {
	s := newMockStore()
	pp := &model.PropagationPolicy{
		Name: "policy-up", Namespace: "default", TenantID: "tenant-1", Spec: `{"old":true}`,
	}
	if err := s.CreatePropagationPolicy(pp); err != nil {
		t.Fatalf("create: %v", err)
	}
	pp.Spec = `{"new":true}`
	if err := s.UpdatePropagationPolicy(pp); err != nil {
		t.Fatalf("update: %v", err)
	}
	got, err := s.GetPropagationPolicy("tenant-1", "default", "policy-up")
	if err != nil {
		t.Fatalf("get after update: %v", err)
	}
	if got.Spec != `{"new":true}` {
		t.Fatalf("expected spec={\"new\":true}, got %q", got.Spec)
	}
}

// TestStore_DeletePropagationPolicy_Success 删除后应不可获取。
func TestStore_DeletePropagationPolicy_Success(t *testing.T) {
	s := newMockStore()
	pp := &model.PropagationPolicy{
		Name: "policy-del", Namespace: "default", TenantID: "tenant-1", Spec: "{}",
	}
	if err := s.CreatePropagationPolicy(pp); err != nil {
		t.Fatalf("create: %v", err)
	}
	if err := s.DeletePropagationPolicy("tenant-1", "default", "policy-del"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := s.GetPropagationPolicy("tenant-1", "default", "policy-del"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound after delete, got %v", err)
	}
}

// TestStore_DeletePropagationPolicy_NotFound 删除不存在的策略应返回 ErrNotFound。
func TestStore_DeletePropagationPolicy_NotFound(t *testing.T) {
	s := newMockStore()
	if err := s.DeletePropagationPolicy("tenant-1", "default", "no-such"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}
