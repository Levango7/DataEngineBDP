package failover

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/Levango7/DataEngineBDP/failover-engine/internal/health"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/karmada"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/model"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/prometheus"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/weight"
)

// newTestManager 创建用于测试的 Manager（Karmada 后端返回 Ready+Syncable）。
func newTestManager(t *testing.T) (*Manager, *httptest.Server) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		// 默认返回健康集群。
		_, _ = w.Write([]byte(`{"name":"c1","maxReplicas":100,"conditions":[{"type":"Ready","status":"True"},{"type":"Syncable","status":"True"}]}`))
	}))
	kc := karmada.NewClient(srv.URL, "")
	pc := prometheus.NewClient("http://127.0.0.1:0") // 不可达，降级为 karmada_api 源
	checker := health.NewChecker(kc, pc)
	allocator := weight.NewAllocator()
	m := NewManager(checker, kc, allocator)
	return m, srv
}

// TestNewManager 构造函数应正确初始化。
func TestNewManager(t *testing.T) {
	m, srv := newTestManager(t)
	defer srv.Close()
	if m == nil {
		t.Fatal("expected non-nil manager")
	}
	if m.policies == nil {
		t.Fatal("expected non-nil policies map")
	}
	if m.healthHistory == nil {
		t.Fatal("expected non-nil healthHistory map")
	}
	if m.eventChan == nil {
		t.Fatal("expected non-nil eventChan")
	}
}

// TestManager_AddRemovePolicy 添加/移除策略应生效。
func TestManager_AddRemovePolicy(t *testing.T) {
	m, srv := newTestManager(t)
	defer srv.Close()

	p := &model.FailoverPolicyConfig{
		Name:           "p1",
		PrimaryCluster: "c1",
		BackupClusters: []string{"c2", "c3"},
		Enabled:        true,
	}
	m.AddPolicy(p)
	if _, ok := m.policies["p1"]; !ok {
		t.Fatal("expected policy p1 to be added")
	}

	m.RemovePolicy("p1")
	if _, ok := m.policies["p1"]; ok {
		t.Fatal("expected policy p1 to be removed")
	}
}

// TestManager_EventChan 应返回事件通道。
func TestManager_EventChan(t *testing.T) {
	m, srv := newTestManager(t)
	defer srv.Close()
	if m.EventChan() == nil {
		t.Fatal("expected non-nil event channel")
	}
}

// TestManager_GetHealthHistory_Empty 无历史应返回空切片。
func TestManager_GetHealthHistory_Empty(t *testing.T) {
	m, srv := newTestManager(t)
	defer srv.Close()
	history := m.GetHealthHistory("c1")
	if len(history) != 0 {
		t.Fatalf("expected 0 history, got %d", len(history))
	}
}

// TestManager_HealthSummary_Empty 无历史应返回空 map。
func TestManager_HealthSummary_Empty(t *testing.T) {
	m, srv := newTestManager(t)
	defer srv.Close()
	summary := m.HealthSummary()
	if len(summary) != 0 {
		t.Fatalf("expected empty summary, got %d", len(summary))
	}
}

// TestManager_MarshalHealthSummary_Empty 空摘要应序列化为 "{}"。
func TestManager_MarshalHealthSummary_Empty(t *testing.T) {
	m, srv := newTestManager(t)
	defer srv.Close()
	data, err := m.MarshalHealthSummary()
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if data != "{}" {
		t.Fatalf("expected {}, got %q", data)
	}
}

// TestManager_ManualFailover_Success 手动迁移应返回 succeeded 事件。
func TestManager_ManualFailover_Success(t *testing.T) {
	// Karmada 服务器：failover 端点返回成功。
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"operationId":"op-1"}`))
	}))
	defer srv.Close()

	kc := karmada.NewClient(srv.URL, "")
	m := NewManager(health.NewChecker(kc, nil), kc, weight.NewAllocator())

	event, err := m.ManualFailover(context.Background(), "p1", "c1", "c2", []string{"dep/nginx"})
	if err != nil {
		t.Fatalf("manual failover: %v", err)
	}
	if event.Status != model.EventSucceeded {
		t.Fatalf("expected status=succeeded, got %q", event.Status)
	}
	if event.TriggerReason != model.ReasonManual {
		t.Fatalf("expected reason=manual, got %q", event.TriggerReason)
	}
	if event.SourceCluster != "c1" || event.TargetCluster != "c2" {
		t.Fatalf("unexpected source/target: %s/%s", event.SourceCluster, event.TargetCluster)
	}
}

// TestManager_ManualFailover_KarmadaError Karmada 失败应返回 failed 事件与错误。
func TestManager_ManualFailover_KarmadaError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	kc := karmada.NewClient(srv.URL, "")
	m := NewManager(health.NewChecker(kc, nil), kc, weight.NewAllocator())

	event, err := m.ManualFailover(context.Background(), "p1", "c1", "c2", nil)
	if err == nil {
		t.Fatal("expected error for karmada failure")
	}
	if event.Status != model.EventFailed {
		t.Fatalf("expected status=failed, got %q", event.Status)
	}
}

// TestManager_ManualFailover_NotifiesEvent 手动迁移成功应通过事件通道通知。
func TestManager_ManualFailover_NotifiesEvent(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"operationId":"op-2"}`))
	}))
	defer srv.Close()

	kc := karmada.NewClient(srv.URL, "")
	m := NewManager(health.NewChecker(kc, nil), kc, weight.NewAllocator())

	_, err := m.ManualFailover(context.Background(), "p1", "c1", "c2", nil)
	if err != nil {
		t.Fatalf("manual failover: %v", err)
	}

	select {
	case event := <-m.EventChan():
		if event.Status != model.EventSucceeded {
			t.Fatalf("expected succeeded event, got %q", event.Status)
		}
	case <-time.After(1 * time.Second):
		t.Fatal("expected event on channel, timed out")
	}
}

// TestManager_RebalanceWeights_Success 重新平衡应返回合法分配。
func TestManager_RebalanceWeights_Success(t *testing.T) {
	m, srv := newTestManager(t)
	defer srv.Close()

	result, err := m.RebalanceWeights(
		context.Background(),
		"p1", "dep/nginx", 10,
		map[string]int{"c1": 5, "c2": 5},
		map[string]int{"c1": 1}, // c1 +1
	)
	if err != nil {
		t.Fatalf("rebalance: %v", err)
	}
	if result.PolicyName != "p1" {
		t.Fatalf("expected policyName=p1, got %q", result.PolicyName)
	}
	if result.Reason != model.ReasonRebalance {
		t.Fatalf("expected reason=rebalance, got %q", result.Reason)
	}
	// 新权重应为 c1=6, c2=5。
	if result.Weights["c1"] != 6 {
		t.Fatalf("expected c1 weight=6, got %d", result.Weights["c1"])
	}
	// 分配总和应为 10。
	sum := 0
	for _, v := range result.Allocation {
		sum += v
	}
	if sum != 10 {
		t.Fatalf("expected allocation sum=10, got %d", sum)
	}
}

// TestManager_Run_Cancel Context 取消应停止管理器。
func TestManager_Run_Cancel(t *testing.T) {
	m, srv := newTestManager(t)
	defer srv.Close()

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- m.Run(ctx)
	}()

	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected context.Canceled, got %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not stop after context cancel")
	}
}
