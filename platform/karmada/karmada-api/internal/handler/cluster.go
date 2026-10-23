package handler

// 联邦集群注册/注销 API handler。
//
// P-01 多集群联邦 — 集群注册/注销 API 骨架。
//
// 端点：
//   POST   /api/v1/clusters          注册联邦集群
//   GET    /api/v1/clusters          列出所有联邦集群
//   GET    /api/v1/clusters/:name    获取单个集群信息
//   DELETE /api/v1/clusters/:name    注销联邦集群
//
// TODO: 待异地机房真实验证
// TODO: 添加集群健康检查端点
// TODO: 添加集群工作负载迁移端点

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/karmada-api/internal/karmadaclient"
	"github.com/Levango7/DataEngineBDP/karmada-api/internal/model"
)

// ClusterHandler 联邦集群 handler。
type ClusterHandler struct {
	client *karmadaclient.Client
}

// NewClusterHandler 创建联邦集群 handler。
func NewClusterHandler(client *karmadaclient.Client) *ClusterHandler {
	return &ClusterHandler{client: client}
}

// RegisterRoutes 注册路由。
//
// rg 已由调用方挂到 /api/v1/clusters（见 main.go），这里不能再写 "/clusters"，
// 否则实际路径变成 /api/v1/clusters/clusters，按 /api/v1/clusters 调的客户端必 404。
// 写法与同目录 propagation_policy.go 一致：组路径由调用方给，handler 只写相对段。
func (h *ClusterHandler) RegisterRoutes(rg *gin.RouterGroup) {
	rg.POST("", h.RegisterCluster)
	rg.GET("", h.ListClusters)
	rg.GET("/:name", h.GetCluster)
	rg.DELETE("/:name", h.UnregisterCluster)
}

// RegisterCluster 注册联邦集群。
//
// TODO: 待异地机房真实验证
// TODO: 添加集群 kubeconfig 校验
// TODO: 添加集群唯一性校验
func (h *ClusterHandler) RegisterCluster(c *gin.Context) {
	var req model.ClusterRegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// TODO: 校验集群名称格式
	// TODO: 校验 API Endpoint 可达性
	// TODO: 校验 kubeconfig 有效性

	clusterInfo := karmadaclient.ClusterInfo{
		Name:        req.Name,
		Provider:    req.Provider,
		Region:      req.Region,
		Zone:        req.Zone,
		APIEndpoint: req.APIEndpoint,
		Labels:      req.Labels,
	}

	if err := h.client.RegisterCluster(c.Request.Context(), clusterInfo); err != nil {
		// TODO: 待异地机房真实验证 — 当前 Karmada API 未连接，返回骨架响应
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"error":  "Karmada API 不可达",
			"detail": err.Error(),
			"todo":   "待异地机房真实验证",
		})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"name":   req.Name,
		"status": "registered",
	})
}

// ListClusters 列出所有联邦集群。
//
// TODO: 待异地机房真实验证
func (h *ClusterHandler) ListClusters(c *gin.Context) {
	clusters, err := h.client.ListClusters(c.Request.Context())
	if err != nil {
		// TODO: 待异地机房真实验证 — 当前 Karmada API 未连接，返回空列表
		c.JSON(http.StatusOK, gin.H{
			"clusters": []interface{}{},
			"warning":  "Karmada API 不可达，返回空列表",
			"todo":     "待异地机房真实验证",
		})
		return
	}
	c.JSON(http.StatusOK, gin.H{"clusters": clusters})
}

// GetCluster 获取单个联邦集群信息。
func (h *ClusterHandler) GetCluster(c *gin.Context) {
	name := c.Param("name")
	cluster, err := h.client.GetCluster(c.Request.Context(), name)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"error":  "Karmada API 不可达",
			"detail": err.Error(),
			"todo":   "待异地机房真实验证",
		})
		return
	}
	c.JSON(http.StatusOK, cluster)
}

// UnregisterCluster 注销联邦集群。
//
// TODO: 待异地机房真实验证
// TODO: 注销前检查工作负载
func (h *ClusterHandler) UnregisterCluster(c *gin.Context) {
	name := c.Param("name")

	if err := h.client.UnregisterCluster(c.Request.Context(), name); err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"error":  "Karmada API 不可达",
			"detail": err.Error(),
			"todo":   "待异地机房真实验证",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"name":   name,
		"status": "unregistered",
	})
}
