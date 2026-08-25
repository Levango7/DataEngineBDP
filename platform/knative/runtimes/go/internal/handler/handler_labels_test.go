package handler

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/Levango7/DataEngineBDP/function-runtime-go/internal/metrics"
)

func invocationCounterValue(t *testing.T, tenant, function, status string) float64 {
	t.Helper()
	families, err := prometheus.DefaultGatherer.Gather()
	if err != nil {
		t.Fatalf("gather metrics: %v", err)
	}
	for _, mf := range families {
		if mf.GetName() != "serverless_invocation_count" {
			continue
		}
		for _, m := range mf.GetMetric() {
			labels := map[string]string{}
			for _, lp := range m.GetLabel() {
				labels[lp.GetName()] = lp.GetValue()
			}
			if labels["tenant"] == tenant && labels["function"] == function &&
				labels["status"] == status && labels["runtime"] == metrics.RuntimeName {
				return m.GetCounter().GetValue()
			}
		}
	}
	return 0
}

func TestSanitizeLabel(t *testing.T) {
	cases := []struct {
		value    string
		fallback string
		expected string
	}{
		{"tenant-1", "invalid", "tenant-1"},
		{"a", "unnamed", "a"},
		{"0fn-x9", "unnamed", "0fn-x9"},
		{"Tenant_1!", "invalid", "invalid"},
		{"", "invalid", "invalid"},
		{"-abc", "invalid", "invalid"},
		{"abc-", "invalid", "invalid"},
		{"a b", "invalid", "invalid"},
		{strings.Repeat("a", 100), "invalid", strings.Repeat("a", 63)},
		{"ab" + strings.Repeat("-", 62) + "cd", "invalid", "invalid"},
	}
	for _, c := range cases {
		if got := sanitizeLabel(c.value, c.fallback); got != c.expected {
			t.Fatalf("sanitizeLabel(%q)=%q, expected %q", c.value, got, c.expected)
		}
	}
}

func TestHandler_Invoke_InvalidLabelsFallBackToConstants(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	r.POST("/invoke", h.Invoke)

	tenant, function, status := "invalid", "unnamed", "success"
	before := invocationCounterValue(t, tenant, function, status)

	req := httptest.NewRequest(http.MethodPost, "/invoke", bytes.NewBufferString(`{"k":1}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-Id", "TENANT_$BAD")
	req.Header.Set("X-Function-Name", "Big Fn!!")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
	if got := invocationCounterValue(t, tenant, function, status); got != before+1 {
		t.Fatalf("expected counter delta 1 for invalid/unnamed/success, got %f -> %f", before, got)
	}
	var resp map[string]interface{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if resp["function"] != "Big Fn!!" {
		t.Fatalf("expected raw function name preserved in payload, got %v", resp["function"])
	}
}

func TestHandler_Invoke_LongLabelsTruncatedTo63(t *testing.T) {
	h := newTestHandler()
	r := gin.New()
	r.POST("/invoke", h.Invoke)

	longFn := strings.Repeat("x", 63)
	before := invocationCounterValue(t, "default-tenant", longFn, "success")

	req := httptest.NewRequest(http.MethodPost, "/invoke", bytes.NewBufferString(`{}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Function-Name", strings.Repeat("x", 100))
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	if got := invocationCounterValue(t, "default-tenant", longFn, "success"); got != before+1 {
		t.Fatalf("expected truncated label recorded, got %f -> %f", before, got)
	}
}

func withBrokenInvoke(t *testing.T, fn func(string, map[string]interface{}) (map[string]interface{}, error)) {
	t.Helper()
	orig := invokeFunction
	invokeFunction = fn
	t.Cleanup(func() { invokeFunction = orig })
}

func postInvoke(t *testing.T, h *Handler, tenant, function string) *httptest.ResponseRecorder {
	t.Helper()
	r := gin.New()
	r.POST("/invoke", h.Invoke)
	req := httptest.NewRequest(http.MethodPost, "/invoke", bytes.NewBufferString(`{}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-Id", tenant)
	req.Header.Set("X-Function-Name", function)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestHandler_Invoke_FunctionErrorRecordsErrorStatus(t *testing.T) {
	withBrokenInvoke(t, func(string, map[string]interface{}) (map[string]interface{}, error) {
		return nil, errors.New("boom")
	})
	h := newTestHandler()

	tenant, function := "tenant-err", "fn-err"
	beforeErr := invocationCounterValue(t, tenant, function, "error")
	beforeOK := invocationCounterValue(t, tenant, function, "success")

	w := postInvoke(t, h, tenant, function)

	if w.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500, got %d body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "boom") {
		t.Fatalf("expected error detail in body, got %s", w.Body.String())
	}
	if got := invocationCounterValue(t, tenant, function, "error"); got != beforeErr+1 {
		t.Fatalf("expected error status recorded, got %f -> %f", beforeErr, got)
	}
	if got := invocationCounterValue(t, tenant, function, "success"); got != beforeOK {
		t.Fatalf("unexpected success recording on error path, got %f -> %f", beforeOK, got)
	}
}

func TestHandler_Invoke_FunctionPanicRecordsErrorStatus(t *testing.T) {
	withBrokenInvoke(t, func(string, map[string]interface{}) (map[string]interface{}, error) {
		panic("kaboom")
	})
	h := newTestHandler()

	tenant, function := "tenant-panic", "fn-panic"
	beforeErr := invocationCounterValue(t, tenant, function, "error")

	w := postInvoke(t, h, tenant, function)

	if w.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500, got %d body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "panicked") {
		t.Fatalf("expected panic detail in body, got %s", w.Body.String())
	}
	if got := invocationCounterValue(t, tenant, function, "error"); got != beforeErr+1 {
		t.Fatalf("expected panic recorded as error status, got %f -> %f", beforeErr, got)
	}
}

func TestSafeInvoke_ConvertsPanicToError(t *testing.T) {
	withBrokenInvoke(t, func(string, map[string]interface{}) (map[string]interface{}, error) {
		panic("oops")
	})
	result, err := safeInvoke("fn", map[string]interface{}{})
	if err == nil {
		t.Fatal("expected error from recovered panic")
	}
	if result != nil {
		t.Fatalf("expected nil result, got %v", result)
	}
	if !strings.Contains(err.Error(), "oops") {
		t.Fatalf("expected panic value in error, got %v", err)
	}
}
