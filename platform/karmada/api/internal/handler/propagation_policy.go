package handler


// PropagationPolicy CRUD handler。
//
// 路由：
//   - POST   /           创建传播策略
//   - GET    /           列出传播策略（支持 ?namespace=&limit=&offset= 查询参数）
//   - GET    /:name      获取单个策略
//   - PUT    /:name      更新策略
//   - DELETE /:name      删除策略

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/shuqing/bigdata/karmada-api/internal/model"
	"github.com/shuqing/bigdata/karmada-api/internal/store"
)

// PropagationPolicyHandler 传播策略处理器。
type PropagationPolicyHandler struct {
	store store.Store
}

// NewPropagationPolicyHandler 创建传播策略处理器。
func NewPropagationPolicyHandler(s store.Store) *PropagationPolicyHandler {
	return &PropagationPolicyHandler{store: s}
}

// RegisterRoutes 注册路由到指定 group。
func (h *PropagationPolicyHandler) RegisterRoutes(rg *gin.RouterGroup) {
	rg.POST("", h.Create)
	rg.GET("", h.List)
	rg.GET("/:name", h.Get)
	rg.PUT("/:name", h.Update)
	rg.DELETE("/:name", h.Delete)
}

// createRequest 创建请求体。
type createRequest struct {
	Name      string          `json:"name"      binding:"required"`
	Namespace string          `json:"namespace" binding:"required"`
	Spec      model.PropagationPolicySpec `json:"spec"      binding:"required"`
}

// Create 创建传播策略。
// POST /api/v1/propagation-policies
func (h *PropagationPolicyHandler) Create(c *gin.Context) {
	var req createRequest
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

	// 序列化 spec 为 JSON 字符串存储。
	specBytes, err := json.Marshal(req.Spec)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to marshal spec"})
		return
	}
	specJSON := string(specBytes)

	pp := &model.PropagationPolicy{
		Name:      req.Name,
		Namespace: req.Namespace,
		TenantID:  tenantID.(string),
		Spec:      specJSON,
	}

	if err := h.store.CreatePropagationPolicy(pp); err != nil {
		c.JSON(http.StatusConflict, gin.H{"error": "failed to create: " + err.Error()})
		return
	}

	c.JSON(http.StatusCreated, pp)
}

// List 列出传播策略。
// GET /api/v1/propagation-policies?namespace=default&limit=20&offset=0
func (h *PropagationPolicyHandler) List(c *gin.Context) {
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

	pps, total, err := h.store.ListPropagationPolicies(tenantID.(string), namespace, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to list: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"items": pps,
		"total": total,
		"limit": limit,
		"offset": offset,
	})
}

// Get 获取单个传播策略。
// GET /api/v1/propagation-policies/:name?namespace=default
func (h *PropagationPolicyHandler) Get(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	pp, err := h.store.GetPropagationPolicy(tenantID.(string), namespace, name)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "propagation policy not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, pp)
}

// updateRequest 更新请求体。
type updateRequest struct {
	Spec model.PropagationPolicySpec `json:"spec" binding:"required"`
}

// Update 更新传播策略。
// PUT /api/v1/propagation-policies/:name?namespace=default
func (h *PropagationPolicyHandler) Update(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	var req updateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body: " + err.Error()})
		return
	}

	// 先查询是否存在。
	pp, err := h.store.GetPropagationPolicy(tenantID.(string), namespace, name)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "propagation policy not found"})
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
	pp.Spec = string(specBytes)

	if err := h.store.UpdatePropagationPolicy(pp); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, pp)
}

// Delete 删除传播策略。
// DELETE /api/v1/propagation-policies/:name?namespace=default
func (h *PropagationPolicyHandler) Delete(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	if err := h.store.DeletePropagationPolicy(tenantID.(string), namespace, name); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "propagation policy not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to delete: " + err.Error()})
		return
	}

	c.JSON(http.StatusNoContent, nil)
}
