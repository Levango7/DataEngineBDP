package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/function-runtime-go/internal/metrics"
)

// init 设置 Gin 测试模式。
func init() {
	gin.SetMode(gin.TestMode)
}

// testRecorder 包级共享 recorder，避免 Prometheus 重复注册。
var testRecorder = metrics.NewInvocationRecorder()

// newTestHandler 创建测试用 handler。
func newTestHandler() *Handler {
	return NewHandler(testRecorder, "default-fn", "default-tenant")
}

// TestNewHandler 构造函数应正确初始化。
func TestNewHandler(t *testing.T) {
	h := newTestHandler()
	if h == nil {
		t.Fatal("expected non-nil handler")
	}
	if h.defaultFunction != "default-fn" {
		t.Fatalf("expected defaultFunction=default-fn, got %q", h.defaultFunction)
	}
	if h.defaultTenant != "default-tenant" {
		t.Fatalf("expected defaultTenant=default-tenant, got %q", h.defaultTenant)
	}
}

// TestHandler_Health 健康检查应返回 UP。
func TestHandler_Health(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	r.GET("/health", h.Health)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	var resp map[string]string
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["status"] != "UP" {
		t.Fatalf("expected status=UP, got %q", resp["status"])
	}
	if resp["runtime"] != "go" {
		t.Fatalf("expected runtime=go, got %q", resp["runtime"])
	}
	if resp["function"] != "default-fn" {
		t.Fatalf("expected function=default-fn, got %q", resp["function"])
	}
}

// TestHandler_Invoke_WithBody 带请求体调用应返回 echo 结果。
func TestHandler_Invoke_WithBody(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	r.POST("/invoke", h.Invoke)

	body := `{"key":"value","number":42}`
	req := httptest.NewRequest(http.MethodPost, "/invoke", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-Id", "tenant-1")
	req.Header.Set("X-Function-Name", "my-fn")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
	var resp map[string]interface{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if resp["runtime"] != "go" {
		t.Fatalf("expected runtime=go, got %v", resp["runtime"])
	}
	if resp["function"] != "my-fn" {
		t.Fatalf("expected function=my-fn, got %v", resp["function"])
	}
	echo, ok := resp["echo"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected echo to be map, got %T", resp["echo"])
	}
	if echo["key"] != "value" {
		t.Fatalf("expected echo.key=value, got %v", echo["key"])
	}
}

// TestHandler_Invoke_DefaultHeaders 缺少头应使用默认值。
func TestHandler_Invoke_DefaultHeaders(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	r.POST("/invoke", h.Invoke)

	body := `{"test":true}`
	req := httptest.NewRequest(http.MethodPost, "/invoke", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	var resp map[string]interface{}
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["function"] != "default-fn" {
		t.Fatalf("expected function=default-fn, got %v", resp["function"])
	}
}

// TestHandler_Invoke_InvalidJSON 非法 JSON 应仍返回 200（降级为空 event）。
func TestHandler_Invoke_InvalidJSON(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	r.POST("/invoke", h.Invoke)

	req := httptest.NewRequest(http.MethodPost, "/invoke", bytes.NewBufferString("not-json"))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 for invalid JSON, got %d", w.Code)
	}
}

// TestHandler_Invoke_EmptyBody 空请求体应返回 200。
func TestHandler_Invoke_EmptyBody(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	r.POST("/invoke", h.Invoke)

	req := httptest.NewRequest(http.MethodPost, "/invoke", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 for empty body, got %d", w.Code)
	}
}

// TestInvokeFunction_Echo invokeFunction 应正确回显 event。
func TestInvokeFunction_Echo(t *testing.T) {
	event := map[string]interface{}{"foo": "bar", "n": 123.0}
	result := invokeFunction("test-fn", event)
	if result["runtime"] != "go" {
		t.Fatalf("expected runtime=go, got %v", result["runtime"])
	}
	if result["function"] != "test-fn" {
		t.Fatalf("expected function=test-fn, got %v", result["function"])
	}
	echo, ok := result["echo"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected echo to be map, got %T", result["echo"])
	}
	if echo["foo"] != "bar" {
		t.Fatalf("expected echo.foo=bar, got %v", echo["foo"])
	}
	if result["message"] != "Hello from Go function runtime" {
		t.Fatalf("unexpected message: %v", result["message"])
	}
}

// TestInvokeFunction_NilEvent nil event 应不 panic。
func TestInvokeFunction_NilEvent(t *testing.T) {
	result := invokeFunction("fn", nil)
	// nil map 经 JSON 处理后变为空 map。
	echo, ok := result["echo"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected echo to be map, got %T", result["echo"])
	}
	if len(echo) != 0 {
		t.Fatalf("expected empty echo map, got %v", echo)
	}
}