package prometheus

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestNewClient 构造函数应正确初始化。
func TestNewClient(t *testing.T) {
	c := NewClient("http://prom:9090")
	if c.baseURL != "http://prom:9090" {
		t.Fatalf("expected baseURL=http://prom:9090, got %q", c.baseURL)
	}
}

// TestClient_Query_Success 查询应正确解析结果。
func TestClient_Query_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/query" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[{"value":[1700000000,"75.5"]}]}}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Query(context.Background(), "up")
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if result.Value != 75.5 {
		t.Fatalf("expected value=75.5, got %f", result.Value)
	}
}

// TestClient_Query_EmptyResult 空结果应返回 0 值。
func TestClient_Query_EmptyResult(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[]}}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Query(context.Background(), "up")
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if result.Value != 0 {
		t.Fatalf("expected value=0 for empty result, got %f", result.Value)
	}
}

// TestClient_Query_HTTPError 非 200 应返回错误。
func TestClient_Query_HTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	if _, err := c.Query(context.Background(), "up"); err == nil {
		t.Fatal("expected error for HTTP 500")
	}
}

// TestClient_Query_StatusError status=error 应返回错误。
func TestClient_Query_StatusError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"error","errorType":"bad_data","error":"invalid"}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	if _, err := c.Query(context.Background(), "up"); err == nil {
		t.Fatal("expected error for status=error")
	}
}

// TestClient_GetClusterMetrics_Success 应聚合多个指标查询。
func TestClient_GetClusterMetrics_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		// 根据查询参数返回不同值。
		q := r.URL.Query().Get("query")
		var val string
		switch {
		case contains(q, "node_cpu_seconds_total"):
			val = "80.0"
		case contains(q, "node_memory_MemAvailable"):
			val = "70.0"
		case contains(q, "kube_pod_info"):
			val = "50"
		case contains(q, "kube_node_info"):
			val = "5"
		default:
			val = "0"
		}
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[{"value":[1700000000,"` + val + `"]}]}}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	metrics, err := c.GetClusterMetrics(context.Background(), "c1")
	if err != nil {
		t.Fatalf("get metrics: %v", err)
	}
	if metrics.ClusterName != "c1" {
		t.Fatalf("expected clusterName=c1, got %q", metrics.ClusterName)
	}
	if metrics.CPULoad != 80.0 {
		t.Fatalf("expected cpuLoad=80.0, got %f", metrics.CPULoad)
	}
	if metrics.PodCount != 50 {
		t.Fatalf("expected podCount=50, got %d", metrics.PodCount)
	}
	if metrics.NodeCount != 5 {
		t.Fatalf("expected nodeCount=5, got %d", metrics.NodeCount)
	}
}

// contains 简单字符串包含检查（避免引入 strings 包）。
func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// TestClient_IsAvailable_Available 健康端点返回 200 应视为可用。
func TestClient_IsAvailable_Available(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/-/healthy" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	if !c.IsAvailable(context.Background()) {
		t.Fatal("expected available")
	}
}

// TestClient_IsAvailable_Unavailable 非 200 应视为不可用。
func TestClient_IsAvailable_Unavailable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	if c.IsAvailable(context.Background()) {
		t.Fatal("expected unavailable")
	}
}

// TestClient_IsAvailable_Unreachable 不可达应视为不可用。
func TestClient_IsAvailable_Unreachable(t *testing.T) {
	c := NewClient("http://127.0.0.1:0")
	if c.IsAvailable(context.Background()) {
		t.Fatal("expected unavailable for unreachable server")
	}
}
