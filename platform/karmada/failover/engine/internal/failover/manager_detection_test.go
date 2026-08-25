package failover

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/failover-engine/internal/health"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/karmada"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/model"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/prometheus"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/weight"
)

// detectionTestServer 动态模拟 Karmada 控制面：可切换主集群健康状态并统计 failover 调用。
type detectionTestServer struct {
	primaryDown   atomic.Bool
	failoverCalls atomic.Int64
	firstFailover atomic.Int64
	srv           *httptest.Server
}

func newDetectionTestServer(t *testing.T) *detectionTestServer {
	t.Helper()
	ts := &detectionTestServer{}
	mux := http.NewServeMux()
	mux.HandleFunc("/apis/cluster.karmada.io/v1alpha1/clusters/", ts.handleCluster)
	mux.HandleFunc("/apis/policy.karmada.io/v1alpha1/failover", ts.handleFailover)
	ts.srv = httptest.NewServer(mux)
	t.Cleanup(ts.srv.Close)
	return ts
}

func (ts *detectionTestServer) handleCluster(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/apis/cluster.karmada.io/v1alpha1/clusters/")
	w.Header().Set("Content-Type", "application/json")
	if name == "c1" && ts.primaryDown.Load() {
		_, _ = w.Write([]byte(`{"name":"c1","maxReplicas":100,"conditions":[{"type":"Ready","status":"False"},{"type":"Syncable","status":"True"}]}`))
		return
	}
	_, _ = w.Write([]byte(`{"name":"` + name + `","maxReplicas":100,"conditions":[{"type":"Ready","status":"True"},{"type":"Syncable","status":"True"}]}`))
}

func (ts *detectionTestServer) handleFailover(w http.ResponseWriter, r *http.Request) {
	ts.firstFailover.CompareAndSwap(0, time.Now().UnixNano())
	ts.failoverCalls.Add(1)
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"operationId":"op-detect"}`))
}

func newDetectionManager(t *testing.T, ts *detectionTestServer) *Manager {
	t.Helper()
	kc := karmada.NewClient(ts.srv.URL, "")
	pc := prometheus.NewClient("http://127.0.0.1:0")
	checker := health.NewChecker(kc, pc)
	return NewManager(checker, kc, weight.NewAllocator())
}

func newDetectionPolicy() *model.FailoverPolicyConfig {
	return &model.FailoverPolicyConfig{
		Name:                       "p1",
		PrimaryCluster:             "c1",
		BackupClusters:             []string{"c2"},
		DetectionWindowSeconds:     2,
		MigrationTimeoutSeconds:    5,
		HealthCheckIntervalSeconds: 10,
		Enabled:                    true,
	}
}

func startDetectionRun(m *Manager) (cancel context.CancelFunc, done <-chan error) {
	var ctx context.Context
	ctx, cancel = context.WithCancel(context.Background())
	errCh := make(chan error, 1)
	go func() {
		errCh <- m.Run(ctx)
	}()
	return cancel, errCh
}

func stopDetectionRun(cancel context.CancelFunc, done <-chan error) {
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
	}
}

// TestManager_DetectionWindow_TimestampBasedTrigger 持续 down 时首次迁移必须等满检测窗口。
func TestManager_DetectionWindow_TimestampBasedTrigger(t *testing.T) {
	ts := newDetectionTestServer(t)
	ts.primaryDown.Store(true)
	m := newDetectionManager(t, ts)
	m.AddPolicy(newDetectionPolicy())

	start := time.Now()
	cancel, done := startDetectionRun(m)
	defer stopDetectionRun(cancel, done)

	require.Eventually(t, func() bool {
		return ts.firstFailover.Load() != 0
	}, 15*time.Second, 50*time.Millisecond, "expected failover to trigger")

	elapsed := time.Unix(0, ts.firstFailover.Load()).Sub(start)
	require.GreaterOrEqual(t, elapsed, 2*time.Second, "failover must not trigger before DetectionWindow elapses")

	m.mu.Lock()
	st := m.failStates["p1"]
	m.mu.Unlock()
	require.NotNil(t, st)
	require.False(t, st.firstFailTime.IsZero())
	require.True(t, st.triggered)
}

// TestManager_Dedup_SingleTriggerWhileDown 主集群持续 down 只触发一次迁移。
func TestManager_Dedup_SingleTriggerWhileDown(t *testing.T) {
	ts := newDetectionTestServer(t)
	ts.primaryDown.Store(true)
	m := newDetectionManager(t, ts)
	m.AddPolicy(newDetectionPolicy())

	cancel, done := startDetectionRun(m)
	defer stopDetectionRun(cancel, done)

	require.Eventually(t, func() bool {
		return ts.failoverCalls.Load() >= 1
	}, 15*time.Second, 50*time.Millisecond, "expected first failover trigger")

	time.Sleep(6 * time.Second)

	require.Equal(t, int64(1), ts.failoverCalls.Load(), "failover must be deduplicated across detection cycles")
}

// TestManager_Rearm_RecoveredThenDownTriggersAgain 触发后恢复再故障应重新武装并可再次触发。
func TestManager_Rearm_RecoveredThenDownTriggersAgain(t *testing.T) {
	ts := newDetectionTestServer(t)
	ts.primaryDown.Store(true)
	m := newDetectionManager(t, ts)
	m.AddPolicy(newDetectionPolicy())

	cancel, done := startDetectionRun(m)
	defer stopDetectionRun(cancel, done)

	require.Eventually(t, func() bool {
		return ts.failoverCalls.Load() >= 1
	}, 15*time.Second, 50*time.Millisecond, "expected first failover trigger")

	ts.primaryDown.Store(false)
	time.Sleep(3 * time.Second)
	require.Equal(t, int64(1), ts.failoverCalls.Load(), "no failover while primary healthy")

	ts.primaryDown.Store(true)
	require.Eventually(t, func() bool {
		return ts.failoverCalls.Load() >= 2
	}, 15*time.Second, 50*time.Millisecond, "expected second failover trigger after re-arm")

	require.Equal(t, int64(2), ts.failoverCalls.Load())
}
