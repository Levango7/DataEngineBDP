package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Levango7/DataEngineBDP/query-api/internal/k3sclient"
	"github.com/gin-gonic/gin"
)

// mock K8s API：返回单节点 + 3 Pod（shuqing 命名空间 2 个 running）。
func mockK8sServer() *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v1/nodes":
			_, _ = w.Write([]byte(`{
				"items":[{"metadata":{
					"name":"node-1",
					"labels":{"node-role.kubernetes.io/control-plane":""},
					"creationTimestamp":"2026-08-07T00:00:00Z"},
					"status":{
						"conditions":[{"type":"Ready","status":"True"}],
						"capacity":{"cpu":"4","memory":"8Gi","pods":"110"},
						"nodeInfo":{"osImage":"Ubuntu 24.04","containerRuntimeVersion":"containerd://2.3.2"}}}]}`))
		case "/api/v1/pods":
			_, _ = w.Write([]byte(`{"items":[
				{"metadata":{"name":"sql-gateway-0","namespace":"shuqing","creationTimestamp":"2026-08-07T00:00:00Z",
					"ownerReferences":[{"kind":"StatefulSet","name":"sql-gateway"}]},
				 "spec":{"nodeName":"node-1","containers":[{"resources":{"requests":{"cpu":"500m","memory":"1Gi"}}}]},
				 "status":{"phase":"Running","startTime":"2026-08-07T00:00:00Z","containerStatuses":[{"restartCount":0}]}},
				{"metadata":{"name":"catalog-1","namespace":"shuqing","creationTimestamp":"2026-08-07T00:00:00Z",
					"ownerReferences":[{"kind":"Deployment","name":"catalog"}]},
				 "spec":{"nodeName":"node-1","containers":[{"resources":{"requests":{"cpu":"100m","memory":"256Mi"}}}]},
				 "status":{"phase":"Running","containerStatuses":[{"restartCount":1}]}},
				{"metadata":{"name":"busybox","namespace":"default","creationTimestamp":"2026-08-07T00:00:00Z"},
				 "spec":{"nodeName":"node-1","containers":[]},
				 "status":{"phase":"Pending","containerStatuses":[]}}
			]}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
}

// fakeK3sClient 测试用：HTTP 调 mock server 并解析为 K8s 结构。
type fakeK3sClient struct {
	base string
}

func (f *fakeK3sClient) ListNodes() (*k3sclient.NodeList, error) {
	var out k3sclient.NodeList
	if err := f.get("/api/v1/nodes", &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (f *fakeK3sClient) ListPods() (*k3sclient.PodList, error) {
	var out k3sclient.PodList
	if err := f.get("/api/v1/pods", &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (f *fakeK3sClient) get(path string, out interface{}) error {
	resp, err := http.Get(f.base + path)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return json.NewDecoder(resp.Body).Decode(out)
}

// 用 mock server 构造 handler（绕过 kubeconfig 解析）。
func clusterHandlerWith(mockURL string) *ClusterHandler {
	return &ClusterHandler{client: &fakeK3sClient{base: mockURL}}
}

func TestClusterOverview_countsNodesAndPods(t *testing.T) {
	gin.SetMode(gin.TestMode)
	srv := mockK8sServer()
	defer srv.Close()

	h := clusterHandlerWith(srv.URL)
	r := gin.New()
	r.GET("/api/v1/cluster/overview", h.Overview)

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/cluster/overview", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("状态码: %d body=%s", w.Code, w.Body.String())
	}
	var body map[string]interface{}
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	if body["nodeTotal"].(float64) != 1 {
		t.Errorf("nodeTotal=%v, 期望 1", body["nodeTotal"])
	}
	if body["nodeReady"].(float64) != 1 {
		t.Errorf("nodeReady=%v, 期望 1", body["nodeReady"])
	}
	if body["podTotal"].(float64) != 3 {
		t.Errorf("podTotal=%v, 期望 3", body["podTotal"])
	}
	if body["podRunning"].(float64) != 2 {
		t.Errorf("podRunning=%v, 期望 2", body["podRunning"])
	}
}

func TestClusterComponents_onlyShuqingNamespace(t *testing.T) {
	gin.SetMode(gin.TestMode)
	srv := mockK8sServer()
	defer srv.Close()

	h := clusterHandlerWith(srv.URL)
	r := gin.New()
	r.GET("/api/v1/cluster/components", h.Components)

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/cluster/components", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("状态码: %d body=%s", w.Code, w.Body.String())
	}
	var items []map[string]interface{}
	_ = json.Unmarshal(w.Body.Bytes(), &items)
	byName := map[string]map[string]interface{}{}
	for _, it := range items {
		byName[it["name"].(string)] = it
	}
	if s, ok := byName["sql-gateway"]; ok && s["status"] != "healthy" {
		t.Errorf("sql-gateway 应为 healthy, got %v", s["status"])
	}
	if s, ok := byName["catalog"]; ok && s["status"] != "healthy" {
		t.Errorf("catalog 应为 healthy, got %v", s["status"])
	}
}
