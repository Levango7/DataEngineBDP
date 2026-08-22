// Package api 提供向量检索引擎的 HTTP API handlers。
//
// API 端点（前缀 /api/v1）：
//
//	GET    /health                            健康检查
//	POST   /collections                       创建集合
//	DELETE /collections/:name                 删除集合
//	POST   /collections/:name/vectors         插入向量
//	POST   /collections/:name/search          向量检索
//	POST   /collections/:name/hybrid-search   混合检索
//	DELETE /collections/:name/vectors         删除向量
//	GET    /collections/:name/stats           集合统计
//
// 健康检查：GET /api/v1/health
package api

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/service"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
)

// HealthHandler 处理健康检查请求。
type HealthHandler struct {
	version   string
	component string
}

// NewHealthHandler 创建一个新的健康检查 handler。
func NewHealthHandler(version, component string) *HealthHandler {
	return &HealthHandler{version: version, component: component}
}

// Health 返回服务健康状态。
// GET /api/v1/health
func (h *HealthHandler) Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "UP",
		"component": h.component,
		"version":   h.version,
	})
}

// VectorHandler 处理向量检索的 REST API 请求。
type VectorHandler struct {
	svc *service.VectorService
}

// NewVectorHandler 创建一个新的 Vector handler。
func NewVectorHandler(svc *service.VectorService) *VectorHandler {
	return &VectorHandler{svc: svc}
}

// RegisterRoutes 在给定的 router group 上注册所有向量检索路由。
func (h *VectorHandler) RegisterRoutes(rg *gin.RouterGroup) {
	rg.POST("/collections", h.CreateCollection)
	rg.DELETE("/collections/:name", h.DropCollection)
	rg.POST("/collections/:name/vectors", h.InsertVectors)
	rg.POST("/collections/:name/search", h.Search)
	rg.POST("/collections/:name/hybrid-search", h.HybridSearch)
	rg.DELETE("/collections/:name/vectors", h.DeleteVectors)
	rg.GET("/collections/:name/stats", h.GetStats)

	// 前端契约端点（frontend/src/api/vector.ts BASE=/vector）：
	// GET /vector 列表集合、POST /vector/search 全局检索
	rg.GET("/vector", h.ListCollections)
	rg.POST("/vector/search", h.GlobalSearch)
}

// ListCollections 列出全部集合。
// GET /api/v1/vector
func (h *VectorHandler) ListCollections(c *gin.Context) {
	collections, err := h.svc.ListCollections(c.Request.Context())
	if err != nil {
		h.writeStoreError(c, err)
		return
	}
	c.JSON(http.StatusOK, collections)
}

// GlobalSearch 全局检索（不指定集合时遍历全部，取 topK）。
// POST /api/v1/vector/search  body: {"query":"...","topK":5}
//
// 前端 query 为文本，底层 SearchRequest.Vector 为向量：
// 以文本确定性哈希生成固定维度向量（mock 可复现，生产接向量化模型）。
func (h *VectorHandler) GlobalSearch(c *gin.Context) {
	var req struct {
		Query string `json:"query"`
		TopK  int    `json:"topK"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误: " + err.Error()})
		return
	}
	if req.Query == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "query 不能为空"})
		return
	}
	topK := req.TopK
	if topK <= 0 {
		topK = 5
	}

	collections, err := h.svc.ListCollections(c.Request.Context())
	if err != nil {
		h.writeStoreError(c, err)
		return
	}
	// TODO: 生产环境应通过 VECTOR_EMBEDDING_API 环境变量配置真实 embedding 服务
	// 当前为字符哈希占位实现，仅用于无 embedding 服务时的可运行降级
	queryVector := textToVector(req.Query, 8)
	out := make([]map[string]interface{}, 0, topK)
	for _, col := range collections {
		if len(out) >= topK {
			break
		}
		results, err := h.svc.Search(c.Request.Context(), store.SearchRequest{
			CollectionName: col.Name,
			Vector:         queryVector,
			TopK:           topK - len(out),
		})
		if err != nil {
			continue
		}
		for _, r := range results {
			out = append(out, map[string]interface{}{
				"id":         r.ID,
				"score":      r.Score,
				"payload":    r.Metadata,
				"collection": col.Name,
			})
		}
	}
	c.JSON(http.StatusOK, out)
}

// textToVector 文本确定性哈希 → 固定维度向量。
// ⚠️ 占位实现：使用字符码求和哈希，不具备语义检索能力。
// 生产环境应配置真实 embedding 模型 API（如 text-embedding-3-small），
// 通过 VECTOR_EMBEDDING_API 环境变量切换。当前实现仅保证可运行。
func textToVector(text string, dim int) []float32 {
	vec := make([]float32, dim)
	sum := 0
	for _, ch := range text {
		sum += int(ch)
	}
	for i := 0; i < dim; i++ {
		seed := sum*31 + i*17
		vec[i] = float32(seed%1000)/1000 - 0.5
	}
	return vec
}

// CreateCollection 创建向量集合。
// POST /api/v1/collections
func (h *VectorHandler) CreateCollection(c *gin.Context) {
	var req store.CreateCollectionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_request_body", "message": err.Error()})
		return
	}
	if err := h.svc.CreateCollection(c.Request.Context(), req); err != nil {
		h.writeStoreError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"name":       req.Name,
		"dimension":  req.Dimension,
		"metricType": req.MetricType,
		"indexType":  req.IndexType,
	})
}

// DropCollection 删除向量集合。
// DELETE /api/v1/collections/:name
func (h *VectorHandler) DropCollection(c *gin.Context) {
	name := c.Param("name")
	if err := h.svc.DropCollection(c.Request.Context(), name); err != nil {
		h.writeStoreError(c, err)
		return
	}
	c.JSON(http.StatusNoContent, nil)
}

// InsertVectors 插入向量。
// POST /api/v1/collections/:name/vectors
func (h *VectorHandler) InsertVectors(c *gin.Context) {
	name := c.Param("name")
	var body struct {
		Vectors []store.Vector `json:"vectors"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_request_body", "message": err.Error()})
		return
	}
	req := store.InsertRequest{
		CollectionName: name,
		Vectors:        body.Vectors,
	}
	if err := h.svc.Insert(c.Request.Context(), req); err != nil {
		h.writeStoreError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"inserted": len(body.Vectors),
	})
}

// Search 向量检索。
// POST /api/v1/collections/:name/search
func (h *VectorHandler) Search(c *gin.Context) {
	name := c.Param("name")
	var body struct {
		Vector []float32 `json:"vector"`
		TopK   int       `json:"topK"`
		Filter string    `json:"filter"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_request_body", "message": err.Error()})
		return
	}
	req := store.SearchRequest{
		CollectionName: name,
		Vector:         body.Vector,
		TopK:           body.TopK,
		Filter:         body.Filter,
	}
	results, err := h.svc.Search(c.Request.Context(), req)
	if err != nil {
		h.writeStoreError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"results": results,
		"total":   len(results),
	})
}

// HybridSearch 混合检索。
// POST /api/v1/collections/:name/hybrid-search
func (h *VectorHandler) HybridSearch(c *gin.Context) {
	name := c.Param("name")
	var body struct {
		Vector   []float32 `json:"vector"`
		TopK     int       `json:"topK"`
		Filter   string    `json:"filter"`
		MinScore float32   `json:"minScore"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_request_body", "message": err.Error()})
		return
	}
	req := store.HybridSearchRequest{
		CollectionName: name,
		Vector:         body.Vector,
		TopK:           body.TopK,
		Filter:         body.Filter,
		MinScore:       body.MinScore,
	}
	results, err := h.svc.HybridSearch(c.Request.Context(), req)
	if err != nil {
		h.writeStoreError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"results": results,
		"total":   len(results),
	})
}

// DeleteVectors 删除向量。
// DELETE /api/v1/collections/:name/vectors
// Body: {"ids": ["id1", "id2", ...]}
func (h *VectorHandler) DeleteVectors(c *gin.Context) {
	name := c.Param("name")
	var body struct {
		IDs []string `json:"ids"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_request_body", "message": err.Error()})
		return
	}
	if err := h.svc.Delete(c.Request.Context(), name, body.IDs); err != nil {
		h.writeStoreError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"deleted": len(body.IDs),
	})
}

// GetStats 返回集合统计信息。
// GET /api/v1/collections/:name/stats
func (h *VectorHandler) GetStats(c *gin.Context) {
	name := c.Param("name")
	stats, err := h.svc.GetStats(c.Request.Context(), name)
	if err != nil {
		h.writeStoreError(c, err)
		return
	}
	c.JSON(http.StatusOK, stats)
}

// writeStoreError 将 store 层错误映射为统一的 HTTP 错误响应格式：
//
//	{"error": "<errorCode>", "message": "<errorMessage>"}
//
// error 为机器可读的错误码（snake_case），message 为人类可读的错误描述。
func (h *VectorHandler) writeStoreError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, store.ErrCollectionNotFound):
		c.JSON(http.StatusNotFound, gin.H{"error": "collection_not_found", "message": err.Error()})
	case errors.Is(err, store.ErrCollectionAlreadyExists):
		c.JSON(http.StatusConflict, gin.H{"error": "collection_already_exists", "message": err.Error()})
	case errors.Is(err, store.ErrVectorNotFound):
		c.JSON(http.StatusNotFound, gin.H{"error": "vector_not_found", "message": err.Error()})
	case errors.Is(err, store.ErrInvalidDimension):
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_dimension", "message": err.Error()})
	case errors.Is(err, store.ErrInvalidMetricType):
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_metric_type", "message": err.Error()})
	case errors.Is(err, store.ErrInvalidIndexType):
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_index_type", "message": err.Error()})
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "bad_request", "message": err.Error()})
	}
}
