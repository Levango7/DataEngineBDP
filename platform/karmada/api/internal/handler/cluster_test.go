package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/karmada-api/internal/karmadaclient"
)

// 说明：本文件覆盖 P-01 联邦集群 handler（cluster.go）。
// gin.SetMode(gin.TestMode) 已由同包的 handler_test.go 的 init() 设置，此处不再重复定义 init。

// newClusterRouter 构造挂载联邦集群路由的测试引擎，上游指向给定地址。
func newClusterRouter(t *testing.T, upstreamURL string) *gin.Engine {
	t.Helper()
	client, err := karmadaclient.NewClient(karmadaclient.Config{
		APIServer: upstreamURL,
		Timeout:   5 * time.Second,
	})
	if err != nil {
		t.Fatalf("创建 karmada 客户端失败: %v", err)
	}
	r := gin.New()
	NewClusterHandler(client).RegisterRoutes(r.Group("/api/v1"))
	return r
}

// doClusterJSON 向引擎发起 JSON 请求并返回响应记录器。
func doClusterJSON(t *testing.T, r *gin.Engine, method, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatalf("编码请求体失败: %v", err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// upstreamOK 返回按给定状态码与响应体作答的上游测试服务器。
func upstreamOK(t *testing.T, status int, body string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		if body != "" {
			_, _ = w.Write([]byte(body))
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

// ============ 构造与路由注册 ============

// TestNewClusterHandler_NonNil 构造函数应返回非 nil handler。
func TestNewClusterHandler_NonNil(t *testing.T) {
	if h := NewClusterHandler(nil); h == nil {
		t.Fatal("NewClusterHandler 不应返回 nil")
	}
}

// TestClusterHandler_RegisterRoutes 应注册 4 条联邦集群路由。
func TestClusterHandler_RegisterRoutes(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusOK, "").URL)

	want := map[string]bool{
		"POST /api/v1/clusters":         false,
		"GET /api/v1/clusters":          false,
		"GET /api/v1/clusters/:name":    false,
		"DELETE /api/v1/clusters/:name": false,
	}
	for _, ri := range r.Routes() {
		key := ri.Method + " " + ri.Path
		if _, ok := want[key]; ok {
			want[key] = true
		}
	}
	for k, found := range want {
		if !found {
			t.Errorf("路由未注册: %s", k)
		}
	}
}

// ============ RegisterCluster ============

// TestRegisterCluster_InvalidBody400 缺少必填字段应返回 400。
func TestRegisterCluster_InvalidBody400(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusCreated, "").URL)

	w := doClusterJSON(t, r, http.MethodPost, "/api/v1/clusters", map[string]any{})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("期望 400，实际 %d（body=%s）", w.Code, w.Body.String())
	}
}

// TestRegisterCluster_Success201 上游 201 时应返回 201 与 registered。
func TestRegisterCluster_Success201(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusCreated, "").URL)

	w := doClusterJSON(t, r, http.MethodPost, "/api/v1/clusters", map[string]any{
		"name":        "cluster-1",
		"provider":    "self-built",
		"region":      "cn-north",
		"zone":        "az-1",
		"apiEndpoint": "https://c1:6443",
		"labels":      map[string]string{"arch": "amd64"},
	})
	if w.Code != http.StatusCreated {
		t.Fatalf("期望 201，实际 %d（body=%s）", w.Code, w.Body.String())
	}

	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if got["status"] != "registered" || got["name"] != "cluster-1" {
		t.Fatalf("响应不符: %v", got)
	}
}

// TestRegisterCluster_UpstreamError503 上游失败时应返回 503 且带 todo 提示。
func TestRegisterCluster_UpstreamError503(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusInternalServerError, "boom").URL)

	w := doClusterJSON(t, r, http.MethodPost, "/api/v1/clusters", map[string]any{
		"name": "cluster-1", "provider": "self-built", "apiEndpoint": "https://c1:6443",
	})
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("期望 503，实际 %d（body=%s）", w.Code, w.Body.String())
	}

	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if got["error"] != "Karmada API 不可达" {
		t.Fatalf("error 字段不符: %v", got)
	}
	if got["todo"] != "待异地机房真实验证" {
		t.Fatalf("todo 字段不符: %v", got)
	}
}

// ============ ListClusters ============

// TestListClusters_Success200 上游正常时应返回集群列表。
func TestListClusters_Success200(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusOK,
		`{"items":[{"name":"c1","status":"online"},{"name":"c2","status":"offline"}]}`).URL)

	w := doClusterJSON(t, r, http.MethodGet, "/api/v1/clusters", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("期望 200，实际 %d（body=%s）", w.Code, w.Body.String())
	}

	var got struct {
		Clusters []map[string]any `json:"clusters"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if len(got.Clusters) != 2 {
		t.Fatalf("期望 2 个集群，实际 %d（body=%s）", len(got.Clusters), w.Body.String())
	}
}

// TestListClusters_UpstreamErrorReturnsEmptyWithWarning 上游失败时降级为空列表 + warning。
func TestListClusters_UpstreamErrorReturnsEmptyWithWarning(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusInternalServerError, "").URL)

	w := doClusterJSON(t, r, http.MethodGet, "/api/v1/clusters", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("降级路径应仍返回 200，实际 %d", w.Code)
	}

	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	clusters, ok := got["clusters"].([]any)
	if !ok || len(clusters) != 0 {
		t.Fatalf("期望空列表，实际 %v", got["clusters"])
	}
	if got["warning"] != "Karmada API 不可达，返回空列表" {
		t.Fatalf("warning 字段不符: %v", got["warning"])
	}
}

// ============ GetCluster ============

// TestGetCluster_Success200 上游正常时应返回集群详情。
func TestGetCluster_Success200(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusOK, `{"name":"c1","status":"online"}`).URL)

	w := doClusterJSON(t, r, http.MethodGet, "/api/v1/clusters/c1", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("期望 200，实际 %d（body=%s）", w.Code, w.Body.String())
	}

	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if got["name"] != "c1" {
		t.Fatalf("响应不符: %v", got)
	}
}

// TestGetCluster_UpstreamError503 上游失败时应返回 503。
func TestGetCluster_UpstreamError503(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusInternalServerError, "").URL)

	w := doClusterJSON(t, r, http.MethodGet, "/api/v1/clusters/c1", nil)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("期望 503，实际 %d（body=%s）", w.Code, w.Body.String())
	}
}

// ============ UnregisterCluster ============

// TestUnregisterCluster_Success200 上游 204 时应返回 200 与 unregistered。
func TestUnregisterCluster_Success200(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusNoContent, "").URL)

	w := doClusterJSON(t, r, http.MethodDelete, "/api/v1/clusters/c1", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("期望 200，实际 %d（body=%s）", w.Code, w.Body.String())
	}

	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if got["status"] != "unregistered" || got["name"] != "c1" {
		t.Fatalf("响应不符: %v", got)
	}
}

// TestUnregisterCluster_UpstreamError503 上游失败时应返回 503。
func TestUnregisterCluster_UpstreamError503(t *testing.T) {
	r := newClusterRouter(t, upstreamOK(t, http.StatusInternalServerError, "").URL)

	w := doClusterJSON(t, r, http.MethodDelete, "/api/v1/clusters/c1", nil)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("期望 503，实际 %d（body=%s）", w.Code, w.Body.String())
	}
}
