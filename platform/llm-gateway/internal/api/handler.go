// Package api 实现大模型网关的 REST API handlers。
//
// 暴露端点（OpenAI 兼容协议 + 网关治理端点）：
//   - POST /api/v1/chat/completions  对话补全
//   - POST /api/v1/embeddings        向量嵌入
//   - GET  /api/v1/models            可用模型列表
//   - GET  /api/v1/providers         Provider 列表
//   - POST /api/v1/providers         注册 Provider
//   - DELETE /api/v1/providers/:name 注销 Provider
//   - GET  /api/v1/metrics/tokens    Token 使用统计
//   - GET  /api/v1/metrics/latency   延迟统计
//   - GET  /health                   健康检查
package api

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/shuqing/bigdata/llm-gateway/internal/gateway"
	"github.com/shuqing/bigdata/llm-gateway/internal/provider"
)

// ============ Handler ============

// Handler API 请求处理器。
type Handler struct {
	gateway *gateway.Gateway
	version string
}

// New 构造 API handler。
func New(g *gateway.Gateway, version string) *Handler {
	return &Handler{gateway: g, version: version}
}

// RegisterRoutes 在 gin.Engine 上注册全部路由。
//
// authMiddleware 用于需要认证的端点；健康检查与 metrics 端点可选不认证。
func (h *Handler) RegisterRoutes(r *gin.Engine, authMiddleware gin.HandlerFunc) {
	// 健康检查（无需认证）。
	r.GET("/health", h.Health)

	// 需认证的 API v1 组。
	v1 := r.Group("/api/v1")
	v1.Use(authMiddleware)
	{
		v1.POST("/chat/completions", h.ChatCompletions)
		v1.POST("/embeddings", h.Embeddings)
		v1.GET("/models", h.ListModels)

		v1.GET("/providers", h.ListProviders)
		v1.POST("/providers", h.RegisterProvider)
		v1.DELETE("/providers/:name", h.UnregisterProvider)

		v1.GET("/metrics/tokens", h.TokenMetrics)
		v1.GET("/metrics/latency", h.LatencyMetrics)
	}
}

// ============ 健康检查 ============

// Health GET /health
func (h *Handler) Health(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
	defer cancel()

	unhealthy := h.gateway.HealthCheck(ctx)
	resp := gin.H{
		"status":    "UP",
		"component": "llm-gateway",
		"version":   h.version,
		"providers": h.gateway.ListProviders(),
	}
	if len(unhealthy) > 0 {
		resp["status"] = "DEGRADED"
		resp["unhealthy"] = unhealthy
	}
	c.JSON(http.StatusOK, resp)
}

// ============ 对话补全 ============

// ChatCompletions POST /api/v1/chat/completions
func (h *Handler) ChatCompletions(c *gin.Context) {
	var req provider.ChatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if strings.TrimSpace(req.Model) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "model is required"})
		return
	}
	if len(req.Messages) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "messages must not be empty"})
		return
	}

	// 从 JWT 注入租户与用户身份。
	req.TenantID = ctxString(c, "tenantId")
	req.UserID = ctxString(c, "userId")

	resp, err := h.gateway.ChatCompletion(c.Request.Context(), req)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}

// ============ 向量嵌入 ============

// Embeddings POST /api/v1/embeddings
func (h *Handler) Embeddings(c *gin.Context) {
	var req provider.EmbeddingRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if strings.TrimSpace(req.Model) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "model is required"})
		return
	}
	if len(req.Input) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "input must not be empty"})
		return
	}

	req.TenantID = ctxString(c, "tenantId")
	req.UserID = ctxString(c, "userId")

	resp, err := h.gateway.Embeddings(c.Request.Context(), req)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, resp)
}

// ============ 模型列表 ============

// ListModels GET /api/v1/models
func (h *Handler) ListModels(c *gin.Context) {
	models, err := h.gateway.ListModels(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"object": "list",
		"data":   models,
	})
}

// ============ Provider 治理 ============

// ListProviders GET /api/v1/providers
func (h *Handler) ListProviders(c *gin.Context) {
	names := h.gateway.ListProviders()

	c.JSON(http.StatusOK, gin.H{
		"providers": names,
		"total":     len(names),
	})
}

// registerProviderRequest 注册 Provider 请求体。
type registerProviderRequest struct {
	Name     string               `json:"name"`
	Type     string               `json:"type"`
	Endpoint string               `json:"endpoint"`
	APIKey   string               `json:"apiKey"`
	Models   []provider.ModelInfo `json:"models"`
	Weight   int                  `json:"weight"`
}

// RegisterProvider POST /api/v1/providers
//
// 运行时注册一个 Provider 实例。当前支持 mock / openai / wenxin / qianwen / zhipu。
func (h *Handler) RegisterProvider(c *gin.Context) {
	var req registerProviderRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if strings.TrimSpace(req.Name) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "name is required"})
		return
	}
	if strings.TrimSpace(req.Type) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "type is required"})
		return
	}

	p, err := buildProvider(req)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	h.gateway.RegisterProvider(p, req.Weight)
	c.JSON(http.StatusCreated, gin.H{
		"name":   p.Name(),
		"type":   req.Type,
		"status": "registered",
	})
}

// UnregisterProvider DELETE /api/v1/providers/:name
func (h *Handler) UnregisterProvider(c *gin.Context) {
	name := c.Param("name")
	if !h.gateway.UnregisterProvider(name) {
		c.JSON(http.StatusNotFound, gin.H{"error": "provider not found"})
		return
	}
	c.JSON(http.StatusNoContent, nil)
}

// ============ 指标 ============

// TokenMetrics GET /api/v1/metrics/tokens
func (h *Handler) TokenMetrics(c *gin.Context) {
	tenantID := c.Query("tenant")
	meter := h.gateway.Meter()

	if tenantID != "" {
		stats := meter.TenantStats(tenantID)
		c.JSON(http.StatusOK, gin.H{
			"tenantId":    stats.TenantID,
			"totalTokens": stats.TotalTokens,
			"totalCalls":  stats.TotalCalls,
			"byModel":     stats.ByModel,
		})
		return
	}

	all := meter.AllTenantStats()
	c.JSON(http.StatusOK, gin.H{
		"totalTokens": meter.TotalTokens(),
		"totalCalls":  meter.TotalCalls(),
		"byTenant":    all,
	})
}

// LatencyMetrics GET /api/v1/metrics/latency
func (h *Handler) LatencyMetrics(c *gin.Context) {
	meter := h.gateway.Meter()
	c.JSON(http.StatusOK, gin.H{
		"averageLatencyMs": h.gateway.Meter().AverageLatency().Milliseconds(),
		"totalCalls":       meter.TotalCalls(),
	})
}

// ============ 辅助 ============

// writeError 将 provider 错误映射到 HTTP 状态码。
func (h *Handler) writeError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, provider.ErrModelNotFound):
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
	case errors.Is(err, provider.ErrProviderNotFound):
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
	case errors.Is(err, provider.ErrInvalidRequest):
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
	case errors.Is(err, provider.ErrSensitiveContent):
		c.JSON(http.StatusForbidden, gin.H{"error": err.Error()})
	case errors.Is(err, provider.ErrUpstreamUnavailable):
		c.JSON(http.StatusBadGateway, gin.H{"error": err.Error()})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
	}
}

// buildProvider 根据注册请求构造 Provider 实例。
func buildProvider(req registerProviderRequest) (provider.LLMProvider, error) {
	switch strings.ToLower(req.Type) {
	case "openai":
		return provider.NewOpenAIProvider(provider.OpenAIConfig{
			Endpoint: req.Endpoint, APIKey: req.APIKey, Models: req.Models,
		}), nil
	case "wenxin":
		return provider.NewWenxinProvider(provider.WenxinConfig{
			Endpoint: req.Endpoint, APIKey: req.APIKey, Models: req.Models,
		}), nil
	case "qianwen":
		return provider.NewQianwenProvider(provider.QianwenConfig{
			Endpoint: req.Endpoint, APIKey: req.APIKey, Models: req.Models,
		}), nil
	case "zhipu":
		return provider.NewZhipuProvider(provider.ZhipuConfig{
			Endpoint: req.Endpoint, APIKey: req.APIKey, Models: req.Models,
		}), nil
	case "mock":
		return provider.NewMockProvider(provider.MockConfig{
			Name: req.Name, Models: req.Models,
		}), nil
	default:
		return nil, errors.New("unknown provider type: " + req.Type)
	}
}

// ctxString 从 gin.Context 取出字符串值。
func ctxString(c *gin.Context, key string) string {
	v, ok := c.Get(key)
	if !ok {
		return ""
	}
	s, _ := v.(string)
	return s
}
