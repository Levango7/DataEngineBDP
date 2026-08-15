package handler

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/failover-api/internal/model"
	"github.com/Levango7/DataEngineBDP/failover-api/internal/store"
)

// init 设置 Gin 测试模式。
func init() {
	gin.SetMode(gin.TestMode)
}

// mockStore 内存实现 store.Store 接口，用于测试不依赖 CGO/sqlite。
type mockStore struct {
	mu sync.Mutex

	op       map[string]*model.OverridePolicy
	opSeq    uint
	events   map[string]*model.FailoverEvent
	evSeq    uint
	health   []model.ClusterHealthRecord
	hSeq     uint
	plans    map[string]*model.ReplicaWeightPlan
	plSeq    uint
	policies map[string]*model.FailoverPolicy
	fpSeq    uint
}

func newMockStore() *mockStore {
	return &mockStore{
		op:       make(map[string]*model.OverridePolicy),
		events:   make(map[string]*model.FailoverEvent),
		plans:    make(map[string]*model.ReplicaWeightPlan),
		policies: make(map[string]*model.FailoverPolicy),
	}
}

// 确保 mockStore 实现 store.Store 接口。
var _ store.Store = (*mockStore)(nil)

func (m *mockStore) opKey(t, ns, n string) string { return t + "|" + ns + "|" + n }
func (m *mockStore) evKey(t, e string) string     { return t + "|" + e }
func (m *mockStore) plKey(t, p string) string     { return t + "|" + p }
func (m *mockStore) fpKey(t, ns, n string) string { return t + "|" + ns + "|" + n }

// OverridePolicy CRUD
func (m *mockStore) CreateOverridePolicy(op *model.OverridePolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.opKey(op.TenantID, op.Namespace, op.Name)
	if _, ok := m.op[k]; ok {
		return errors.New("duplicate")
	}
	m.opSeq++
	op.ID = m.opSeq
	cp := *op
	m.op[k] = &cp
	return nil
}
func (m *mockStore) GetOverridePolicy(t, ns, n string) (*model.OverridePolicy, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	op, ok := m.op[m.opKey(t, ns, n)]
	if !ok {
		return nil, store.ErrNotFound
	}
	cp := *op
	return &cp, nil
}
func (m *mockStore) ListOverridePolicies(t, ns string, limit, offset int) ([]model.OverridePolicy, int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var all []model.OverridePolicy
	for _, op := range m.op {
		if op.TenantID != t {
			continue
		}
		if ns != "" && op.Namespace != ns {
			continue
		}
		all = append(all, *op)
	}
	total := int64(len(all))
	if offset >= len(all) {
		return nil, total, nil
	}
	end := offset + limit
	if limit <= 0 || end > len(all) {
		end = len(all)
	}
	if limit <= 0 {
		return all[offset:], total, nil
	}
	return all[offset:end], total, nil
}
func (m *mockStore) UpdateOverridePolicy(op *model.OverridePolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.opKey(op.TenantID, op.Namespace, op.Name)
	if _, ok := m.op[k]; !ok {
		return store.ErrNotFound
	}
	cp := *op
	m.op[k] = &cp
	return nil
}
func (m *mockStore) DeleteOverridePolicy(t, ns, n string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.opKey(t, ns, n)
	if _, ok := m.op[k]; !ok {
		return store.ErrNotFound
	}
	delete(m.op, k)
	return nil
}

// FailoverEvent CRUD
func (m *mockStore) CreateFailoverEvent(e *model.FailoverEvent) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.evKey(e.TenantID, e.EventID)
	if _, ok := m.events[k]; ok {
		return errors.New("duplicate")
	}
	m.evSeq++
	e.ID = m.evSeq
	cp := *e
	m.events[k] = &cp
	return nil
}
func (m *mockStore) GetFailoverEvent(t, e string) (*model.FailoverEvent, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	ev, ok := m.events[m.evKey(t, e)]
	if !ok {
		return nil, store.ErrNotFound
	}
	cp := *ev
	return &cp, nil
}
func (m *mockStore) ListFailoverEvents(t string, limit, offset int) ([]model.FailoverEvent, int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var all []model.FailoverEvent
	for _, ev := range m.events {
		if ev.TenantID != t {
			continue
		}
		all = append(all, *ev)
	}
	total := int64(len(all))
	if offset >= len(all) {
		return nil, total, nil
	}
	end := offset + limit
	if limit <= 0 || end > len(all) {
		end = len(all)
	}
	if limit <= 0 {
		return all[offset:], total, nil
	}
	return all[offset:end], total, nil
}
func (m *mockStore) UpdateFailoverEvent(e *model.FailoverEvent) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.evKey(e.TenantID, e.EventID)
	if _, ok := m.events[k]; !ok {
		return store.ErrNotFound
	}
	cp := *e
	m.events[k] = &cp
	return nil
}

// ClusterHealthRecord CRUD
func (m *mockStore) CreateClusterHealthRecord(r *model.ClusterHealthRecord) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.hSeq++
	r.ID = m.hSeq
	m.health = append(m.health, *r)
	return nil
}
func (m *mockStore) LatestClusterHealth(t string) ([]model.ClusterHealthRecord, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	// 简化：返回该租户所有记录。
	var out []model.ClusterHealthRecord
	for _, r := range m.health {
		if r.TenantID == t {
			out = append(out, r)
		}
	}
	return out, nil
}
func (m *mockStore) ListClusterHealth(t, c string, limit int) ([]model.ClusterHealthRecord, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if limit <= 0 || limit > 1000 {
		limit = 100
	}
	var out []model.ClusterHealthRecord
	for _, r := range m.health {
		if r.TenantID != t {
			continue
		}
		if c != "" && r.ClusterName != c {
			continue
		}
		out = append(out, r)
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// ReplicaWeightPlan CRUD
func (m *mockStore) CreateReplicaWeightPlan(p *model.ReplicaWeightPlan) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.plKey(p.TenantID, p.PolicyName)
	if _, ok := m.plans[k]; ok {
		return errors.New("duplicate")
	}
	m.plSeq++
	p.ID = m.plSeq
	cp := *p
	m.plans[k] = &cp
	return nil
}
func (m *mockStore) GetReplicaWeightPlan(t, p string) (*model.ReplicaWeightPlan, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	pl, ok := m.plans[m.plKey(t, p)]
	if !ok {
		return nil, store.ErrNotFound
	}
	cp := *pl
	return &cp, nil
}
func (m *mockStore) ListReplicaWeightPlans(t string, limit, offset int) ([]model.ReplicaWeightPlan, int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var all []model.ReplicaWeightPlan
	for _, pl := range m.plans {
		if pl.TenantID == t {
			all = append(all, *pl)
		}
	}
	total := int64(len(all))
	if offset >= len(all) {
		return nil, total, nil
	}
	end := offset + limit
	if limit <= 0 || end > len(all) {
		end = len(all)
	}
	if limit <= 0 {
		return all[offset:], total, nil
	}
	return all[offset:end], total, nil
}
func (m *mockStore) UpdateReplicaWeightPlan(p *model.ReplicaWeightPlan) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.plKey(p.TenantID, p.PolicyName)
	if _, ok := m.plans[k]; !ok {
		return store.ErrNotFound
	}
	cp := *p
	m.plans[k] = &cp
	return nil
}

// FailoverPolicy CRUD
func (m *mockStore) CreateFailoverPolicy(p *model.FailoverPolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.fpKey(p.TenantID, p.Namespace, p.Name)
	if _, ok := m.policies[k]; ok {
		return errors.New("duplicate")
	}
	m.fpSeq++
	p.ID = m.fpSeq
	cp := *p
	m.policies[k] = &cp
	return nil
}
func (m *mockStore) GetFailoverPolicy(t, ns, n string) (*model.FailoverPolicy, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	p, ok := m.policies[m.fpKey(t, ns, n)]
	if !ok {
		return nil, store.ErrNotFound
	}
	cp := *p
	return &cp, nil
}
func (m *mockStore) ListFailoverPolicies(t string, limit, offset int) ([]model.FailoverPolicy, int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var all []model.FailoverPolicy
	for _, p := range m.policies {
		if p.TenantID == t {
			all = append(all, *p)
		}
	}
	total := int64(len(all))
	if offset >= len(all) {
		return nil, total, nil
	}
	end := offset + limit
	if limit <= 0 || end > len(all) {
		end = len(all)
	}
	if limit <= 0 {
		return all[offset:], total, nil
	}
	return all[offset:end], total, nil
}
func (m *mockStore) UpdateFailoverPolicy(p *model.FailoverPolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.fpKey(p.TenantID, p.Namespace, p.Name)
	if _, ok := m.policies[k]; !ok {
		return store.ErrNotFound
	}
	cp := *p
	m.policies[k] = &cp
	return nil
}
func (m *mockStore) DeleteFailoverPolicy(t, ns, n string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.fpKey(t, ns, n)
	if _, ok := m.policies[k]; !ok {
		return store.ErrNotFound
	}
	delete(m.policies, k)
	return nil
}

// setTenant 在 gin.Context 注入 tenantId。
func setTenant(c *gin.Context, tenantID string) { c.Set("tenantId", tenantID) }

// withTenantMiddleware 模拟 AuthMiddleware 注入 tenantId。
func withTenantMiddleware(tenantID string) gin.HandlerFunc {
	return func(c *gin.Context) { setTenant(c, tenantID); c.Next() }
}

// TestHealthHandler_Health 健康检查应返回 UP。
func TestHealthHandler_Health(t *testing.T) {
	h := NewHealthHandler("v2.0.0")
	r := gin.New()
	r.GET("/health", h.Health)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	var resp map[string]string
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["service"] != "failover-api" {
		t.Fatalf("expected service=failover-api, got %q", resp["service"])
	}
	if resp["version"] != "v2.0.0" {
		t.Fatalf("expected version=v2.0.0, got %q", resp["version"])
	}
}

// TestOverridePolicyHandler_Create_Success 创建覆盖策略应返回 201。
func TestOverridePolicyHandler_Create_Success(t *testing.T) {
	s := newMockStore()
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	body := `{"name":"op1","namespace":"default","spec":{"overrideRules":[{"overriders":{"plaintext":[{"path":"/spec/replicas","operator":"replace","value":3}]}}]}}`
	req := httptest.NewRequest(http.MethodPost, "/ops", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestOverridePolicyHandler_Create_InvalidBody 非法请求体应返回 400。
func TestOverridePolicyHandler_Create_InvalidBody(t *testing.T) {
	s := newMockStore()
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodPost, "/ops", bytes.NewBufferString(`{"name":"op1"}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

// TestOverridePolicyHandler_Create_EmptyOverrideRules 空 OverrideRules 应返回 400。
func TestOverridePolicyHandler_Create_EmptyOverrideRules(t *testing.T) {
	s := newMockStore()
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	body := `{"name":"op1","namespace":"default","spec":{"overrideRules":[]}}`
	req := httptest.NewRequest(http.MethodPost, "/ops", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for empty overrideRules, got %d", w.Code)
	}
}

// TestOverridePolicyHandler_Create_NoTenant 缺少 tenantId 应返回 401。
func TestOverridePolicyHandler_Create_NoTenant(t *testing.T) {
	s := newMockStore()
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	h.RegisterRoutes(grp)

	body := `{"name":"op1","namespace":"default","spec":{"overrideRules":[{"overriders":{}}]}}`
	req := httptest.NewRequest(http.MethodPost, "/ops", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

// TestOverridePolicyHandler_List_Success 列表应返回已创建策略。
func TestOverridePolicyHandler_List_Success(t *testing.T) {
	s := newMockStore()
	for _, n := range []string{"a", "b"} {
		_ = s.CreateOverridePolicy(&model.OverridePolicy{
			Name: "op-" + n, Namespace: "default", TenantID: "tenant-1", Spec: "{}",
		})
	}
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodGet, "/ops?namespace=default", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
	var resp struct {
		Total int64 `json:"total"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp.Total != 2 {
		t.Fatalf("expected total=2, got %d", resp.Total)
	}
}

// TestOverridePolicyHandler_Get_NotFound 不存在策略应返回 404。
func TestOverridePolicyHandler_Get_NotFound(t *testing.T) {
	s := newMockStore()
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodGet, "/ops/no-such", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}

// TestOverridePolicyHandler_Get_Success 获取已存在策略应返回 200。
func TestOverridePolicyHandler_Get_Success(t *testing.T) {
	s := newMockStore()
	_ = s.CreateOverridePolicy(&model.OverridePolicy{
		Name: "op-get", Namespace: "default", TenantID: "tenant-1", Spec: "{}",
	})
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodGet, "/ops/op-get?namespace=default", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestOverridePolicyHandler_Update_NotFound 更新不存在策略应返回 404。
func TestOverridePolicyHandler_Update_NotFound(t *testing.T) {
	s := newMockStore()
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	body := `{"spec":{"overrideRules":[{"overriders":{}}]}}`
	req := httptest.NewRequest(http.MethodPut, "/ops/no-such", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}

// TestOverridePolicyHandler_Update_Success 更新已存在策略应返回 200。
func TestOverridePolicyHandler_Update_Success(t *testing.T) {
	s := newMockStore()
	_ = s.CreateOverridePolicy(&model.OverridePolicy{
		Name: "op-up", Namespace: "default", TenantID: "tenant-1", Spec: `{"old":true}`,
	})
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	body := `{"spec":{"overrideRules":[{"overriders":{"plaintext":[{"path":"/spec/replicas","operator":"replace","value":5}]}}]}}`
	req := httptest.NewRequest(http.MethodPut, "/ops/op-up?namespace=default", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestOverridePolicyHandler_Delete_Success 删除已存在策略应返回 204。
func TestOverridePolicyHandler_Delete_Success(t *testing.T) {
	s := newMockStore()
	_ = s.CreateOverridePolicy(&model.OverridePolicy{
		Name: "op-del", Namespace: "default", TenantID: "tenant-1", Spec: "{}",
	})
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodDelete, "/ops/op-del?namespace=default", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", w.Code)
	}
}

// TestOverridePolicyHandler_Delete_NotFound 删除不存在策略应返回 404。
func TestOverridePolicyHandler_Delete_NotFound(t *testing.T) {
	s := newMockStore()
	h := NewOverridePolicyHandler(s)
	r := gin.New()
	grp := r.Group("/ops")
	grp.Use(withTenantMiddleware("tenant-1"))
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodDelete, "/ops/no-such", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}
