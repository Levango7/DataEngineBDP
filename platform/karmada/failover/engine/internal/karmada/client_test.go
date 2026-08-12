package karmada

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Levango7/DataEngineBDP/failover-engine/internal/model"
)

// TestNewClient 构造函数应正确初始化。
func TestNewClient(t *testing.T) {
	c := NewClient("http://karmada:8080", "token-abc")
	if c.baseURL != "http://karmada:8080" {
		t.Fatalf("expected baseURL=http://karmada:8080, got %q", c.baseURL)
	}
	if c.token != "token-abc" {
		t.Fatalf("expected token=token-abc, got %q", c.token)
	}
}

// TestClient_ListClusters_Success 列出集群应正确解析。
func TestClient_ListClusters_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/apis/cluster.karmada.io/v1alpha1/clusters" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer tok" {
			t.Fatalf("expected Bearer tok, got %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"items":[{"name":"c1","maxReplicas":100}]}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	clusters, err := c.ListClusters(context.Background())
	if err != nil {
		t.Fatalf("list clusters: %v", err)
	}
	if len(clusters) != 1 {
		t.Fatalf("expected 1 cluster, got %d", len(clusters))
	}
	if clusters[0].Name != "c1" {
		t.Fatalf("expected name=c1, got %q", clusters[0].Name)
	}
}

// TestClient_ListClusters_HTTPError 后端非 200 应返回错误。
func TestClient_ListClusters_HTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	if _, err := c.ListClusters(context.Background()); err == nil {
		t.Fatal("expected error for HTTP 500")
	}
}

// TestClient_GetCluster_Success 获取单个集群应正确解析。
func TestClient_GetCluster_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/apis/cluster.karmada.io/v1alpha1/clusters/c1" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"name":"c1","maxReplicas":50,"conditions":[{"type":"Ready","status":"True"}]}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	cluster, err := c.GetCluster(context.Background(), "c1")
	if err != nil {
		t.Fatalf("get cluster: %v", err)
	}
	if cluster.Name != "c1" {
		t.Fatalf("expected name=c1, got %q", cluster.Name)
	}
	if cluster.MaxReplicas != 50 {
		t.Fatalf("expected maxReplicas=50, got %d", cluster.MaxReplicas)
	}
	if !cluster.IsReady() {
		t.Fatal("expected IsReady=true")
	}
}

// TestClient_GetCluster_NotFound 404 应返回错误。
func TestClient_GetCluster_NotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	if _, err := c.GetCluster(context.Background(), "no-such"); err == nil {
		t.Fatal("expected error for 404")
	}
}

// TestClient_Failover_Success 触发 failover 应返回 operationId。
func TestClient_Failover_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/apis/policy.karmada.io/v1alpha1/failover" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		if r.Method != http.MethodPost {
			t.Fatalf("expected POST, got %s", r.Method)
		}
		var req failoverRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.SourceCluster != "c1" || req.TargetCluster != "c2" {
			t.Fatalf("unexpected failover request: %+v", req)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"operationId":"op-123"}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	opID, err := c.Failover(context.Background(), "c1", "c2", []string{"dep/nginx"}, "p1")
	if err != nil {
		t.Fatalf("failover: %v", err)
	}
	if opID != "op-123" {
		t.Fatalf("expected opID=op-123, got %q", opID)
	}
}

// TestClient_Failover_Accepted 202 应视为成功。
func TestClient_Failover_Accepted(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"operationId":"op-async"}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	opID, err := c.Failover(context.Background(), "c1", "c2", nil, "")
	if err != nil {
		t.Fatalf("failover: %v", err)
	}
	if opID != "op-async" {
		t.Fatalf("expected opID=op-async, got %q", opID)
	}
}

// TestClient_Failover_Error 非 2xx 应返回错误。
func TestClient_Failover_Error(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	if _, err := c.Failover(context.Background(), "c1", "c2", nil, ""); err == nil {
		t.Fatal("expected error for 400")
	}
}

// TestClient_MigrateWorkload_Success 迁移工作负载应成功。
func TestClient_MigrateWorkload_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		expectedPath := "/apis/policy.karmada.io/v1alpha1/namespaces/default/propagationpolicies/p1"
		if r.URL.Path != expectedPath {
			t.Fatalf("unexpected path: %s, expected %s", r.URL.Path, expectedPath)
		}
		if r.Method != http.MethodPut {
			t.Fatalf("expected PUT, got %s", r.Method)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	if err := c.MigrateWorkload(context.Background(), "p1", "default", "c1", "c2", 0, 100); err != nil {
		t.Fatalf("migrate: %v", err)
	}
}

// TestClient_MigrateWorkload_Error 非 200 应返回错误。
func TestClient_MigrateWorkload_Error(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	if err := c.MigrateWorkload(context.Background(), "p1", "default", "c1", "c2", 0, 100); err == nil {
		t.Fatal("expected error for 500")
	}
}

// TestClient_NoToken 不带 token 时不应设置 Authorization 头。
func TestClient_NoToken(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "" {
			t.Fatalf("expected empty Authorization, got %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"items":[]}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "")
	if _, err := c.ListClusters(context.Background()); err != nil {
		t.Fatalf("list clusters: %v", err)
	}
}

// TestClient_InvalidBaseURL 无效 baseURL 应返回错误。
func TestClient_InvalidBaseURL(t *testing.T) {
	c := NewClient("http://127.0.0.1:0", "tok")
	_, err := c.ListClusters(context.Background())
	if err == nil {
		t.Fatal("expected error for invalid base URL")
	}
}

// 确保编译时使用 model 包。
var _ = model.ClusterInfo{}