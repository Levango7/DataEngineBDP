package handler

// FailoverEvent / ClusterHealth / ReplicaWeightPlan / FailoverPolicy handler。
//
// 路由：
//   - GET    /failover-events                 列出迁移事件
//   - GET    /failover-events/:eventId        获取单个迁移事件
//   - POST   /failover-events                 手动触发迁移
//   - GET    /clusters/health                 获取所有集群最新健康
//   - GET    /clusters/:name/health           获取集群健康历史
//   - GET    /replica-plans                   列出副本权重方案
//   - GET    /replica-plans/:policyName       获取单个副本权重方案
//   - POST   /replica-plans                   计算并保存副本权重方案
//   - PUT    /replica-plans/:policyName       动态调整副本权重
//   - GET    /failover-policies               列出迁移策略
//   - POST   /failover-policies               创建迁移策略
//   - GET    /failover-policies/:name         获取单个迁移策略
//   - PUT    /failover-policies/:name         更新迁移策略
//   - DELETE /failover-policies/:name         删除迁移策略

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/failover-api/internal/model"
	"github.com/Levango7/DataEngineBDP/failover-api/internal/store"
)

// FailoverHandler 故障迁移相关 handler 集合。
type FailoverHandler struct {
	store store.Store
}

// NewFailoverHandler 创建故障迁移 handler。
func NewFailoverHandler(s store.Store) *FailoverHandler {
	return &FailoverHandler{store: s}
}

// RegisterRoutes 注册路由到指定 group。
func (h *FailoverHandler) RegisterRoutes(rg *gin.RouterGroup) {
	// /failover-events
	events := rg.Group("/failover-events")
	{
		events.GET("", h.ListFailoverEvents)
		events.POST("", h.TriggerFailover)
		events.GET("/:eventId", h.GetFailoverEvent)
	}

	// /clusters/health
	clusters := rg.Group("/clusters")
	{
		clusters.GET("/health", h.ListClusterHealth)
		clusters.GET("/:name/health", h.GetClusterHealthHistory)
	}

	// /replica-plans
	plans := rg.Group("/replica-plans")
	{
		plans.GET("", h.ListReplicaPlans)
		plans.POST("", h.CreateReplicaPlan)
		plans.GET("/:policyName", h.GetReplicaPlan)
		plans.PUT("/:policyName", h.UpdateReplicaPlan)
	}

	// /failover-policies
	policies := rg.Group("/failover-policies")
	{
		policies.GET("", h.ListFailoverPolicies)
		policies.POST("", h.CreateFailoverPolicy)
		policies.GET("/:name", h.GetFailoverPolicy)
		policies.PUT("/:name", h.UpdateFailoverPolicy)
		policies.DELETE("/:name", h.DeleteFailoverPolicy)
	}
}

// ---------------------------------------------------------------------------
// FailoverEvent
// ---------------------------------------------------------------------------

// ListFailoverEvents 列出故障迁移事件。
// GET /api/v1/failover-events?limit=20&offset=0
func (h *FailoverHandler) ListFailoverEvents(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	if offset < 0 {
		offset = 0
	}

	events, total, err := h.store.ListFailoverEvents(tenantID.(string), limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to list: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"items":  events,
		"total":  total,
		"limit":  limit,
		"offset": offset,
	})
}

// GetFailoverEvent 获取单个故障迁移事件。
// GET /api/v1/failover-events/:eventId
func (h *FailoverHandler) GetFailoverEvent(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	eventID := c.Param("eventId")
	e, err := h.store.GetFailoverEvent(tenantID.(string), eventID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "failover event not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, e)
}

// triggerFailoverRequest 手动触发迁移请求体。
type triggerFailoverRequest struct {
	SourceCluster string   `json:"sourceCluster" binding:"required"`
	TargetCluster string   `json:"targetCluster" binding:"required"`
	PolicyName    string   `json:"policyName"`
	Reason        string   `json:"reason"`
	Workloads     []string `json:"workloads"`
}

// TriggerFailover 手动触发故障迁移。
//
// 注意：实际迁移动作由 failover engine 异步执行，本端点仅创建事件记录。
// POST /api/v1/failover-events
func (h *FailoverHandler) TriggerFailover(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	var req triggerFailoverRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body: " + err.Error()})
		return
	}

	reason := req.Reason
	if reason == "" {
		reason = model.ReasonManual
	}

	workloadsJSON, _ := json.Marshal(req.Workloads)

	eventID := "fo-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	e := &model.FailoverEvent{
		EventID:           eventID,
		TenantID:          tenantID.(string),
		SourceCluster:     req.SourceCluster,
		TargetCluster:     req.TargetCluster,
		TriggerReason:     reason,
		PolicyName:        req.PolicyName,
		Status:            model.EventStatusPending,
		AffectedWorkloads: string(workloadsJSON),
		StartedAt:         time.Now(),
	}

	if err := h.store.CreateFailoverEvent(e); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create event: " + err.Error()})
		return
	}

	c.JSON(http.StatusCreated, e)
}

// ---------------------------------------------------------------------------
// ClusterHealth
// ---------------------------------------------------------------------------

// ListClusterHealth 获取所有集群最新健康状态。
// GET /api/v1/clusters/health
func (h *FailoverHandler) ListClusterHealth(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	records, err := h.store.LatestClusterHealth(tenantID.(string))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to list health: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"items": records,
		"total": len(records),
	})
}

// GetClusterHealthHistory 获取集群健康历史。
// GET /api/v1/clusters/:name/health?limit=100
func (h *FailoverHandler) GetClusterHealthHistory(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	clusterName := c.Param("name")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))

	records, err := h.store.ListClusterHealth(tenantID.(string), clusterName, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to list health: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"items":   records,
		"total":   len(records),
		"cluster": clusterName,
	})
}

// ---------------------------------------------------------------------------
// ReplicaWeightPlan
// ---------------------------------------------------------------------------

// ListReplicaPlans 列出副本权重方案。
// GET /api/v1/replica-plans?limit=20&offset=0
func (h *FailoverHandler) ListReplicaPlans(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	if offset < 0 {
		offset = 0
	}

	plans, total, err := h.store.ListReplicaWeightPlans(tenantID.(string), limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to list: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"items":  plans,
		"total":  total,
		"limit":  limit,
		"offset": offset,
	})
}

// GetReplicaPlan 获取单个副本权重方案。
// GET /api/v1/replica-plans/:policyName
func (h *FailoverHandler) GetReplicaPlan(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	policyName := c.Param("policyName")
	p, err := h.store.GetReplicaWeightPlan(tenantID.(string), policyName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "replica plan not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, p)
}

// createReplicaPlanRequest 创建副本权重方案请求体。
type createReplicaPlanRequest struct {
	PolicyName    string         `json:"policyName" binding:"required"`
	Workload      string         `json:"workload" binding:"required"`
	TotalReplicas int            `json:"totalReplicas" binding:"required"`
	Weights       map[string]int `json:"weights" binding:"required"`
	Reason        string         `json:"reason"`
}

// CreateReplicaPlan 计算并保存副本权重方案。
//
// 输入：总副本数 + 各集群权重，按权重比例计算各集群分配副本数，
// 受各集群 maxReplicas 上限约束（由引擎后续校验）。
// POST /api/v1/replica-plans
func (h *FailoverHandler) CreateReplicaPlan(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	var req createReplicaPlanRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body: " + err.Error()})
		return
	}

	if req.TotalReplicas <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "totalReplicas must be positive"})
		return
	}
	if len(req.Weights) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "weights must not be empty"})
		return
	}

	// 计算总权重。
	totalWeight := 0
	for _, w := range req.Weights {
		if w < 0 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "weight must be non-negative"})
			return
		}
		totalWeight += w
	}
	if totalWeight == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "total weight must be positive"})
		return
	}

	// 按权重比例分配副本（最大余数法保证总和一致）。
	allocation := allocateByWeight(req.TotalReplicas, req.Weights, totalWeight)

	allocationJSON, _ := json.Marshal(allocation)
	weightsJSON, _ := json.Marshal(req.Weights)

	reason := req.Reason
	if reason == "" {
		reason = "initial"
	}

	plan := &model.ReplicaWeightPlan{
		TenantID:      tenantID.(string),
		PolicyName:    req.PolicyName,
		Workload:      req.Workload,
		TotalReplicas: req.TotalReplicas,
		Allocation:    string(allocationJSON),
		Weights:       string(weightsJSON),
		Reason:        reason,
	}

	if err := h.store.CreateReplicaWeightPlan(plan); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create: " + err.Error()})
		return
	}

	c.JSON(http.StatusCreated, plan)
}

// updateReplicaPlanRequest 动态调整副本权重请求体。
type updateReplicaPlanRequest struct {
	TotalReplicas *int           `json:"totalReplicas"`
	Weights       map[string]int `json:"weights"`
	Reason        string         `json:"reason"`
}

// UpdateReplicaPlan 动态调整副本权重。
//
// 支持运行时调整总副本数与各集群权重，重新计算分配方案。
// PUT /api/v1/replica-plans/:policyName
func (h *FailoverHandler) UpdateReplicaPlan(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	policyName := c.Param("policyName")
	plan, err := h.store.GetReplicaWeightPlan(tenantID.(string), policyName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "replica plan not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get: " + err.Error()})
		return
	}

	var req updateReplicaPlanRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body: " + err.Error()})
		return
	}

	// 解析当前权重与分配。
	var currentWeights map[string]int
	if err := json.Unmarshal([]byte(plan.Weights), &currentWeights); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to parse weights"})
		return
	}

	// 应用更新。
	if req.TotalReplicas != nil {
		if *req.TotalReplicas <= 0 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "totalReplicas must be positive"})
			return
		}
		plan.TotalReplicas = *req.TotalReplicas
	}
	weights := currentWeights
	if req.Weights != nil {
		weights = req.Weights
	}

	totalWeight := 0
	for _, w := range weights {
		if w < 0 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "weight must be non-negative"})
			return
		}
		totalWeight += w
	}
	if totalWeight == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "total weight must be positive"})
		return
	}

	allocation := allocateByWeight(plan.TotalReplicas, weights, totalWeight)
	allocationJSON, _ := json.Marshal(allocation)
	weightsJSON, _ := json.Marshal(weights)

	plan.Allocation = string(allocationJSON)
	plan.Weights = string(weightsJSON)
	if req.Reason != "" {
		plan.Reason = req.Reason
	} else {
		plan.Reason = "manual"
	}

	if err := h.store.UpdateReplicaWeightPlan(plan); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, plan)
}

// allocateByWeight 按权重分配副本（最大余数法）。
//
// 保证 sum(allocation) == total，且各集群分配比例尽量贴近权重比。
func allocateByWeight(total int, weights map[string]int, totalWeight int) map[string]int {
	result := make(map[string]int)
	keys := make([]string, 0, len(weights))
	for k := range weights {
		keys = append(keys, k)
	}

	// 按集群名稳定排序，保证输出可重现。
	for i := 0; i < len(keys); i++ {
		for j := i + 1; j < len(keys); j++ {
			if keys[i] > keys[j] {
				keys[i], keys[j] = keys[j], keys[i]
			}
		}
	}

	allocated := 0
	remainders := make(map[string]float64, len(keys))
	for _, k := range keys {
		exact := float64(total) * float64(weights[k]) / float64(totalWeight)
		floor := int(exact)
		result[k] = floor
		allocated += floor
		remainders[k] = exact - float64(floor)
	}

	// 把剩余副本按余数大小依次分配。
	remaining := total - allocated
	for remaining > 0 {
		// 找余数最大的集群。
		best := keys[0]
		for _, k := range keys[1:] {
			if remainders[k] > remainders[best] {
				best = k
			}
		}
		result[best]++
		remainders[best] = -1 // 标记已分配完
		remaining--
	}

	return result
}

// ---------------------------------------------------------------------------
// FailoverPolicy
// ---------------------------------------------------------------------------

// ListFailoverPolicies 列出故障迁移策略。
// GET /api/v1/failover-policies?limit=20&offset=0
func (h *FailoverHandler) ListFailoverPolicies(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	if offset < 0 {
		offset = 0
	}

	policies, total, err := h.store.ListFailoverPolicies(tenantID.(string), limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to list: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"items":  policies,
		"total":  total,
		"limit":  limit,
		"offset": offset,
	})
}

// GetFailoverPolicy 获取单个故障迁移策略。
// GET /api/v1/failover-policies/:name?namespace=default
func (h *FailoverHandler) GetFailoverPolicy(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	p, err := h.store.GetFailoverPolicy(tenantID.(string), namespace, name)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "failover policy not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, p)
}

// createFailoverPolicyRequest 创建故障迁移策略请求体。
type createFailoverPolicyRequest struct {
	Name                       string   `json:"name" binding:"required"`
	Namespace                  string   `json:"namespace" binding:"required"`
	PrimaryCluster             string   `json:"primaryCluster" binding:"required"`
	BackupClusters             []string `json:"backupClusters" binding:"required"`
	DetectionWindowSeconds     int      `json:"detectionWindowSeconds"`
	MigrationTimeoutSeconds    int      `json:"migrationTimeoutSeconds"`
	HealthCheckIntervalSeconds int      `json:"healthCheckIntervalSeconds"`
	Enabled                    bool     `json:"enabled"`
}

// CreateFailoverPolicy 创建故障迁移策略。
// POST /api/v1/failover-policies
func (h *FailoverHandler) CreateFailoverPolicy(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	var req createFailoverPolicyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body: " + err.Error()})
		return
	}

	if len(req.BackupClusters) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "backupClusters must not be empty"})
		return
	}

	// 默认值：检测窗口 30s，迁移超时 60s，健康检查间隔 10s。
	if req.DetectionWindowSeconds <= 0 {
		req.DetectionWindowSeconds = 30
	}
	if req.MigrationTimeoutSeconds <= 0 {
		req.MigrationTimeoutSeconds = 60
	}
	if req.HealthCheckIntervalSeconds <= 0 {
		req.HealthCheckIntervalSeconds = 10
	}

	backupJSON, _ := json.Marshal(req.BackupClusters)

	p := &model.FailoverPolicy{
		TenantID:                   tenantID.(string),
		Name:                       req.Name,
		Namespace:                  req.Namespace,
		PrimaryCluster:             req.PrimaryCluster,
		BackupClusters:             string(backupJSON),
		DetectionWindowSeconds:     req.DetectionWindowSeconds,
		MigrationTimeoutSeconds:    req.MigrationTimeoutSeconds,
		HealthCheckIntervalSeconds: req.HealthCheckIntervalSeconds,
		Enabled:                    req.Enabled,
	}

	if err := h.store.CreateFailoverPolicy(p); err != nil {
		if errors.Is(err, store.ErrAlreadyExists) {
			c.JSON(http.StatusConflict, gin.H{"error": "failed to create: " + err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create: " + err.Error()})
		return
	}

	c.JSON(http.StatusCreated, p)
}

// updateFailoverPolicyRequest 更新故障迁移策略请求体。
type updateFailoverPolicyRequest struct {
	PrimaryCluster             *string  `json:"primaryCluster"`
	BackupClusters             []string `json:"backupClusters"`
	DetectionWindowSeconds     *int     `json:"detectionWindowSeconds"`
	MigrationTimeoutSeconds    *int     `json:"migrationTimeoutSeconds"`
	HealthCheckIntervalSeconds *int     `json:"healthCheckIntervalSeconds"`
	Enabled                    *bool    `json:"enabled"`
}

// UpdateFailoverPolicy 更新故障迁移策略。
// PUT /api/v1/failover-policies/:name?namespace=default
func (h *FailoverHandler) UpdateFailoverPolicy(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	p, err := h.store.GetFailoverPolicy(tenantID.(string), namespace, name)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "failover policy not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to get: " + err.Error()})
		return
	}

	var req updateFailoverPolicyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body: " + err.Error()})
		return
	}

	if req.PrimaryCluster != nil {
		p.PrimaryCluster = *req.PrimaryCluster
	}
	if req.BackupClusters != nil {
		if len(req.BackupClusters) == 0 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "backupClusters must not be empty"})
			return
		}
		backupJSON, _ := json.Marshal(req.BackupClusters)
		p.BackupClusters = string(backupJSON)
	}
	if req.DetectionWindowSeconds != nil {
		p.DetectionWindowSeconds = *req.DetectionWindowSeconds
	}
	if req.MigrationTimeoutSeconds != nil {
		p.MigrationTimeoutSeconds = *req.MigrationTimeoutSeconds
	}
	if req.HealthCheckIntervalSeconds != nil {
		p.HealthCheckIntervalSeconds = *req.HealthCheckIntervalSeconds
	}
	if req.Enabled != nil {
		p.Enabled = *req.Enabled
	}

	if err := h.store.UpdateFailoverPolicy(p); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update: " + err.Error()})
		return
	}

	c.JSON(http.StatusOK, p)
}

// DeleteFailoverPolicy 删除故障迁移策略。
// DELETE /api/v1/failover-policies/:name?namespace=default
func (h *FailoverHandler) DeleteFailoverPolicy(c *gin.Context) {
	tenantID, exists := c.Get("tenantId")
	if !exists {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "tenant id not found in token"})
		return
	}

	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", "default")

	if err := h.store.DeleteFailoverPolicy(tenantID.(string), namespace, name); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "failover policy not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to delete: " + err.Error()})
		return
	}

	c.JSON(http.StatusNoContent, nil)
}
