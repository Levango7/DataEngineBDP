package karmadaclient

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// ============ NewConfigFromEnv ============

// TestNewConfigFromEnv_Defaults 未设置 KARMADA_API_TIMEOUT 时应回落默认 30s。
func TestNewConfigFromEnv_Defaults(t *testing.T) {
	t.Setenv("KARMADA_API_SERVER", "https://karmada.example:5443")
	t.Setenv("KARMADA_KUBECONFIG", "/etc/karmada/kubeconfig")
	t.Setenv("KARMADA_API_TIMEOUT", "")

	cfg := NewConfigFromEnv()
	if cfg.APIServer != "https://karmada.example:5443" {
		t.Fatalf("APIServer 期望 https://karmada.example:5443，实际 %q", cfg.APIServer)
	}
	if cfg.Kubeconfig != "/etc/karmada/kubeconfig" {
		t.Fatalf("Kubeconfig 期望 /etc/karmada/kubeconfig，实际 %q", cfg.Kubeconfig)
	}
	if cfg.Timeout != 30*time.Second {
		t.Fatalf("Timeout 期望默认 30s，实际 %v", cfg.Timeout)
	}
}

// TestNewConfigFromEnv_ExplicitTimeout 显式超时（秒）应被解析。
func TestNewConfigFromEnv_ExplicitTimeout(t *testing.T) {
	t.Setenv("KARMADA_API_TIMEOUT", "5")

	cfg := NewConfigFromEnv()
	if cfg.Timeout != 5*time.Second {
		t.Fatalf("Timeout 期望 5s，实际 %v", cfg.Timeout)
	}
}

// TestNewConfigFromEnv_InvalidTimeout 非法超时值应回落默认 30s（不得 panic）。
func TestNewConfigFromEnv_InvalidTimeout(t *testing.T) {
	t.Setenv("KARMADA_API_TIMEOUT", "not-a-number")

	cfg := NewConfigFromEnv()
	if cfg.Timeout != 30*time.Second {
		t.Fatalf("非法超时应回落 30s，实际 %v", cfg.Timeout)
	}
}

// ============ NewClient ============

// TestNewClient_MissingAPIServer 未配置 API Server 应 fail-fast 返回错误。
func TestNewClient_MissingAPIServer(t *testing.T) {
	if _, err := NewClient(Config{}); err == nil {
		t.Fatal("APIServer 为空时应返回错误")
	}
}

// TestNewClient_OK 合法配置应创建客户端并带上配置的超时。
func TestNewClient_OK(t *testing.T) {
	c, err := NewClient(Config{APIServer: "http://127.0.0.1:1", Timeout: 3 * time.Second})
	if err != nil {
		t.Fatalf("创建客户端不应失败: %v", err)
	}
	if c == nil || c.http == nil {
		t.Fatal("客户端与其 http.Client 均不应为 nil")
	}
	if c.http.Timeout != 3*time.Second {
		t.Fatalf("http.Client.Timeout 期望 3s，实际 %v", c.http.Timeout)
	}
}

// ============ 测试辅助 ============

// newTestClient 指向给定测试服务器创建客户端。
func newTestClient(t *testing.T, url string) *Client {
	t.Helper()
	c, err := NewClient(Config{APIServer: url, Timeout: 5 * time.Second})
	if err != nil {
		t.Fatalf("创建客户端失败: %v", err)
	}
	return c
}

// closedServerURL 返回一个已关闭（端口不可达）的服务器地址。
func closedServerURL(t *testing.T) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := srv.URL
	srv.Close()
	return url
}

// ============ RegisterCluster ============

// TestRegisterCluster_Success 201 视为成功，且请求方法/路径/Content-Type 正确。
func TestRegisterCluster_Success(t *testing.T) {
	var gotMethod, gotPath, gotCT string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath, gotCT = r.Method, r.URL.Path, r.Header.Get("Content-Type")
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()

	err := newTestClient(t, srv.URL).RegisterCluster(context.Background(), ClusterInfo{
		Name: "cluster-1", Provider: "self-built", APIEndpoint: "https://c1:6443",
	})
	if err != nil {
		t.Fatalf("期望成功，实际 %v", err)
	}
	if gotMethod != http.MethodPost {
		t.Fatalf("方法期望 POST，实际 %s", gotMethod)
	}
	if gotPath != "/apis/cluster.karmada.io/v1alpha1/clusters" {
		t.Fatalf("路径错误: %s", gotPath)
	}
	if gotCT != "application/json" {
		t.Fatalf("Content-Type 期望 application/json，实际 %s", gotCT)
	}
}

// TestRegisterCluster_OKStatus 200 亦视为成功。
func TestRegisterCluster_OKStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	if err := newTestClient(t, srv.URL).RegisterCluster(context.Background(), ClusterInfo{Name: "c1"}); err != nil {
		t.Fatalf("期望成功，实际 %v", err)
	}
}

// TestRegisterCluster_ErrorStatus 非 2xx 应返回错误且错误信息含状态码与响应体。
func TestRegisterCluster_ErrorStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte("bad request"))
	}))
	defer srv.Close()

	err := newTestClient(t, srv.URL).RegisterCluster(context.Background(), ClusterInfo{Name: "c1"})
	if err == nil {
		t.Fatal("非 2xx 应返回错误")
	}
	if !strings.Contains(err.Error(), "400") {
		t.Fatalf("错误信息应含状态码 400，实际 %v", err)
	}
	if !strings.Contains(err.Error(), "bad request") {
		t.Fatalf("错误信息应含响应体，实际 %v", err)
	}
}

// TestRegisterCluster_Unreachable 上游不可达应返回错误（而非 panic）。
func TestRegisterCluster_Unreachable(t *testing.T) {
	err := newTestClient(t, closedServerURL(t)).RegisterCluster(context.Background(), ClusterInfo{Name: "c1"})
	if err == nil {
		t.Fatal("上游不可达应返回错误")
	}
}

// TestRegisterCluster_ContextCanceled 已取消的 context 应返回错误。
func TestRegisterCluster_ContextCanceled(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := newTestClient(t, srv.URL).RegisterCluster(ctx, ClusterInfo{Name: "c1"}); err == nil {
		t.Fatal("context 已取消应返回错误")
	}
}

// ============ UnregisterCluster ============

// TestUnregisterCluster_Success 204 视为成功，且路径含集群名。
func TestUnregisterCluster_Success(t *testing.T) {
	var gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	if err := newTestClient(t, srv.URL).UnregisterCluster(context.Background(), "cluster-9"); err != nil {
		t.Fatalf("期望成功，实际 %v", err)
	}
	if gotMethod != http.MethodDelete {
		t.Fatalf("方法期望 DELETE，实际 %s", gotMethod)
	}
	if gotPath != "/apis/cluster.karmada.io/v1alpha1/clusters/cluster-9" {
		t.Fatalf("路径错误: %s", gotPath)
	}
}

// TestUnregisterCluster_OKStatus 200 亦视为成功。
func TestUnregisterCluster_OKStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	if err := newTestClient(t, srv.URL).UnregisterCluster(context.Background(), "c1"); err != nil {
		t.Fatalf("期望成功，实际 %v", err)
	}
}

// TestUnregisterCluster_ErrorStatus 非 2xx/204 应返回错误。
func TestUnregisterCluster_ErrorStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	err := newTestClient(t, srv.URL).UnregisterCluster(context.Background(), "c1")
	if err == nil {
		t.Fatal("非成功状态应返回错误")
	}
	if !strings.Contains(err.Error(), "500") {
		t.Fatalf("错误信息应含状态码 500，实际 %v", err)
	}
}

// TestUnregisterCluster_Unreachable 上游不可达应返回错误。
func TestUnregisterCluster_Unreachable(t *testing.T) {
	if err := newTestClient(t, closedServerURL(t)).UnregisterCluster(context.Background(), "c1"); err == nil {
		t.Fatal("上游不可达应返回错误")
	}
}

// ============ ListClusters ============

// TestListClusters_Success 应解析 items 并返回。
func TestListClusters_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"items":[{"name":"c1","status":"online"},{"name":"c2","status":"offline"}]}`))
	}))
	defer srv.Close()

	got, err := newTestClient(t, srv.URL).ListClusters(context.Background())
	if err != nil {
		t.Fatalf("期望成功，实际 %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("期望 2 个集群，实际 %d", len(got))
	}
	if got[0].Name != "c1" || got[1].Name != "c2" {
		t.Fatalf("解析结果不符: %+v", got)
	}
}

// TestListClusters_ErrorStatus 非 200 应返回错误。
func TestListClusters_ErrorStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer srv.Close()

	if _, err := newTestClient(t, srv.URL).ListClusters(context.Background()); err == nil {
		t.Fatal("非 200 应返回错误")
	}
}

// TestListClusters_DecodeError 响应体非法 JSON 应返回解码错误。
func TestListClusters_DecodeError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{not-json`))
	}))
	defer srv.Close()

	if _, err := newTestClient(t, srv.URL).ListClusters(context.Background()); err == nil {
		t.Fatal("非法 JSON 应返回错误")
	}
}

// TestListClusters_Unreachable 上游不可达应返回错误。
func TestListClusters_Unreachable(t *testing.T) {
	if _, err := newTestClient(t, closedServerURL(t)).ListClusters(context.Background()); err == nil {
		t.Fatal("上游不可达应返回错误")
	}
}

// ============ GetCluster ============

// TestGetCluster_Success 应解析单个集群。
func TestGetCluster_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"name":"c1","provider":"self-built","status":"online"}`))
	}))
	defer srv.Close()

	got, err := newTestClient(t, srv.URL).GetCluster(context.Background(), "c1")
	if err != nil {
		t.Fatalf("期望成功，实际 %v", err)
	}
	if got.Name != "c1" || got.Provider != "self-built" {
		t.Fatalf("解析结果不符: %+v", got)
	}
}

// TestGetCluster_ErrorStatus 非 200 应返回错误。
func TestGetCluster_ErrorStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	if _, err := newTestClient(t, srv.URL).GetCluster(context.Background(), "missing"); err == nil {
		t.Fatal("非 200 应返回错误")
	}
}

// TestGetCluster_DecodeError 响应体非法 JSON 应返回解码错误。
func TestGetCluster_DecodeError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`[`))
	}))
	defer srv.Close()

	if _, err := newTestClient(t, srv.URL).GetCluster(context.Background(), "c1"); err == nil {
		t.Fatal("非法 JSON 应返回错误")
	}
}

// TestGetCluster_Unreachable 上游不可达应返回错误。
func TestGetCluster_Unreachable(t *testing.T) {
	if _, err := newTestClient(t, closedServerURL(t)).GetCluster(context.Background(), "c1"); err == nil {
		t.Fatal("上游不可达应返回错误")
	}
}

// ============ bytesReader ============

// TestBytesReader_FullReadThenEOF 应完整读出数据，随后返回 io.EOF。
func TestBytesReader_FullReadThenEOF(t *testing.T) {
	r := bytesReader([]byte("hello"))

	buf := make([]byte, 16)
	n, err := r.Read(buf)
	if err != nil {
		t.Fatalf("首次读取不应报错: %v", err)
	}
	if n != 5 || string(buf[:n]) != "hello" {
		t.Fatalf("首次读取期望 hello，实际 %q", string(buf[:n]))
	}

	if _, err := r.Read(buf); err != io.EOF {
		t.Fatalf("数据读完后应返回 io.EOF，实际 %v", err)
	}
}

// TestBytesReader_ChunkedRead 小缓冲区应分块读出且内容不丢。
func TestBytesReader_ChunkedRead(t *testing.T) {
	r := bytesReader([]byte("abcdef"))

	var out []byte
	buf := make([]byte, 2)
	for {
		n, err := r.Read(buf)
		out = append(out, buf[:n]...)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("分块读取报错: %v", err)
		}
	}
	if string(out) != "abcdef" {
		t.Fatalf("分块读取期望 abcdef，实际 %q", string(out))
	}
}

// TestBytesReader_Empty 空数据应立即返回 io.EOF。
func TestBytesReader_Empty(t *testing.T) {
	if _, err := bytesReader(nil).Read(make([]byte, 4)); err != io.EOF {
		t.Fatalf("空数据应返回 io.EOF，实际 %v", err)
	}
}
