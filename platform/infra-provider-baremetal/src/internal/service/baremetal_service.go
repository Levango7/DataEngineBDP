// Package service - baremetal_service.go 实现裸金属供应的核心编排逻辑。
//
// BareMetalService 编排以下供应流程:
//  1. 创建集群 → 持久化集群与节点
//  2. 对每个节点: 通过Redfish采集硬件信息 → 设置PXE启动 → 开机
//  3. 等待OS安装完成 → 通过SSH执行kubeadm init/join
//  4. 销毁集群: kubeadm reset → 关机 → 清理DB
package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/sirupsen/logrus"
	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
)

// BareMetalService 裸金属供应服务
type BareMetalService struct {
	db        *gorm.DB
	redfish   *RedfishClient
	k8s       *K8sBootstrapper
	logger    *logrus.Entry
	provision map[string]context.CancelFunc // 正在进行的供应任务取消函数
	mu        sync.RWMutex
}

// NewBareMetalService 创建裸金属供应服务
func NewBareMetalService(db *gorm.DB, redfish *RedfishClient, k8s *K8sBootstrapper, logger *logrus.Entry) *BareMetalService {
	return &BareMetalService{
		db:        db,
		redfish:   redfish,
		k8s:       k8s,
		logger:    logger,
		provision: make(map[string]context.CancelFunc),
	}
}

// AutoMigrate 自动迁移数据库表
func (s *BareMetalService) AutoMigrate() error {
	return s.db.AutoMigrate(&model.BareMetalCluster{}, &model.BareMetalNode{})
}

// CreateCluster 创建裸金属集群
//
// 步骤:
//  1. 校验请求(至少1个control-plane节点)
//  2. 持久化集群与节点(state=pending)
//  3. 异步启动供应流程
func (s *BareMetalService) CreateCluster(ctx context.Context, req *model.CreateClusterRequest) (*model.BareMetalCluster, error) {
	if err := validateCreateRequest(req); err != nil {
		return nil, err
	}

	clusterID := uuid.New().String()
	now := time.Now()

	cpCount, workerCount := countNodes(req.Nodes)
	labelsJSON, _ := json.Marshal(req.Labels)

	cluster := &model.BareMetalCluster{
		ID:                       clusterID,
		Name:                     req.Name,
		State:                    model.ClusterStateCreating,
		Description:              req.Description,
		K8sVersion:               req.K8s.KubernetesVersion,
		PodCIDR:                  req.K8s.PodCIDR,
		ServiceCIDR:              req.K8s.ServiceCIDR,
		APIServerPort:            req.K8s.APIServerPort,
		ImageRepository:          req.K8s.ImageRepository,
		ControlPlaneVIP:          req.K8s.ControlPlaneVIP,
		ControlPlaneVIPInterface: req.K8s.ControlPlaneVIPInterface,
		NetworkPlugin:            req.K8s.NetworkPlugin,
		CloudProvider:            req.K8s.CloudProvider,
		NodeCount:                len(req.Nodes),
		ControlPlaneCount:        cpCount,
		WorkerCount:              workerCount,
		LabelsJSON:               string(labelsJSON),
	}

	tx := s.db.Begin()
	if err := tx.Create(cluster).Error; err != nil {
		tx.Rollback()
		return nil, fmt.Errorf("创建集群记录失败: %w", err)
	}

	for _, spec := range req.Nodes {
		node := buildNodeFromSpec(clusterID, spec)
		if err := tx.Create(node).Error; err != nil {
			tx.Rollback()
			return nil, fmt.Errorf("创建节点记录失败(hostname=%s): %w", spec.Hostname, err)
		}
	}

	if err := tx.Commit().Error; err != nil {
		return nil, fmt.Errorf("提交事务失败: %w", err)
	}

	// 异步启动供应流程
	provisionCtx, cancel := context.WithCancel(context.Background())
	s.mu.Lock()
	s.provision[clusterID] = cancel
	s.mu.Unlock()

	go s.provisionCluster(provisionCtx, clusterID, req)

	s.logger.WithField("cluster_id", clusterID).Info("集群创建成功，开始异步供应")
	_ = now // now 保留用于未来同步供应场景
	return cluster, nil
}

// GetCluster 查询集群详情
func (s *BareMetalService) GetCluster(ctx context.Context, clusterID string) (*model.ClusterDetail, error) {
	var cluster model.BareMetalCluster
	if err := s.db.First(&cluster, "id = ?", clusterID).Error; err != nil {
		return nil, fmt.Errorf("集群不存在: %w", err)
	}
	var nodes []model.BareMetalNode
	if err := s.db.Find(&nodes, "cluster_id = ?", clusterID).Error; err != nil {
		return nil, fmt.Errorf("查询节点失败: %w", err)
	}
	return &model.ClusterDetail{Cluster: cluster, Nodes: nodes}, nil
}

// DeleteCluster 销毁集群
//
// 步骤:
//  1. 取消正在进行的供应任务
//  2. 对每个节点: kubeadm reset → Redfish关机
//  3. 删除DB记录
func (s *BareMetalService) DeleteCluster(ctx context.Context, clusterID string) error {
	var cluster model.BareMetalCluster
	if err := s.db.First(&cluster, "id = ?", clusterID).Error; err != nil {
		return fmt.Errorf("集群不存在: %w", err)
	}

	// 取消正在进行的供应
	s.mu.Lock()
	if cancel, ok := s.provision[clusterID]; ok {
		cancel()
		delete(s.provision, clusterID)
	}
	s.mu.Unlock()

	cluster.State = model.ClusterStateDestroying
	s.db.Save(&cluster)

	var nodes []model.BareMetalNode
	s.db.Find(&nodes, "cluster_id = ?", clusterID)

	// 并发销毁节点
	var wg sync.WaitGroup
	for i := range nodes {
		wg.Add(1)
		go func(node *model.BareMetalNode) {
			defer wg.Done()
			s.destroyNode(ctx, node)
		}(&nodes[i])
	}
	wg.Wait()

	// 删除DB记录
	s.db.Where("cluster_id = ?", clusterID).Delete(&model.BareMetalNode{})
	cluster.State = model.ClusterStateDestroyed
	s.db.Save(&cluster)

	s.logger.WithField("cluster_id", clusterID).Info("集群销毁完成")
	return nil
}

// ListNodes 查询集群节点列表
func (s *BareMetalService) ListNodes(ctx context.Context, clusterID string) ([]model.BareMetalNode, error) {
	var nodes []model.BareMetalNode
	if err := s.db.Find(&nodes, "cluster_id = ?", clusterID).Error; err != nil {
		return nil, fmt.Errorf("查询节点失败: %w", err)
	}
	return nodes, nil
}

// ScaleCluster 扩缩容
func (s *BareMetalService) ScaleCluster(ctx context.Context, clusterID string, req *model.ScaleRequest) error {
	var cluster model.BareMetalCluster
	if err := s.db.First(&cluster, "id = ?", clusterID).Error; err != nil {
		return fmt.Errorf("集群不存在: %w", err)
	}
	if cluster.State != model.ClusterStateRunning {
		return fmt.Errorf("集群状态非running，无法扩缩容(当前: %s)", cluster.State)
	}

	cluster.State = model.ClusterStateScaling
	s.db.Save(&cluster)

	switch req.Action {
	case "add":
		return s.scaleOut(ctx, &cluster, req.Nodes)
	case "remove":
		return s.scaleIn(ctx, &cluster, req.Nodes)
	default:
		return fmt.Errorf("未知动作: %s", req.Action)
	}
}

// ListClusters 列出所有集群
func (s *BareMetalService) ListClusters(ctx context.Context) ([]model.BareMetalCluster, error) {
	var clusters []model.BareMetalCluster
	if err := s.db.Find(&clusters).Error; err != nil {
		return nil, fmt.Errorf("查询集群列表失败: %w", err)
	}
	return clusters, nil
}

// provisionCluster 异步供应流程
func (s *BareMetalService) provisionCluster(ctx context.Context, clusterID string, _ *model.CreateClusterRequest) {
	logger := s.logger.WithField("cluster_id", clusterID)

	var cluster model.BareMetalCluster
	if err := s.db.First(&cluster, "id = ?", clusterID).Error; err != nil {
		logger.WithError(err).Error("加载集群失败")
		return
	}

	// 阶段1: 通过Redfish采集硬件信息并设置PXE启动
	logger.Info("阶段1: 采集硬件信息并设置PXE启动")
	var nodes []model.BareMetalNode
	s.db.Find(&nodes, "cluster_id = ?", clusterID)

	for i := range nodes {
		if ctx.Err() != nil {
			return
		}
		node := &nodes[i]
		if err := s.provisionNodeHardware(ctx, node); err != nil {
			logger.WithError(err).WithField("node", node.Hostname).Error("节点硬件供应失败")
			node.State = model.NodeStateFailed
			node.LastError = err.Error()
			s.db.Save(node)
			s.markClusterFailed(clusterID, err)
			return
		}
	}

	// 阶段2: 等待OS安装完成(简化: 标记为ready)
	logger.Info("阶段2: 等待OS安装完成")
	for i := range nodes {
		nodes[i].State = model.NodeStateReady
		s.db.Save(&nodes[i])
	}

	// 阶段3: K8s集群初始化
	logger.Info("阶段3: K8s集群初始化")
	cluster.State = model.ClusterStateProvisioning
	s.db.Save(&cluster)

	if err := s.bootstrapK8s(ctx, &cluster, nodes); err != nil {
		logger.WithError(err).Error("K8s初始化失败")
		s.markClusterFailed(clusterID, err)
		return
	}

	now := time.Now()
	cluster.State = model.ClusterStateRunning
	cluster.ProvisionedAt = &now
	s.db.Save(&cluster)
	logger.Info("集群供应完成")
}

// provisionNodeHardware 单节点硬件供应: 采集硬件信息 → 设置PXE → 开机
func (s *BareMetalService) provisionNodeHardware(ctx context.Context, node *model.BareMetalNode) error {
	bmc := node.ToSpec().BMC

	// 健康检查BMC
	if err := s.redfish.HealthCheck(ctx, bmc); err != nil {
		return fmt.Errorf("BMC健康检查失败: %w", err)
	}

	// 列出系统以获取systemID
	systems, err := s.redfish.ListSystems(ctx, bmc)
	if err != nil {
		return fmt.Errorf("列出Redfish系统失败: %w", err)
	}
	if len(systems) == 0 {
		return errors.New("BMC上未发现任何系统")
	}

	// 取第一个系统(生产环境应根据SKU/Serial匹配)
	sys := systems[0]
	node.RedfishSystemID = sys.ID

	// 采集硬件信息
	hw, err := s.redfish.CollectHardwareInfo(ctx, bmc, sys.ID)
	if err == nil {
		node.HardwareInfo = *hw
	}

	node.State = model.NodeStatePoweringOn
	s.db.Save(node)

	// 设置PXE启动并开机
	if err := s.redfish.EnsurePXEBoot(ctx, bmc, sys.ID, true); err != nil {
		return fmt.Errorf("设置PXE启动失败: %w", err)
	}

	node.State = model.NodeStatePXEBooting
	s.db.Save(node)

	return nil
}

// bootstrapK8s K8s集群初始化编排
func (s *BareMetalService) bootstrapK8s(ctx context.Context, cluster *model.BareMetalCluster, nodes []model.BareMetalNode) error {
	if s.k8s == nil {
		s.logger.Warn("K8s引导器未配置，跳过K8s初始化")
		return nil
	}

	var controlPlanes, workers []model.BareMetalNode
	for i := range nodes {
		if nodes[i].Role == model.NodeRoleControlPlane {
			controlPlanes = append(controlPlanes, nodes[i])
		} else {
			workers = append(workers, nodes[i])
		}
	}

	if len(controlPlanes) == 0 {
		return errors.New("无控制平面节点")
	}

	// 初始化第一个控制平面
	cp0 := &controlPlanes[0]
	cp0.State = model.NodeStateJoining
	s.db.Save(cp0)

	result, err := s.k8s.InitControlPlane(ctx, cp0, cluster.K8sVersion, cluster.ControlPlaneVIP)
	if err != nil {
		return fmt.Errorf("初始化控制平面失败: %w", err)
	}

	cluster.JoinKey = result.JoinToken
	cluster.JoinCertHash = result.JoinCertHash
	s.db.Save(cluster)

	now := time.Now()
	cp0.State = model.NodeStateRunning
	cp0.JoinedAt = &now
	cp0.K8sNodeName = cp0.Hostname
	s.db.Save(cp0)

	// join endpoint
	endpoint := s.k8s.GenerateJoinEndpoint(cluster.ControlPlaneVIP, controlPlanes)

	// 其余控制平面加入(简化: 暂不处理多控制平面的特殊join)
	for i := 1; i < len(controlPlanes); i++ {
		cp := &controlPlanes[i]
		cp.State = model.NodeStateJoining
		s.db.Save(cp)
		if err := s.k8s.JoinNode(ctx, cp, endpoint, result.JoinToken, result.JoinCertHash); err != nil {
			cp.State = model.NodeStateFailed
			cp.LastError = err.Error()
			s.db.Save(cp)
			return err
		}
		cp.State = model.NodeStateRunning
		cp.JoinedAt = &now
		cp.K8sNodeName = cp.Hostname
		s.db.Save(cp)
	}

	// 工作节点加入
	for i := range workers {
		w := &workers[i]
		w.State = model.NodeStateJoining
		s.db.Save(w)
		if err := s.k8s.JoinNode(ctx, w, endpoint, result.JoinToken, result.JoinCertHash); err != nil {
			w.State = model.NodeStateFailed
			w.LastError = err.Error()
			s.db.Save(w)
			return err
		}
		w.State = model.NodeStateRunning
		w.JoinedAt = &now
		w.K8sNodeName = w.Hostname
		s.db.Save(w)
	}

	// 安装CNI
	if cluster.NetworkPlugin != "" {
		_ = s.k8s.InstallCNI(ctx, cp0, cluster.NetworkPlugin)
	}

	return nil
}

// scaleOut 扩容
func (s *BareMetalService) scaleOut(_ context.Context, cluster *model.BareMetalCluster, specs []model.NodeSpec) error {
	for _, spec := range specs {
		node := buildNodeFromSpec(cluster.ID, spec)
		if err := s.db.Create(node).Error; err != nil {
			return err
		}
		// 异步供应新节点(简化: 仅硬件供应)
		go func(n *model.BareMetalNode) {
			provisionCtx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
			defer cancel()
			if err := s.provisionNodeHardware(provisionCtx, n); err != nil {
				n.State = model.NodeStateFailed
				n.LastError = err.Error()
				s.db.Save(n)
			}
		}(node)
	}
	cluster.State = model.ClusterStateRunning
	cluster.NodeCount += len(specs)
	if specs[0].Role == model.NodeRoleWorker {
		cluster.WorkerCount += len(specs)
	} else {
		cluster.ControlPlaneCount += len(specs)
	}
	s.db.Save(cluster)
	return nil
}

// scaleIn 缩容
func (s *BareMetalService) scaleIn(ctx context.Context, cluster *model.BareMetalCluster, specs []model.NodeSpec) error {
	for _, spec := range specs {
		var node model.BareMetalNode
		if err := s.db.First(&node, "cluster_id = ? AND hostname = ?", cluster.ID, spec.Hostname).Error; err != nil {
			continue
		}
		node.State = model.NodeStateRemoving
		s.db.Save(&node)
		s.destroyNode(ctx, &node)
		s.db.Delete(&node)
	}
	cluster.State = model.ClusterStateRunning
	cluster.NodeCount -= len(specs)
	s.db.Save(cluster)
	return nil
}

// destroyNode 销毁单个节点
func (s *BareMetalService) destroyNode(ctx context.Context, node *model.BareMetalNode) {
	if s.k8s != nil {
		_ = s.k8s.ResetNode(ctx, node)
	}
	if node.RedfishSystemID != "" {
		_ = s.redfish.PowerOffGracefully(ctx, node.ToSpec().BMC, node.RedfishSystemID)
	}
}

// markClusterFailed 标记集群失败
func (s *BareMetalService) markClusterFailed(clusterID string, err error) {
	s.db.Model(&model.BareMetalCluster{}).Where("id = ?", clusterID).Updates(map[string]interface{}{
		"state":      model.ClusterStateFailed,
		"last_error": err.Error(),
	})
}

// validateCreateRequest 校验创建集群请求
func validateCreateRequest(req *model.CreateClusterRequest) error {
	if strings.TrimSpace(req.Name) == "" {
		return errors.New("集群名称不能为空")
	}
	if len(req.Nodes) == 0 {
		return errors.New("节点列表不能为空")
	}
	hasControlPlane := false
	hostnames := make(map[string]struct{})
	for i, n := range req.Nodes {
		if strings.TrimSpace(n.Hostname) == "" {
			return fmt.Errorf("第%d个节点hostname为空", i+1)
		}
		if _, dup := hostnames[n.Hostname]; dup {
			return fmt.Errorf("节点hostname重复: %s", n.Hostname)
		}
		hostnames[n.Hostname] = struct{}{}
		if n.BMC.Host == "" {
			return fmt.Errorf("节点 %s 缺少BMC地址", n.Hostname)
		}
		if n.Role == model.NodeRoleControlPlane {
			hasControlPlane = true
		}
	}
	if !hasControlPlane {
		return errors.New("至少需要一个control-plane节点")
	}
	if req.K8s.KubernetesVersion == "" {
		return errors.New("K8s版本不能为空")
	}
	if req.K8s.PodCIDR == "" {
		return errors.New("PodCIDR不能为空")
	}
	if req.K8s.ServiceCIDR == "" {
		return errors.New("ServiceCIDR不能为空")
	}
	return nil
}

// countNodes 统计控制平面与工作节点数
func countNodes(nodes []model.NodeSpec) (cp, worker int) {
	for _, n := range nodes {
		if n.Role == model.NodeRoleControlPlane {
			cp++
		} else {
			worker++
		}
	}
	return
}

// buildNodeFromSpec 从规格构建节点运行时模型
func buildNodeFromSpec(clusterID string, spec model.NodeSpec) *model.BareMetalNode {
	labelsJSON, _ := json.Marshal(spec.Labels)
	osImage := spec.OSImage
	if osImage == "" {
		osImage = "ubuntu-22.04-server-cloudimg-amd64"
	}
	return &model.BareMetalNode{
		UUID:              uuid.New().String(),
		ClusterID:         clusterID,
		Hostname:          spec.Hostname,
		Role:              spec.Role,
		State:             model.NodeStatePending,
		ManagementIP:      spec.ManagementIP,
		ManagementCIDR:    spec.ManagementCIDR,
		ManagementGateway: spec.ManagementGateway,
		Nameserver:        spec.Nameserver,
		OSImage:           osImage,
		BMCHost:           spec.BMC.Host,
		BMCUsername:       spec.BMC.Username,
		BMCPassword:       spec.BMC.Password,
		BMCVendor:         spec.BMC.Vendor,
		LabelsJSON:        string(labelsJSON),
	}
}
