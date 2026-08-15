package health

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Levango7/DataEngineBDP/failover-engine/internal/karmada"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/model"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/prometheus"
)

// newMockKarmadaServer 创建模拟 Karmada API 服务器，返回指定集群状态。
func newMockKarmadaServer(t *testing.T, status int, body string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
}

// TestNewChecker 构造函数应设置默认阈值。
func TestNewChecker(t *testing.T) {
	kc := karmada.NewClient("http://karmada", "")
	pc := prometheus.NewClient("http://prom")
	c := NewChecker(kc, pc)
	if c.cpuDegradedThreshold != 90.0 {
		t.Fatalf("expected default cpu threshold=90.0, got %f", c.cpuDegradedThreshold)
	}
	if c.memoryDegradedThreshold != 90.0 {
		t.Fatalf("expected default memory threshold=90.0, got %f", c.memoryDegradedThreshold)
	}
}

// TestChecker_SetThresholds 自定义阈值应生效。
func TestChecker_SetThresholds(t *testing.T) {
	c := NewChecker(karmada.NewClient("http://k", ""), prometheus.NewClient("http://p"))
	c.SetThresholds(80.0, 85.0)
	if c.cpuDegradedThreshold != 80.0 {
		t.Fatalf("expected cpu threshold=80.0, got %f", c.cpuDegradedThreshold)
	}
	if c.memoryDegradedThreshold != 85.0 {
		t.Fatalf("expected memory threshold=85.0, got %f", c.memoryDegradedThreshold)
	}
}

// TestChecker_CheckCluster_KarmadaError Karmada API 不可达应标记为 down。
func TestChecker_CheckCluster_KarmadaError(t *testing.T) {
	// 使用不可达的 Karmada URL。
	kc := karmada.NewClient("http://127.0.0.1:0", "")
	c := NewChecker(kc, nil)

	h, err := c.CheckCluster(context.Background(), "c1")
	if err != nil {
		t.Fatalf("check cluster: %v", err)
	}
	if h.Status != model.StatusDown {
		t.Fatalf("expected status=down for karmada error, got %q", h.Status)
	}
	if h.CheckSource != "karmada_api" {
		t.Fatalf("expected checkSource=karmada_api, got %q", h.CheckSource)
	}
}

// TestChecker_CheckCluster_NotReady 集群 NotReady 应标记为 down。
func TestChecker_CheckCluster_NotReady(t *testing.T) {
	srv := newMockKarmadaServer(t, http.StatusOK, `{"name":"c1","conditions":[{"type":"Ready","status":"False"}]}`)
	defer srv.Close()

	kc := karmada.NewClient(srv.URL, "")
	c := NewChecker(kc, nil)

	h, err := c.CheckCluster(context.Background(), "c1")
	if err != nil {
		t.Fatalf("check cluster: %v", err)
	}
	if h.Status != model.StatusDown {
		t.Fatalf("expected status=down for NotReady, got %q", h.Status)
	}
	if h.Ready {
		t.Fatal("expected Ready=false")
	}
}

// TestChecker_CheckCluster_NotSyncable 集群 NotSyncable 应标记为 down。
func TestChecker_CheckCluster_NotSyncable(t *testing.T) {
	srv := newMockKarmadaServer(t, http.StatusOK, `{"name":"c1","conditions":[{"type":"Ready","status":"True"},{"type":"Syncable","status":"False"}]}`)
	defer srv.Close()

	kc := karmada.NewClient(srv.URL, "")
	c := NewChecker(kc, nil)

	h, err := c.CheckCluster(context.Background(), "c1")
	if err != nil {
		t.Fatalf("check cluster: %v", err)
	}
	if h.Status != model.StatusDown {
		t.Fatalf("expected status=down for NotSyncable, got %q", h.Status)
	}
}

// TestChecker_CheckCluster_Healthy 集群 Ready+Syncable 且低负载应标记为 healthy。
func TestChecker_CheckCluster_Healthy(t *testing.T) {
	srv := newMockKarmadaServer(t, http.StatusOK, `{"name":"c1","maxReplicas":100,"conditions":[{"type":"Ready","status":"True"},{"type":"Syncable","status":"True"}]}`)
	defer srv.Close()

	kc := karmada.NewClient(srv.URL, "")
	c := NewChecker(kc, nil) // 无 Prometheus 客户端

	h, err := c.CheckCluster(context.Background(), "c1")
	if err != nil {
		t.Fatalf("check cluster: %v", err)
	}
	if h.Status != model.StatusHealthy {
		t.Fatalf("expected status=healthy, got %q", h.Status)
	}
	if !h.Ready || !h.Syncable {
		t.Fatal("expected Ready=true and Syncable=true")
	}
	if h.MaxReplicas != 100 {
		t.Fatalf("expected maxReplicas=100, got %d", h.MaxReplicas)
	}
}

// TestChecker_CheckCluster_Degraded 集群 Ready 但 CPU 超阈值应标记为 degraded。
func TestChecker_CheckCluster_Degraded(t *testing.T) {
	// Karmada 服务器：返回 Ready+Syncable。
	karmadaSrv := newMockKarmadaServer(t, http.StatusOK, `{"name":"c1","maxReplicas":100,"conditions":[{"type":"Ready","status":"True"},{"type":"Syncable","status":"True"}]}`)
	defer karmadaSrv.Close()

	// Prometheus 服务器：返回高 CPU 负载。
	promSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		q := r.URL.Query().Get("query")
		var val string
		if contains(q, "node_cpu_seconds_total") {
			val = "95.0" // CPU 95% > 90% 阈值
		} else {
			val = "50.0"
		}
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[{"value":[1700000000,"` + val + `"]}]}}`))
	}))
	defer promSrv.Close()

	kc := karmada.NewClient(karmadaSrv.URL, "")
	pc := prometheus.NewClient(promSrv.URL)
	c := NewChecker(kc, pc)

	h, err := c.CheckCluster(context.Background(), "c1")
	if err != nil {
		t.Fatalf("check cluster: %v", err)
	}
	if h.Status != model.StatusDegraded {
		t.Fatalf("expected status=degraded for high CPU, got %q", h.Status)
	}
	if h.CPULoad != 95.0 {
		t.Fatalf("expected cpuLoad=95.0, got %f", h.CPULoad)
	}
}

// contains 简单字符串包含检查。
func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// TestChecker_CheckAllClusters_Success 应返回所有集群的检查结果。
func TestChecker_CheckAllClusters_Success(t *testing.T) {
	srv := newMockKarmadaServer(t, http.StatusOK, `{"name":"c1","conditions":[{"type":"Ready","status":"True"},{"type":"Syncable","status":"True"}]}`)
	defer srv.Close()

	kc := karmada.NewClient(srv.URL, "")
	c := NewChecker(kc, nil)

	results, err := c.CheckAllClusters(context.Background(), []string{"c1", "c2"})
	if err != nil {
		t.Fatalf("check all: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}
}

// TestChecker_CheckAllClusters_Empty 空集群列表应返回空结果。
func TestChecker_CheckAllClusters_Empty(t *testing.T) {
	c := NewChecker(karmada.NewClient("http://k", ""), nil)
	results, err := c.CheckAllClusters(context.Background(), nil)
	if err != nil {
		t.Fatalf("check all: %v", err)
	}
	if len(results) != 0 {
		t.Fatalf("expected 0 results, got %d", len(results))
	}
}

// TestChecker_IsClusterDown_AllDown 全部 down 应返回 true。
func TestChecker_IsClusterDown_AllDown(t *testing.T) {
	c := NewChecker(karmada.NewClient("http://k", ""), nil)
	history := []*model.ClusterHealth{
		{Status: model.StatusDown},
		{Status: model.StatusDown},
	}
	if !c.IsClusterDown(history) {
		t.Fatal("expected IsClusterDown=true for all down")
	}
}

// TestChecker_IsClusterDown_MixedStatus 混合状态应返回 false。
func TestChecker_IsClusterDown_MixedStatus(t *testing.T) {
	c := NewChecker(karmada.NewClient("http://k", ""), nil)
	history := []*model.ClusterHealth{
		{Status: model.StatusDown},
		{Status: model.StatusHealthy},
	}
	if c.IsClusterDown(history) {
		t.Fatal("expected IsClusterDown=false for mixed status")
	}
}

// TestChecker_IsClusterDown_EmptyHistory 空历史应返回 false。
func TestChecker_IsClusterDown_EmptyHistory(t *testing.T) {
	c := NewChecker(karmada.NewClient("http://k", ""), nil)
	if c.IsClusterDown(nil) {
		t.Fatal("expected IsClusterDown=false for empty history")
	}
}
