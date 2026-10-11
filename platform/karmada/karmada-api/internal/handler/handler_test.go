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

	"github.com/Levango7/DataEngineBDP/karmada-api/internal/model"
	"github.com/Levango7/DataEngineBDP/karmada-api/internal/store"
)

// init 设置 Gin 测试模式。
func init() {
	gin.SetMode(gin.TestMode)
}

// mockStore 内存实现 store.Store 接口，用于测试不依赖 CGO/sqlite。
type mockStore struct {
	mu  sync.Mutex
	db  map[string]*model.PropagationPolicy
	seq uint

	createPPErr error
}

// newTestStore 创建内存 mock 存储用于测试。
func newTestStore(_ *testing.T) store.Store {
	return &mockStore{db: make(map[string]*model.PropagationPolicy)}
}

func (m *mockStore) key(tenantID, namespace, name string) string {
	return tenantID + "|" + namespace + "|" + name
}

func (m *mockStore) CreatePropagationPolicy(pp *model.PropagationPolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.createPPErr != nil {
		return m.createPPErr
	}
	k := m.key(pp.TenantID, pp.Namespace, pp.Name)
	if _, exists := m.db[k]; exists {
		return store.ErrAlreadyExists
	}
	m.seq++
	pp.ID = m.seq
	cp := *pp
	m.db[k] = &cp
	return nil
}

func (m *mockStore) GetPropagationPolicy(tenantID, namespace, name string) (*model.PropagationPolicy, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.key(tenantID, namespace, name)
	pp, ok := m.db[k]
	if !ok {
		return nil, store.ErrNotFound
	}
	cp := *pp
	return &cp, nil
}

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

func (m *mockStore) UpdatePropagationPolicy(pp *model.PropagationPolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.key(pp.TenantID, pp.Namespace, pp.Name)
	if _, ok := m.db[k]; !ok {
		return store.ErrNotFound
	}
	cp := *pp
	m.db[k] = &cp
	return nil
}

func (m *mockStore) DeletePropagationPolicy(tenantID, namespace, name string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := m.key(tenantID, namespace, name)
	if _, ok := m.db[k]; !ok {
		return store.ErrNotFound
	}
	delete(m.db, k)
	return nil
}

// setTenant 在 gin.Context 注入 tenantId（模拟 AuthMiddleware）。
func setTenant(c *gin.Context, tenantID string) {
	c.Set("tenantId", tenantID)
}

// TestHealthHandler_Health 健康检查应返回 UP 与版本号。
func TestHealthHandler_Health(t *testing.T) {
	h := NewHealthHandler("v1.0.0")
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
	if resp["status"] != "UP" {
		t.Fatalf("expected status=UP, got %q", resp["status"])
	}
	if resp["service"] != "karmada-api" {
		t.Fatalf("expected service=karmada-api, got %q", resp["service"])
	}
	if resp["version"] != "v1.0.0" {
		t.Fatalf("expected version=v1.0.0, got %q", resp["version"])
	}
}

// TestPropagationPolicyHandler_Create_Success 创建策略应返回 201。
func TestPropagationPolicyHandler_Create_Success(t *testing.T) {
	s := newTestStore(t)
	h := NewPropagationPolicyHandler(s)

	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	body := `{"name":"p1","namespace":"default","spec":{"resourceSelectors":[{"apiVersion":"v1","kind":"Deployment","name":"nginx"}]}}`
	req := httptest.NewRequest(http.MethodPost, "/policies", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d body=%s", w.Code, w.Body.String())
	}
	var pp model.PropagationPolicy
	if err := json.Unmarshal(w.Body.Bytes(), &pp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if pp.Name != "p1" {
		t.Fatalf("expected name=p1, got %q", pp.Name)
	}
	if pp.TenantID != "tenant-1" {
		t.Fatalf("expected tenantId=tenant-1, got %q", pp.TenantID)
	}
}

// TestPropagationPolicyHandler_Create_InvalidBody 非法请求体应返回 400。
func TestPropagationPolicyHandler_Create_InvalidBody(t *testing.T) {
	s := newTestStore(t)
	h := NewPropagationPolicyHandler(s)

	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodPost, "/policies", bytes.NewBufferString(`{"name":"p1"}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid body, got %d", w.Code)
	}
}

// TestPropagationPolicyHandler_Create_NoTenant 缺少 tenantId 应返回 401。
func TestPropagationPolicyHandler_Create_NoTenant(t *testing.T) {
	s := newTestStore(t)
	h := NewPropagationPolicyHandler(s)

	r := gin.New()
	grp := r.Group("/policies")
	h.RegisterRoutes(grp)

	body := `{"name":"p1","namespace":"default","spec":{"resourceSelectors":[]}}`
	req := httptest.NewRequest(http.MethodPost, "/policies", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for missing tenant, got %d", w.Code)
	}
}

// TestPropagationPolicyHandler_Create_Duplicate 重复创建同名策略应返回 409。
func TestPropagationPolicyHandler_Create_Duplicate(t *testing.T) {
	m := &mockStore{db: make(map[string]*model.PropagationPolicy)}
	h := NewPropagationPolicyHandler(m)

	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	body := `{"name":"p-dup","namespace":"default","spec":{"resourceSelectors":[{"apiVersion":"v1","kind":"Deployment","name":"nginx"}]}}`
	req := httptest.NewRequest(http.MethodPost, "/policies", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201 on first create, got %d body=%s", w.Code, w.Body.String())
	}

	req = httptest.NewRequest(http.MethodPost, "/policies", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusConflict {
		t.Fatalf("expected 409 for duplicate, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestPropagationPolicyHandler_Create_StoreInternalError store 内部错误应返回 500。
func TestPropagationPolicyHandler_Create_StoreInternalError(t *testing.T) {
	m := &mockStore{db: make(map[string]*model.PropagationPolicy)}
	m.createPPErr = errors.New("connection refused")
	h := NewPropagationPolicyHandler(m)

	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	body := `{"name":"p-err","namespace":"default","spec":{"resourceSelectors":[{"apiVersion":"v1","kind":"Deployment","name":"nginx"}]}}`
	req := httptest.NewRequest(http.MethodPost, "/policies", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 for store internal error, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestPropagationPolicyHandler_List_Success 列表应返回已创建的策略。
func TestPropagationPolicyHandler_List_Success(t *testing.T) {
	s := newTestStore(t)
	for _, n := range []string{"a", "b", "c"} {
		if err := s.CreatePropagationPolicy(&model.PropagationPolicy{
			Name: "p-" + n, Namespace: "default", TenantID: "tenant-1", Spec: "{}",
		}); err != nil {
			t.Fatalf("create: %v", err)
		}
	}

	h := NewPropagationPolicyHandler(s)
	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodGet, "/policies?namespace=default&limit=10", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
	var resp struct {
		Items []model.PropagationPolicy `json:"items"`
		Total int64                     `json:"total"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if resp.Total != 3 {
		t.Fatalf("expected total=3, got %d", resp.Total)
	}
	if len(resp.Items) != 3 {
		t.Fatalf("expected 3 items, got %d", len(resp.Items))
	}
}

// TestPropagationPolicyHandler_List_DefaultLimit 非法 limit 应回退到默认 20。
func TestPropagationPolicyHandler_List_DefaultLimit(t *testing.T) {
	s := newTestStore(t)
	h := NewPropagationPolicyHandler(s)
	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodGet, "/policies?limit=0", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	var resp struct {
		Limit int `json:"limit"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp.Limit != 20 {
		t.Fatalf("expected default limit=20, got %d", resp.Limit)
	}
}

// TestPropagationPolicyHandler_Get_Success 获取已存在策略应返回 200。
func TestPropagationPolicyHandler_Get_Success(t *testing.T) {
	s := newTestStore(t)
	if err := s.CreatePropagationPolicy(&model.PropagationPolicy{
		Name: "p-get", Namespace: "default", TenantID: "tenant-1", Spec: "{}",
	}); err != nil {
		t.Fatalf("create: %v", err)
	}

	h := NewPropagationPolicyHandler(s)
	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodGet, "/policies/p-get?namespace=default", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestPropagationPolicyHandler_Get_NotFound 不存在策略应返回 404。
func TestPropagationPolicyHandler_Get_NotFound(t *testing.T) {
	s := newTestStore(t)
	h := NewPropagationPolicyHandler(s)
	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodGet, "/policies/no-such", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}

// TestPropagationPolicyHandler_Update_Success 更新已存在策略应返回 200。
func TestPropagationPolicyHandler_Update_Success(t *testing.T) {
	s := newTestStore(t)
	if err := s.CreatePropagationPolicy(&model.PropagationPolicy{
		Name: "p-up", Namespace: "default", TenantID: "tenant-1", Spec: `{"old":true}`,
	}); err != nil {
		t.Fatalf("create: %v", err)
	}

	h := NewPropagationPolicyHandler(s)
	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	body := `{"spec":{"resourceSelectors":[]}}`
	req := httptest.NewRequest(http.MethodPut, "/policies/p-up?namespace=default", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestPropagationPolicyHandler_Update_NotFound 更新不存在策略应返回 404。
func TestPropagationPolicyHandler_Update_NotFound(t *testing.T) {
	s := newTestStore(t)
	h := NewPropagationPolicyHandler(s)
	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	body := `{"spec":{"resourceSelectors":[]}}`
	req := httptest.NewRequest(http.MethodPut, "/policies/no-such", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}

// TestPropagationPolicyHandler_Delete_Success 删除已存在策略应返回 204。
func TestPropagationPolicyHandler_Delete_Success(t *testing.T) {
	s := newTestStore(t)
	if err := s.CreatePropagationPolicy(&model.PropagationPolicy{
		Name: "p-del", Namespace: "default", TenantID: "tenant-1", Spec: "{}",
	}); err != nil {
		t.Fatalf("create: %v", err)
	}

	h := NewPropagationPolicyHandler(s)
	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodDelete, "/policies/p-del?namespace=default", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", w.Code)
	}
}

// TestPropagationPolicyHandler_Delete_NotFound 删除不存在策略应返回 404。
func TestPropagationPolicyHandler_Delete_NotFound(t *testing.T) {
	s := newTestStore(t)
	h := NewPropagationPolicyHandler(s)
	r := gin.New()
	grp := r.Group("/policies")
	grp.Use(func(c *gin.Context) { setTenant(c, "tenant-1"); c.Next() })
	h.RegisterRoutes(grp)

	req := httptest.NewRequest(http.MethodDelete, "/policies/no-such", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}
