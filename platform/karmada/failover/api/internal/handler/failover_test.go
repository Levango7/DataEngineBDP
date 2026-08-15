package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/failover-api/internal/model"
)

// newFailoverRouter 创建带 tenant 中间件的测试路由。
func newFailoverRouter(s *mockStore) *gin.Engine {
	r := gin.New()
	grp := r.Group("/api/v1")
	grp.Use(withTenantMiddleware("tenant-1"))
	h := NewFailoverHandler(s)
	h.RegisterRoutes(grp)
	return r
}

// TestFailoverHandler_ListFailoverEvents_Empty 空列表应返回 total=0。
func TestFailoverHandler_ListFailoverEvents_Empty(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/failover-events", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
	var resp struct {
		Total int64 `json:"total"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp.Total != 0 {
		t.Fatalf("expected total=0, got %d", resp.Total)
	}
}

// TestFailoverHandler_ListFailoverEvents_NoTenant 缺少 tenantId 应返回 401。
func TestFailoverHandler_ListFailoverEvents_NoTenant(t *testing.T) {
	s := newMockStore()
	r := gin.New()
	h := NewFailoverHandler(s)
	h.RegisterRoutes(r.Group("/api/v1"))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/failover-events", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

// TestFailoverHandler_TriggerFailover_Success 手动触发迁移应返回 201。
func TestFailoverHandler_TriggerFailover_Success(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	body := `{"sourceCluster":"cluster-a","targetCluster":"cluster-b","policyName":"p1","workloads":["dep/nginx"]}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/failover-events", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d body=%s", w.Code, w.Body.String())
	}
	var ev model.FailoverEvent
	if err := json.Unmarshal(w.Body.Bytes(), &ev); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if ev.SourceCluster != "cluster-a" {
		t.Fatalf("expected source=cluster-a, got %q", ev.SourceCluster)
	}
	if ev.Status != model.EventStatusPending {
		t.Fatalf("expected status=pending, got %q", ev.Status)
	}
	if ev.TriggerReason != model.ReasonManual {
		t.Fatalf("expected reason=manual, got %q", ev.TriggerReason)
	}
}

// TestFailoverHandler_TriggerFailover_InvalidBody 非法请求体应返回 400。
func TestFailoverHandler_TriggerFailover_InvalidBody(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/failover-events", bytes.NewBufferString(`{"sourceCluster":"a"}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

// TestFailoverHandler_GetFailoverEvent_NotFound 不存在事件应返回 404。
func TestFailoverHandler_GetFailoverEvent_NotFound(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/failover-events/no-such", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}

// TestFailoverHandler_GetFailoverEvent_Success 获取已存在事件应返回 200。
func TestFailoverHandler_GetFailoverEvent_Success(t *testing.T) {
	s := newMockStore()
	_ = s.CreateFailoverEvent(&model.FailoverEvent{
		EventID: "ev-1", TenantID: "tenant-1", SourceCluster: "a", TargetCluster: "b",
		TriggerReason: model.ReasonManual, Status: model.EventStatusSucceeded,
	})
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/failover-events/ev-1", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestFailoverHandler_ListClusterHealth_Success 集群健康列表应返回 200。
func TestFailoverHandler_ListClusterHealth_Success(t *testing.T) {
	s := newMockStore()
	_ = s.CreateClusterHealthRecord(&model.ClusterHealthRecord{
		ClusterName: "c1", TenantID: "tenant-1", Status: model.ClusterStatusHealthy, Ready: true,
	})
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/clusters/health", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestFailoverHandler_GetClusterHealthHistory_Success 集群健康历史应返回 200。
func TestFailoverHandler_GetClusterHealthHistory_Success(t *testing.T) {
	s := newMockStore()
	_ = s.CreateClusterHealthRecord(&model.ClusterHealthRecord{
		ClusterName: "c1", TenantID: "tenant-1", Status: model.ClusterStatusHealthy,
	})
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/clusters/c1/health?limit=10", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestFailoverHandler_ListReplicaPlans_Empty 空副本方案列表应返回 total=0。
func TestFailoverHandler_ListReplicaPlans_Empty(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/replica-plans", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
}

// TestFailoverHandler_CreateReplicaPlan_Success 创建副本方案应返回 201。
func TestFailoverHandler_CreateReplicaPlan_Success(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	body := `{"policyName":"p1","workload":"dep/nginx","totalReplicas":10,"weights":{"c1":6,"c2":4}}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/replica-plans", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestFailoverHandler_CreateReplicaPlan_NonPositiveReplicas 非正副本数应返回 400。
func TestFailoverHandler_CreateReplicaPlan_NonPositiveReplicas(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	body := `{"policyName":"p1","workload":"dep/nginx","totalReplicas":0,"weights":{"c1":1}}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/replica-plans", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

// TestFailoverHandler_CreateReplicaPlan_EmptyWeights 空权重应返回 400。
func TestFailoverHandler_CreateReplicaPlan_EmptyWeights(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	body := `{"policyName":"p1","workload":"dep/nginx","totalReplicas":10,"weights":{}}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/replica-plans", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

// TestFailoverHandler_GetReplicaPlan_NotFound 不存在方案应返回 404。
func TestFailoverHandler_GetReplicaPlan_NotFound(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/replica-plans/no-such", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}

// TestFailoverHandler_GetReplicaPlan_Success 获取已存在方案应返回 200。
func TestFailoverHandler_GetReplicaPlan_Success(t *testing.T) {
	s := newMockStore()
	_ = s.CreateReplicaWeightPlan(&model.ReplicaWeightPlan{
		TenantID: "tenant-1", PolicyName: "p1", Workload: "dep/nginx",
		TotalReplicas: 10, Allocation: "{}", Weights: "{}",
	})
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/replica-plans/p1", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestFailoverHandler_UpdateReplicaPlan_NotFound 更新不存在方案应返回 404。
func TestFailoverHandler_UpdateReplicaPlan_NotFound(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	body := `{"totalReplicas":20}`
	req := httptest.NewRequest(http.MethodPut, "/api/v1/replica-plans/no-such", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}

// TestFailoverHandler_ListFailoverPolicies_Empty 空策略列表应返回 total=0。
func TestFailoverHandler_ListFailoverPolicies_Empty(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/failover-policies", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
}

// TestFailoverHandler_GetFailoverPolicy_NotFound 不存在策略应返回 404。
func TestFailoverHandler_GetFailoverPolicy_NotFound(t *testing.T) {
	s := newMockStore()
	r := newFailoverRouter(s)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/failover-policies/no-such", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}
}

// TestAllocateByWeight_Simple 简单权重分配应正确。
func TestAllocateByWeight_Simple(t *testing.T) {
	weights := map[string]int{"c1": 6, "c2": 4}
	got := allocateByWeight(10, weights, 10)
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

// TestAllocateByWeight_Remainder 有余数时应正确分配。
func TestAllocateByWeight_Remainder(t *testing.T) {
	weights := map[string]int{"c1": 1, "c2": 1, "c3": 1}
	got := allocateByWeight(10, weights, 3)
	sum := got["c1"] + got["c2"] + got["c3"]
	if sum != 10 {
		t.Fatalf("expected sum=10, got %d", sum)
	}
}

// TestAllocateByWeight_SingleCluster 单集群应分配全部副本。
func TestAllocateByWeight_SingleCluster(t *testing.T) {
	weights := map[string]int{"c1": 100}
	got := allocateByWeight(7, weights, 100)
	if got["c1"] != 7 {
		t.Fatalf("expected c1=7, got %d", got["c1"])
	}
}
