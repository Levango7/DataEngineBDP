package store

import (
	"errors"
	"sync"

	"github.com/Levango7/DataEngineBDP/karmada-api/internal/model"
)

// mockStore 内存实现 store.Store 接口，用于测试不依赖 CGO/sqlite。
type mockStore struct {
	mu  sync.Mutex
	db  map[string]*model.PropagationPolicy // key = tenant|ns|name
	seq uint
}

// newMockStore 创建内存 mock store。
func newMockStore() Store {
	return &mockStore{db: make(map[string]*model.PropagationPolicy)}
}

func (m *mockStore) key(tenantID, namespace, name string) string {
	return tenantID + "|" + namespace + "|" + name
}

// CreatePropagationPolicy 创建策略。
func (m *mockStore) CreatePropagationPolicy(pp *model.PropagationPolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.key(pp.TenantID, pp.Namespace, pp.Name)
	if _, exists := m.db[k]; exists {
		return errors.New("duplicate")
	}
	m.seq++
	pp.ID = m.seq
	cp := *pp
	m.db[k] = &cp
	return nil
}

// GetPropagationPolicy 获取策略。
func (m *mockStore) GetPropagationPolicy(tenantID, namespace, name string) (*model.PropagationPolicy, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.key(tenantID, namespace, name)
	pp, ok := m.db[k]
	if !ok {
		return nil, ErrNotFound
	}
	cp := *pp
	return &cp, nil
}

// ListPropagationPolicies 列出策略。
func (m *mockStore) ListPropagationPolicies(tenantID, namespace string, limit, offset int) ([]model.PropagationPolicy, int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var all []model.PropagationPolicy
	for _, pp := range m.db {
		if pp.TenantID != tenantID {
			continue
		}
		if namespace != "" && pp.Namespace != namespace {
			continue
		}
		all = append(all, *pp)
	}
	total := int64(len(all))
	if offset >= len(all) {
		return nil, total, nil
	}
	end := offset + limit
	if end > len(all) || limit <= 0 {
		end = len(all)
	}
	if limit <= 0 {
		return all[offset:], total, nil
	}
	return all[offset:end], total, nil
}

// UpdatePropagationPolicy 更新策略。
func (m *mockStore) UpdatePropagationPolicy(pp *model.PropagationPolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.key(pp.TenantID, pp.Namespace, pp.Name)
	if _, ok := m.db[k]; !ok {
		return ErrNotFound
	}
	cp := *pp
	m.db[k] = &cp
	return nil
}

// DeletePropagationPolicy 删除策略。
func (m *mockStore) DeletePropagationPolicy(tenantID, namespace, name string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.key(tenantID, namespace, name)
	if _, ok := m.db[k]; !ok {
		return ErrNotFound
	}
	delete(m.db, k)
	return nil
}
