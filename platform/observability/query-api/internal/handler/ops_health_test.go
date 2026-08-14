package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

// 探活三态验证：UP(actuator status=UP) / DOWN(连接失败) / 500(DOWN)。
func TestOpsHealthOverview_threeStates(t *testing.T) {
	gin.SetMode(gin.TestMode)

	// 本地 mock：返回 actuator 风格 JSON
	mock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	}))
	defer mock.Close()

	h := NewOpsHealthHandler()
	// 用自定义注册表（1 个 UP + 1 个 DOWN 不可达端口）
	h.components = []ComponentSpec{
		{Name: "mock-up", Group: "test", URL: mock.URL, ProbeKind: "http_json"},
		{Name: "mock-down", Group: "test", URL: "http://127.0.0.1:1", ProbeKind: "http_ok"},
	}

	r := gin.New()
	r.GET("/api/v1/ops/health/overview", h.Overview)
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/ops/health/overview", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("状态码: %d", w.Code)
	}
	var resp struct {
		Summary    map[string]int    `json:"summary"`
		Components []ComponentHealth `json:"components"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("解析失败: %v body=%s", err, w.Body.String())
	}
	if resp.Summary["total"] != 2 {
		t.Errorf("total=%d, 期望 2", resp.Summary["total"])
	}
	if resp.Summary["up"] != 1 || resp.Summary["down"] != 1 {
		t.Errorf("up=%d down=%d, 期望 up=1 down=1", resp.Summary["up"], resp.Summary["down"])
	}
	// UP 组件应标记正确
	for _, c := range resp.Components {
		if c.Name == "mock-up" && c.Status != "UP" {
			t.Errorf("mock-up 状态=%s, 期望 UP", c.Status)
		}
		if c.Name == "mock-down" && c.Status != "DOWN" {
			t.Errorf("mock-down 状态=%s, 期望 DOWN", c.Status)
		}
	}
}

// DEGRADED → WARN 映射验证。
func TestOpsHealth_probeDegradedToWarn(t *testing.T) {
	mock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"status":"DEGRADED"}`))
	}))
	defer mock.Close()

	h := NewOpsHealthHandler()
	got := h.probe(ComponentSpec{Name: "deg", Group: "g", URL: mock.URL, ProbeKind: "http_json"})
	if got.Status != "WARN" {
		t.Errorf("DEGRADED 应映射 WARN, got=%s", got.Status)
	}
}

// 不可达端口 → DOWN。
func TestOpsHealth_probeUnreachableToDown(t *testing.T) {
	h := NewOpsHealthHandler()
	got := h.probe(ComponentSpec{Name: "x", Group: "g", URL: "http://127.0.0.1:1", ProbeKind: "http_ok"})
	if got.Status != "DOWN" {
		t.Errorf("不可达应 DOWN, got=%s", got.Status)
	}
}
