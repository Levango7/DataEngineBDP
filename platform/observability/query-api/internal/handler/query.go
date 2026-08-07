package handler

import (
	"net/http"
	"net/url"

	"github.com/gin-gonic/gin"

	"github.com/shuqing/bigdata/query-api/internal/service"
)

// QueryHandler 代理 Prometheus 查询，支持平台方/客户方双视图。
//
// 平台方视图：/platform/api/v1/query 等，不做 tenant 过滤。
// 客户方视图：/tenant/api/v1/query 等，强制注入 tenant_id 过滤。
type QueryHandler struct {
	promClient  *service.PrometheusClient
	tenantSvc   *service.TenantFilter
}

// NewQueryHandler 创建 QueryHandler。
func NewQueryHandler(promClient *service.PrometheusClient, tenantSvc *service.TenantFilter) *QueryHandler {
	return &QueryHandler{promClient: promClient, tenantSvc: tenantSvc}
}

// RegisterPlatformRoutes 注册平台方视图路由（不做 tenant 过滤）。
//
// 路由：
//   GET /platform/api/v1/query          瞬时查询
//   GET /platform/api/v1/query_range    范围查询
//   GET /platform/api/v1/labels         标签名列表
//   GET /platform/api/v1/label/:name/values  标签值列表
//   GET /platform/api/v1/series         序列查找
func (h *QueryHandler) RegisterPlatformRoutes(rg *gin.RouterGroup) {
	v1 := rg.Group("/api/v1")
	v1.GET("/query", h.platformQuery)
	v1.GET("/query_range", h.platformQueryRange)
	v1.GET("/labels", h.platformLabels)
	v1.GET("/label/:name/values", h.platformLabelValues)
	v1.GET("/series", h.platformSeries)
}

// RegisterTenantRoutes 注册客户方视图路由（强制 tenant 过滤）。
//
// 路由同平台方，但路径前缀为 /tenant/api/v1/...。
func (h *QueryHandler) RegisterTenantRoutes(rg *gin.RouterGroup) {
	v1 := rg.Group("/api/v1")
	v1.GET("/query", h.tenantQuery)
	v1.GET("/query_range", h.tenantQueryRange)
	v1.GET("/labels", h.tenantLabels)
	v1.GET("/label/:name/values", h.tenantLabelValues)
	v1.GET("/series", h.tenantSeries)
}

// ---------------------------------------------------------------------------
// 平台方视图 handler（不做 tenant 过滤）
// ---------------------------------------------------------------------------

func (h *QueryHandler) platformQuery(c *gin.Context) {
	params := c.Request.URL.Query()
	ctx := c.Request.Context()
	_, body, err := h.promClient.Query(ctx, params)
	h.respond(c, body, err)
}

func (h *QueryHandler) platformQueryRange(c *gin.Context) {
	params := c.Request.URL.Query()
	ctx := c.Request.Context()
	_, body, err := h.promClient.QueryRange(ctx, params)
	h.respond(c, body, err)
}

func (h *QueryHandler) platformLabels(c *gin.Context) {
	params := c.Request.URL.Query()
	ctx := c.Request.Context()
	_, body, err := h.promClient.Labels(ctx, params)
	h.respond(c, body, err)
}

func (h *QueryHandler) platformLabelValues(c *gin.Context) {
	labelName := c.Param("name")
	params := c.Request.URL.Query()
	ctx := c.Request.Context()
	_, body, err := h.promClient.LabelValues(ctx, labelName, params)
	h.respond(c, body, err)
}

func (h *QueryHandler) platformSeries(c *gin.Context) {
	params := c.Request.URL.Query()
	ctx := c.Request.Context()
	_, body, err := h.promClient.Series(ctx, params)
	h.respond(c, body, err)
}

// ---------------------------------------------------------------------------
// 客户方视图 handler（强制 tenant 过滤）
// ---------------------------------------------------------------------------

func (h *QueryHandler) tenantQuery(c *gin.Context) {
	tenantID := c.GetString("effectiveTenantId")
	original := c.Query("query")
	if original == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "query parameter is required"})
		return
	}
	// 注入 tenant_id 过滤。
	injected := h.tenantSvc.InjectTenantQuery(original, tenantID)

	params := url.Values{}
	params.Set("query", injected)
	// 透传 time 参数。
	if t := c.Query("time"); t != "" {
		params.Set("time", t)
	}
	if timeout := c.Query("timeout"); timeout != "" {
		params.Set("timeout", timeout)
	}

	ctx := c.Request.Context()
	_, body, err := h.promClient.Query(ctx, params)
	h.respond(c, body, err)
}

func (h *QueryHandler) tenantQueryRange(c *gin.Context) {
	tenantID := c.GetString("effectiveTenantId")
	original := c.Query("query")
	if original == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "query parameter is required"})
		return
	}
	injected := h.tenantSvc.InjectTenantQuery(original, tenantID)

	params := url.Values{}
	params.Set("query", injected)
	for _, k := range []string{"start", "end", "step", "timeout"} {
		if v := c.Query(k); v != "" {
			params.Set(k, v)
		}
	}

	ctx := c.Request.Context()
	_, body, err := h.promClient.QueryRange(ctx, params)
	h.respond(c, body, err)
}

func (h *QueryHandler) tenantLabels(c *gin.Context) {
	tenantID := c.GetString("effectiveTenantId")
	params := h.tenantSvc.InjectTenantParams(c.Request.URL.Query(), tenantID)
	ctx := c.Request.Context()
	_, body, err := h.promClient.Labels(ctx, params)
	h.respond(c, body, err)
}

func (h *QueryHandler) tenantLabelValues(c *gin.Context) {
	tenantID := c.GetString("effectiveTenantId")
	labelName := c.Param("name")
	params := h.tenantSvc.InjectTenantLabelValues(c.Request.URL.Query(), labelName, tenantID)
	ctx := c.Request.Context()
	_, body, err := h.promClient.LabelValues(ctx, labelName, params)
	h.respond(c, body, err)
}

func (h *QueryHandler) tenantSeries(c *gin.Context) {
	tenantID := c.GetString("effectiveTenantId")
	params := h.tenantSvc.InjectTenantParams(c.Request.URL.Query(), tenantID)
	ctx := c.Request.Context()
	_, body, err := h.promClient.Series(ctx, params)
	h.respond(c, body, err)
}

// respond 统一处理 Prometheus 代理响应。
//
// 成功时透传原始 body（保持 Prometheus 响应格式不变，兼容 Grafana）。
// 失败时返回 502 + 错误信息。
func (h *QueryHandler) respond(c *gin.Context, body []byte, err error) {
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{
			"error":  "prometheus query failed",
			"detail": err.Error(),
		})
		return
	}
	c.Data(http.StatusOK, "application/json", body)
}