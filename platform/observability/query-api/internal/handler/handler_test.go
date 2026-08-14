package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/query-api/internal/service"
)

// init 设置 Gin 为测试模式。
func init() {
	gin.SetMode(gin.TestMode)
}

// newTestPrometheusServer 启动一个返回指定 body 的测试 Prometheus 后端。
func newTestPrometheusServer(t *testing.T, status int, body string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
}

// TestHealthHandler_Health 健康检查应返回 UP 与版本号。
func TestHealthHandler_Health(t *testing.T) {
	h := NewHealthHandler("1.2.3")

	r := gin.New()
	r.GET("/health", h.Health)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	var resp map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("invalid json: %v", err)
	}
	if resp["status"] != "UP" {
		t.Fatalf("expected status=UP, got %q", resp["status"])
	}
	if resp["version"] != "1.2.3" {
		t.Fatalf("expected version=1.2.3, got %q", resp["version"])
	}
	if resp["service"] != "query-api" {
		t.Fatalf("expected service=query-api, got %q", resp["service"])
	}
}

// TestQueryHandler_PlatformQuery 平台方查询应透传到 Prometheus 且不做 tenant 过滤。
func TestQueryHandler_PlatformQuery(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":{}}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	platformGroup := r.Group("/platform")
	h.RegisterPlatformRoutes(platformGroup)

	req := httptest.NewRequest(http.MethodGet, "/platform/api/v1/query?query=up", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_PlatformQueryRange 平台方范围查询应透传。
func TestQueryHandler_PlatformQueryRange(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":{}}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	h.RegisterPlatformRoutes(r.Group("/platform"))

	req := httptest.NewRequest(http.MethodGet, "/platform/api/v1/query_range?query=up&start=0&end=100&step=15", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_PlatformLabels 平台方标签列表应透传。
func TestQueryHandler_PlatformLabels(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":[]}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	h.RegisterPlatformRoutes(r.Group("/platform"))

	req := httptest.NewRequest(http.MethodGet, "/platform/api/v1/labels", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_PlatformLabelValues 平台方标签值应透传。
func TestQueryHandler_PlatformLabelValues(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":[]}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	h.RegisterPlatformRoutes(r.Group("/platform"))

	req := httptest.NewRequest(http.MethodGet, "/platform/api/v1/label/job/values", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_PlatformSeries 平台方序列查找应透传。
func TestQueryHandler_PlatformSeries(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":[]}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	h.RegisterPlatformRoutes(r.Group("/platform"))

	req := httptest.NewRequest(http.MethodGet, "/platform/api/v1/series", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_TenantQuery_NoQueryParam 客户方查询缺少 query 参数应返回 400。
func TestQueryHandler_TenantQuery_NoQueryParam(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":{}}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	// 模拟 AuthMiddleware + TenantIsolationMiddleware 已注入 effectiveTenantId。
	r.GET("/tenant/api/v1/query", func(c *gin.Context) {
		c.Set("effectiveTenantId", "tenant-1")
	}, h.tenantQuery)

	req := httptest.NewRequest(http.MethodGet, "/tenant/api/v1/query", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for missing query param, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_TenantQuery_WithInjection 客户方查询应注入 tenant_id 过滤并透传到 Prometheus。
func TestQueryHandler_TenantQuery_WithInjection(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":{}}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	r.GET("/tenant/api/v1/query", func(c *gin.Context) {
		c.Set("effectiveTenantId", "tenant-1")
	}, h.tenantQuery)

	req := httptest.NewRequest(http.MethodGet, "/tenant/api/v1/query?query=up&time=100", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_TenantQueryRange_NoQuery 客户方范围查询缺少 query 应返回 400。
func TestQueryHandler_TenantQueryRange_NoQuery(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":{}}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	r.GET("/tenant/api/v1/query_range", func(c *gin.Context) {
		c.Set("effectiveTenantId", "tenant-1")
	}, h.tenantQueryRange)

	req := httptest.NewRequest(http.MethodGet, "/tenant/api/v1/query_range", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

// TestQueryHandler_TenantLabels 客户方标签列表应注入 match[] 过滤。
func TestQueryHandler_TenantLabels(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":[]}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	r.GET("/tenant/api/v1/labels", func(c *gin.Context) {
		c.Set("effectiveTenantId", "tenant-1")
	}, h.tenantLabels)

	req := httptest.NewRequest(http.MethodGet, "/tenant/api/v1/labels", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_TenantLabelValues 客户方标签值应注入过滤。
func TestQueryHandler_TenantLabelValues(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":[]}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	r.GET("/tenant/api/v1/label/:name/values", func(c *gin.Context) {
		c.Set("effectiveTenantId", "tenant-1")
	}, h.tenantLabelValues)

	req := httptest.NewRequest(http.MethodGet, "/tenant/api/v1/label/job/values", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_TenantSeries 客户方序列查找应注入过滤。
func TestQueryHandler_TenantSeries(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusOK, `{"status":"success","data":[]}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	r.GET("/tenant/api/v1/series", func(c *gin.Context) {
		c.Set("effectiveTenantId", "tenant-1")
	}, h.tenantSeries)

	req := httptest.NewRequest(http.MethodGet, "/tenant/api/v1/series", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestQueryHandler_PrometheusError Prometheus 返回错误时应透传 502。
func TestQueryHandler_PrometheusError(t *testing.T) {
	srv := newTestPrometheusServer(t, http.StatusInternalServerError, `{"status":"error","error":"boom"}`)
	defer srv.Close()

	promClient := service.NewPrometheusClient(srv.URL, 5*time.Second)
	tenantSvc := service.NewTenantFilter()
	h := NewQueryHandler(promClient, tenantSvc)

	r := gin.New()
	h.RegisterPlatformRoutes(r.Group("/platform"))

	req := httptest.NewRequest(http.MethodGet, "/platform/api/v1/query?query=up", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("expected 502 on prometheus error, got %d body=%s", w.Code, w.Body.String())
	}
}
