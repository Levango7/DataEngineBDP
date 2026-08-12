package handler

// OverridePolicy CRUD handler。
//
// 路由：
//   - POST   /           创建覆盖策略
//   - GET    /           列出覆盖策略（支持 ?namespace=&limit=&offset= 查询参数）
//   - GET    /:name      获取单个策略
//   - PUT    /:name      更新策略
//   - DELETE /:name      删除策略

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/failover-api/internal/model"
	"github.com/Levango7/DataEngineBDP/failover-api/internal/store"
)

// OverridePolicyHandler 覆盖策略处理器。
type OverridePolicyHandler struct {
	store store.Store
}

// NewOverridePolicyHandler 创建覆盖策略处理器。
func NewOverridePolicyHandler(s store.Store) *OverridePolicyHandler {
	return &OverridePolicyHandler{store: s}
}

// RegisterRoutes 注册路由到指定 group。
func (h *OverridePolicyHandler) RegisterRoutes(rg *gin.RouterGroup) {
	rg.POST("", h.Create)
	rg.GET("", h.List)
	rg.GET("/:name", h.Get)
	rg.PUT("/:name", h.Update)
	rg.DELETE("/:name", h.Delete)
}

// createRequest 创建请求体。
type opCreateRequest struct {
	Name      string                   `json:"name"      binding:"required"`
	Namespace string                   `json:"namespace" binding:"required"`
	Spec      model.OverridePolicySpec `json:"spec"      binding:"required"`
}

// Create 创建覆盖策略。
// POST /api/v1/override-policies
func (h *OverridePolicyHandler) Create(c *gin.Context) {
	var req opCreateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body: " + err.Error()})
		return
	}

	// 从 JWT 中间件获取租户 ID。
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	// 校验 OverrideRules 非空（覆盖策略必须有覆盖规则）。
	if len(req.Spec.OverrideRules) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "overrideRules must not be empty"})
		return
	}

	// 序列化 spec 为 JSON 字符串存储。
	specBytes, err := json.Marshal(req.Spec)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to marshal spec"})
		return
	}
	specJSON := string(specBytes)

	op := &model.OverridePolicy{
		Name:      req.Name,
		Namespace: req.Namespace,
		TenantID:  tenantID.(string),
		Spec:      specJSON,
	}

	if err := h.store.CreateOverridePolicy(op); err != nil {
		c.JSON(http.StatusConflict, gin.H{"error": "failed to create: " + err.Error()})
		return
	}

	c.JSON(http.StatusCreated, op)
}

// List 列出覆盖策略。
// GET /api/v1/override-policies?namespace=default&limit=20&offset=0
func (h *OverridePolicyHandler) List(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	namespace := c.DefaultQuery("namespace", "")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	// 参数校验与默认值。
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	if offset < 0 {
		offset = 0
	}

	ops, total, err := h.store.ListOverridePolicies(tenantID.(string), namespace, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to list: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"items":  ops,
		"total":  total,
		"limit":  limit,
		"offset": offset,
	})
}

// Get 获取单个覆盖策略。
// GET /api/v1/override-policies/:name?namespace=default
func (h *OverridePolicyHandler) Get(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	op, err := h.store.GetOverridePolicy(tenantID.(string), namespace, name)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "override policy not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, op)
}

// updateRequest 更新请求体。
type opUpdateRequest struct {
	Spec model.OverridePolicySpec `json:"spec" binding:"required"`
}

// Update 更新覆盖策略。
// PUT /api/v1/override-policies/:name?namespace=default
func (h *OverridePolicyHandler) Update(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	var req opUpdateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body: " + err.Error()})
		return
	}

	// 校验 OverrideRules 非空。
	if len(req.Spec.OverrideRules) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "overrideRules must not be empty"})
		return
	}

	// 先查询是否存在。
	op, err := h.store.GetOverridePolicy(tenantID.(string), namespace, name)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "override policy not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get: " + err.Error()})
		return
	}

	// 更新 spec。
	specBytes, err := json.Marshal(req.Spec)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to marshal spec"})
		return
	}
	op.Spec = string(specBytes)

	if err := h.store.UpdateOverridePolicy(op); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, op)
}

// Delete 删除覆盖策略。
// DELETE /api/v1/override-policies/:name?namespace=default
func (h *OverridePolicyHandler) Delete(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	if err := h.store.DeleteOverridePolicy(tenantID.(string), namespace, name); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "override policy not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to delete: " + err.Error()})
		return
	}

	c.JSON(http.StatusNoContent, nil)
}
