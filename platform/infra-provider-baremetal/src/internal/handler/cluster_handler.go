// Package handler 实现REST API HTTP handler。
//
// cluster_handler.go 暴露裸金属集群供应的REST API:
//
//	POST   /api/v1/clusters/baremetal          - 创建集群
//	DELETE /api/v1/clusters/baremetal/{id}      - 销毁集群
//	GET    /api/v1/clusters/baremetal/{id}      - 查询状态
//	GET    /api/v1/clusters/baremetal           - 列出集群
//	GET    /api/v1/clusters/baremetal/{id}/nodes - 查询节点列表
//	POST   /api/v1/clusters/baremetal/{id}/scale - 扩缩容
package handler

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/service"
)

// ClusterHandler 集群API handler
type ClusterHandler struct {
	svc    *service.BareMetalService
	logger *logrus.Entry
}

// NewClusterHandler 创建集群handler
func NewClusterHandler(svc *service.BareMetalService, logger *logrus.Entry) *ClusterHandler {
	return &ClusterHandler{svc: svc, logger: logger}
}

// RegisterRoutes 注册集群相关路由
func (h *ClusterHandler) RegisterRoutes(rg *gin.RouterGroup) {
	rg.POST("/clusters/baremetal", h.CreateCluster)
	rg.GET("/clusters/baremetal", h.ListClusters)
	rg.GET("/clusters/baremetal/:id", h.GetCluster)
	rg.DELETE("/clusters/baremetal/:id", h.DeleteCluster)
	rg.GET("/clusters/baremetal/:id/nodes", h.ListNodes)
	rg.POST("/clusters/baremetal/:id/scale", h.ScaleCluster)
}

// CreateCluster 创建裸金属集群
// POST /api/v1/clusters/baremetal
func (h *ClusterHandler) CreateCluster(c *gin.Context) {
	var req model.CreateClusterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "请求体解析失败",
			Data:    err.Error(),
		})
		return
	}

	cluster, err := h.svc.CreateCluster(c.Request.Context(), &req)
	if err != nil {
		h.logger.WithError(err).Error("创建集群失败")
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "创建集群失败",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, model.APIResponse{
		Code:    http.StatusCreated,
		Message: "集群创建成功，正在异步供应",
		Data:    cluster,
	})
}

// GetCluster 查询集群状态
// GET /api/v1/clusters/baremetal/{id}
func (h *ClusterHandler) GetCluster(c *gin.Context) {
	clusterID := c.Param("id")
	if clusterID == "" {
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "集群ID不能为空",
		})
		return
	}

	detail, err := h.svc.GetCluster(c.Request.Context(), clusterID)
	if err != nil {
		if strings.Contains(err.Error(), "not found") || strings.Contains(err.Error(), "集群不存在") {
			c.JSON(http.StatusNotFound, model.APIResponse{
				Code:    http.StatusNotFound,
				Message: "集群不存在",
			})
			return
		}
		h.logger.WithError(err).Error("查询集群失败")
		c.JSON(http.StatusInternalServerError, model.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "查询集群失败",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, model.APIResponse{
		Code:    http.StatusOK,
		Message: "success",
		Data:    detail,
	})
}

// ListClusters 列出所有集群
// GET /api/v1/clusters/baremetal
func (h *ClusterHandler) ListClusters(c *gin.Context) {
	clusters, err := h.svc.ListClusters(c.Request.Context())
	if err != nil {
		h.logger.WithError(err).Error("列出集群失败")
		c.JSON(http.StatusInternalServerError, model.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "列出集群失败",
			Data:    err.Error(),
		})
		return
	}
	c.JSON(http.StatusOK, model.APIResponse{
		Code:    http.StatusOK,
		Message: "success",
		Data: model.ClusterListResponse{
			Total:    len(clusters),
			Clusters: clusters,
		},
	})
}

// DeleteCluster 销毁集群
// DELETE /api/v1/clusters/baremetal/{id}
func (h *ClusterHandler) DeleteCluster(c *gin.Context) {
	clusterID := c.Param("id")
	if clusterID == "" {
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "集群ID不能为空",
		})
		return
	}

	if err := h.svc.DeleteCluster(c.Request.Context(), clusterID); err != nil {
		if strings.Contains(err.Error(), "集群不存在") {
			c.JSON(http.StatusNotFound, model.APIResponse{
				Code:    http.StatusNotFound,
				Message: "集群不存在",
			})
			return
		}
		h.logger.WithError(err).Error("销毁集群失败")
		c.JSON(http.StatusInternalServerError, model.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "销毁集群失败",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, model.APIResponse{
		Code:    http.StatusOK,
		Message: "集群销毁成功",
	})
}

// ListNodes 查询集群节点列表
// GET /api/v1/clusters/baremetal/{id}/nodes
func (h *ClusterHandler) ListNodes(c *gin.Context) {
	clusterID := c.Param("id")
	if clusterID == "" {
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "集群ID不能为空",
		})
		return
	}

	nodes, err := h.svc.ListNodes(c.Request.Context(), clusterID)
	if err != nil {
		h.logger.WithError(err).Error("查询节点列表失败")
		c.JSON(http.StatusInternalServerError, model.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "查询节点列表失败",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, model.APIResponse{
		Code:    http.StatusOK,
		Message: "success",
		Data:    nodes,
	})
}

// ScaleCluster 扩缩容
// POST /api/v1/clusters/baremetal/{id}/scale
func (h *ClusterHandler) ScaleCluster(c *gin.Context) {
	clusterID := c.Param("id")
	if clusterID == "" {
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "集群ID不能为空",
		})
		return
	}

	var req model.ScaleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "请求体解析失败",
			Data:    err.Error(),
		})
		return
	}

	if err := h.svc.ScaleCluster(c.Request.Context(), clusterID, &req); err != nil {
		if strings.Contains(err.Error(), "集群不存在") {
			c.JSON(http.StatusNotFound, model.APIResponse{
				Code:    http.StatusNotFound,
				Message: "集群不存在",
			})
			return
		}
		h.logger.WithError(err).Error("扩缩容失败")
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "扩缩容失败",
			Data:    err.Error(),
		})
		return
	}

	c.JSON(http.StatusAccepted, model.APIResponse{
		Code:    http.StatusAccepted,
		Message: "扩缩容请求已接受",
	})
}
