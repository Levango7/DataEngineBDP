package service

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// TestNewPrometheusClient 验证 baseURL 末尾的 / 被裁剪。
func TestNewPrometheusClient(t *testing.T) {
	c := NewPrometheusClient("http://prometheus:9090/", 5*time.Second)
	if c.BaseURL() != "http://prometheus:9090" {
		t.Fatalf("expected base url without trailing slash, got %q", c.BaseURL())
	}
}

// TestPrometheusClient_Query_Success 后端返回 success 时应正确解析。
func TestPrometheusClient_Query_Success(t *testing.T) {
	body := `{"status":"success","data":{"resultType":"vector","result":[]}}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/query" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		if q := r.URL.Query().Get("query"); q != "up" {
			t.Fatalf("unexpected query param: %q", q)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(body))
	}))
	defer srv.Close()

	c := NewPrometheusClient(srv.URL, 5*time.Second)
	params := url.Values{}
	params.Set("query", "up")
	resp, raw, err := c.Query(context.Background(), params)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.Status != "success" {
		t.Fatalf("expected status=success, got %q", resp.Status)
	}
	if !strings.Contains(string(raw), "success") {
		t.Fatalf("raw body should contain success, got %s", string(raw))
	}
}

// TestPrometheusClient_Query_HTTPError 后端返回非 200 应返回错误。
func TestPrometheusClient_Query_HTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"status":"error","errorType":"internal","error":"boom"}`))
	}))
	defer srv.Close()

	c := NewPrometheusClient(srv.URL, 5*time.Second)
	resp, _, err := c.Query(context.Background(), url.Values{})
	if err == nil {
		t.Fatal("expected error for HTTP 500, got nil")
	}
	if resp != nil {
		t.Fatalf("expected nil resp on HTTP error, got %+v", resp)
	}
}

// TestPrometheusClient_Query_PrometheusError status=error 应返回错误。
func TestPrometheusClient_Query_PrometheusError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"error","errorType":"bad_data","error":"invalid query"}`))
	}))
	defer srv.Close()

	c := NewPrometheusClient(srv.URL, 5*time.Second)
	resp, _, err := c.Query(context.Background(), url.Values{})
	if err == nil {
		t.Fatal("expected error for status=error, got nil")
	}
	if resp == nil || resp.Status != "error" {
		t.Fatalf("expected resp with status=error, got %+v", resp)
	}
}

// TestPrometheusClient_QueryRange_Success 范围查询应透传到 /api/v1/query_range。
func TestPrometheusClient_QueryRange_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/query_range" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success","data":{}}`))
	}))
	defer srv.Close()

	c := NewPrometheusClient(srv.URL, 5*time.Second)
	_, _, err := c.QueryRange(context.Background(), url.Values{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestPrometheusClient_Labels_Success 标签列表应透传到 /api/v1/labels。
func TestPrometheusClient_Labels_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/labels" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success","data":[]}`))
	}))
	defer srv.Close()

	c := NewPrometheusClient(srv.URL, 5*time.Second)
	_, _, err := c.Labels(context.Background(), url.Values{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestPrometheusClient_LabelValues_Success 标签值应透传到 /api/v1/label/:name/values，且 name 被 URL 转义。
func TestPrometheusClient_LabelValues_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// labelName="job" 不含特殊字符，PathEscape 不改变它。
		if r.URL.Path != "/api/v1/label/job/values" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success","data":[]}`))
	}))
	defer srv.Close()

	c := NewPrometheusClient(srv.URL, 5*time.Second)
	_, _, err := c.LabelValues(context.Background(), "job", url.Values{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestPrometheusClient_Series_Success 序列查找应透传到 /api/v1/series。
func TestPrometheusClient_Series_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/series" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success","data":[]}`))
	}))
	defer srv.Close()

	c := NewPrometheusClient(srv.URL, 5*time.Second)
	_, _, err := c.Series(context.Background(), url.Values{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestPrometheusClient_InvalidBaseURL 无效 baseURL 应返回请求构造错误。
func TestPrometheusClient_InvalidBaseURL(t *testing.T) {
	c := NewPrometheusClient("http://example.com:99999", 1*time.Second)
	// 使用可取消 context 触发快速失败路径。
	_, _, err := c.Query(context.Background(), url.Values{})
	if err == nil {
		t.Fatal("expected error for invalid base URL, got nil")
	}
}
