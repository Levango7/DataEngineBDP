// Package model - cluster.go 定义裸金属K8s集群模型。
package model

import (
	"time"
)

// ClusterState 集群状态机
type ClusterState string

const (
	ClusterStateCreating     ClusterState = "creating"     // 正在供应物理机
	ClusterStateProvisioning ClusterState = "provisioning" // 正在初始化K8s
	ClusterStateRunning      ClusterState = "running"      // 集群就绪
	ClusterStateScaling      ClusterState = "scaling"      // 扩缩容中
	ClusterStateDestroying   ClusterState = "destroying"   // 销毁中
	ClusterStateFailed       ClusterState = "failed"       // 失败
	ClusterStateDestroyed    ClusterState = "destroyed"    // 已销毁
)

// K8sConfig K8s集群配置
type K8sConfig struct {
	// KubernetesVersion K8s版本(如 v1.29.2)
	KubernetesVersion string `json:"kubernetes_version" validate:"required"`
	// PodCIDR Pod网络CIDR
	PodCIDR string `json:"pod_cidr" validate:"required,cidr"`
	// ServiceCIDR Service网络CIDR
	ServiceCIDR string `json:"service_cidr" validate:"required,cidr"`
	// APIServerPort API Server监听端口
	APIServerPort int `json:"api_server_port" validate:"required,min=1,max=65535"`
	// ImageRepository 镜像仓库
	ImageRepository string `json:"image_repository,omitempty"`
	// ControlPlaneVIP 控制平面VIP(用于多控制平面高可用)
	ControlPlaneVIP string `json:"control_plane_vip,omitempty"`
	// ControlPlaneVIPInterface 控制平面VIP绑定的网卡名
	ControlPlaneVIPInterface string `json:"control_plane_vip_iface,omitempty"`
	// CloudProvider 云提供商(裸金属场景固定为空或"external")
	CloudProvider string `json:"cloud_provider,omitempty"`
	// NetworkPlugin CNI: calico / flannel / cilium
	NetworkPlugin string `json:"network_plugin,omitempty"`
	// ExtraArgs kubeadm额外参数
	ExtraArgs map[string]string `json:"extra_args,omitempty"`
}

// CreateClusterRequest 创建集群请求
type CreateClusterRequest struct {
	// Name 集群名称
	Name string `json:"name" validate:"required,min=1,max=63"`
	// K8s K8s配置
	K8s K8sConfig `json:"k8s" validate:"required"`
	// Nodes 物理节点列表(至少1个control-plane)
	Nodes []NodeSpec `json:"nodes" validate:"required,min=1"`
	// Description 集群描述
	Description string `json:"description,omitempty"`
	// Labels 集群标签
	Labels map[string]string `json:"labels,omitempty"`
}

// BareMetalCluster 裸金属K8s集群运行时模型
type BareMetalCluster struct {
	// ID 集群UUID(对外暴露)
	ID string `json:"id" gorm:"primaryKey;size:36;not null"`
	// Name 集群名称
	Name string `json:"name" gorm:"size:63;not null"`
	// State 集群状态
	State ClusterState `json:"state" gorm:"size:32;not null;default:creating"`
	// Description 集群描述
	Description string `json:"description,omitempty" gorm:"size:255"`
	// K8sVersion K8s版本
	K8sVersion string `json:"k8s_version" gorm:"column:k8s_version;size:32;not null"`
	// PodCIDR Pod网络CIDR
	PodCIDR string `json:"pod_cidr" gorm:"column:pod_cidr;size:64;not null"`
	// ServiceCIDR Service网络CIDR
	ServiceCIDR string `json:"service_cidr" gorm:"column:service_cidr;size:64;not null"`
	// APIServerPort API Server端口
	APIServerPort int `json:"api_server_port" gorm:"column:api_server_port;not null"`
	// ImageRepository 镜像仓库
	ImageRepository string `json:"image_repository,omitempty" gorm:"column:image_repository;size:255"`
	// ControlPlaneVIP 控制平面VIP
	ControlPlaneVIP string `json:"control_plane_vip,omitempty" gorm:"column:control_plane_vip;size:64"`
	// ControlPlaneVIPInterface VIP网卡
	ControlPlaneVIPInterface string `json:"control_plane_vip_iface,omitempty" gorm:"column:control_plane_vip_iface;size:64"`
	// NetworkPlugin CNI
	NetworkPlugin string `json:"network_plugin,omitempty" gorm:"column:network_plugin;size:32"`
	// CloudProvider 云提供商
	CloudProvider string `json:"cloud_provider,omitempty" gorm:"column:cloud_provider;size:32"`
	// NodeCount 节点总数(冗余字段，便于查询)
	NodeCount int `json:"node_count" gorm:"column:node_count;default:0"`
	// ControlPlaneCount 控制平面节点数
	ControlPlaneCount int `json:"control_plane_count" gorm:"column:control_plane_count;default:0"`
	// WorkerCount 工作节点数
	WorkerCount int `json:"worker_count" gorm:"column:worker_count;default:0"`
	// KubeconfigPath kubeconfig存储路径
	KubeconfigPath string `json:"-" gorm:"column:kubeconfig_path;size:255"`
	// JoinKey kubeadm join token(内部使用)
	JoinKey string `json:"-" gorm:"column:join_key;size:64"`
	// JoinCertHash kubeadm discovery token ca cert hash(内部使用)
	JoinCertHash string `json:"-" gorm:"column:join_cert_hash;size:128"`
	// LastError 最近错误
	LastError string `json:"last_error,omitempty" gorm:"column:last_error;type:text"`
	// LabelsJSON 集群标签(JSON序列化)
	LabelsJSON string `json:"-" gorm:"column:labels;type:text"`
	// CreatedAt 创建时间
	CreatedAt time.Time `json:"created_at" gorm:"autoCreateTime"`
	// UpdatedAt 更新时间
	UpdatedAt time.Time `json:"updated_at" gorm:"autoUpdateTime"`
	// ProvisionedAt 集群就绪时间
	ProvisionedAt *time.Time `json:"provisioned_at,omitempty" gorm:"column:provisioned_at"`
}

// TableName 指定GORM表名
func (BareMetalCluster) TableName() string {
	return "baremetal_clusters"
}

// ClusterDetail 集群详情(查询响应)
type ClusterDetail struct {
	Cluster BareMetalCluster `json:"cluster"`
	Nodes   []BareMetalNode  `json:"nodes"`
}

// ClusterListResponse 集群列表响应
type ClusterListResponse struct {
	Total    int                `json:"total"`
	Clusters []BareMetalCluster `json:"clusters"`
}

// HealthResponse 健康检查响应
type HealthResponse struct {
	Status    string            `json:"status"`
	Version   string            `json:"version"`
	Timestamp time.Time         `json:"timestamp"`
	Checks    map[string]string `json:"checks,omitempty"`
}

// APIResponse 统一API响应封装
type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

// APIError 统一错误响应
type APIError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Detail  string `json:"detail,omitempty"`
}
