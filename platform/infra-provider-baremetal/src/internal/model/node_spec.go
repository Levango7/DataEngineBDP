// Package model 定义裸金属供应Provider的核心数据模型。
//
// node_spec.go 描述物理节点规格、BMC带外管理凭据、硬件信息等。
package model

import (
	"time"
)

// NodeRole 节点角色: control-plane / worker
type NodeRole string

const (
	NodeRoleControlPlane NodeRole = "control-plane"
	NodeRoleWorker       NodeRole = "worker"
)

// NodeState 节点供应状态机
type NodeState string

const (
	NodeStatePending      NodeState = "pending"       // 已纳入集群但未启动供应
	NodeStatePoweringOn   NodeState = "powering_on"   // 正在通过BMC开机
	NodeStatePXEBooting   NodeState = "pxe_booting"   // 已设置PXE启动并重启
	NodeStateOSInstalling NodeState = "os_installing" // OS正在通过PXE/cloud-init安装
	NodeStateReady        NodeState = "ready"         // OS就绪，等待加入K8s
	NodeStateJoining      NodeState = "joining"       // kubeadm join 进行中
	NodeStateRunning      NodeState = "running"       // 已成功加入集群
	NodeStateFailed       NodeState = "failed"        // 供应失败
	NodeStateRemoving     NodeState = "removing"      // 正在从集群移除
	NodeStateRemoved      NodeState = "removed"       // 已移除
)

// PowerState Redfish电源状态
type PowerState string

const (
	PowerOn    PowerState = "On"
	PowerOff   PowerState = "Off"
	PowerReset PowerState = "Reset"
)

// BootSourceOverride Redfish启动源覆盖
type BootSourceOverride string

const (
	BootOnce       BootSourceOverride = "Once"
	BootContinuous BootSourceOverride = "Continuous"
)

// BootSourceType Redfish启动设备类型
type BootSourceType string

const (
	BootPxe       BootSourceType = "Pxe"
	BootHdd       BootSourceType = "Hdd"
	BootCd        BootSourceType = "Cd"
	BootBiosSetup BootSourceType = "BiosSetup"
)

// BMCCredential BMC带外管理凭据
type BMCCredential struct {
	// Host BMC管理地址(IP:Port或仅IP)
	Host string `json:"host" yaml:"host" gorm:"column:bmc_host"`
	// Username BMC登录用户名
	Username string `json:"username" yaml:"username" gorm:"column:bmc_username"`
	// Password BMC登录密码(明文，由Provider内部使用)
	Password string `json:"password,omitempty" yaml:"password" gorm:"column:bmc_password"`
	// Vendor BMC厂商: dell / hpe / lenovo / supermicro / generic
	Vendor string `json:"vendor,omitempty" yaml:"vendor" gorm:"column:bmc_vendor"`
}

// HardwareInfo 硬件信息(通过Redfish采集)
type HardwareInfo struct {
	// Manufacturer 厂商
	Manufacturer string `json:"manufacturer,omitempty" gorm:"column:hw_manufacturer"`
	// Model 型号
	Model string `json:"model,omitempty" gorm:"column:hw_model"`
	// SerialNumber 序列号
	SerialNumber string `json:"serial_number,omitempty" gorm:"column:hw_serial"`
	// CPUCount CPU插槽数
	CPUCount int `json:"cpu_count,omitempty" gorm:"column:hw_cpu_count"`
	// CPUCores 每颗CPU核心数
	CPUCores int `json:"cpu_cores,omitempty" gorm:"column:hw_cpu_cores"`
	// CPUModel CPU型号
	CPUModel string `json:"cpu_model,omitempty" gorm:"column:hw_cpu_model"`
	// MemoryGB 内存容量(GB)
	MemoryGB int `json:"memory_gb,omitempty" gorm:"column:hw_memory_gb"`
	// Disks 磁盘列表
	Disks []DiskInfo `json:"disks,omitempty" gorm:"-"`
	// DiskSummary 磁盘摘要(序列化存储)
	DiskSummary string `json:"disk_summary,omitempty" gorm:"column:hw_disk_summary"`
	// NICCount 网卡数量
	NICCount int `json:"nic_count,omitempty" gorm:"column:hw_nic_count"`
}

// DiskInfo 单块磁盘信息
type DiskInfo struct {
	Name     string `json:"name"`
	Capacity int    `json:"capacity_gb"` // GB
	Type     string `json:"type"`        // ssd/hdd/nvme
}

// NodeSpec 单个物理节点规格(创建集群时由用户指定)
type NodeSpec struct {
	// Hostname 节点主机名(也是K8s节点名)
	Hostname string `json:"hostname" validate:"required"`
	// Role 节点角色
	Role NodeRole `json:"role" validate:"required,oneof=control-plane worker"`
	// BMC BMC带外管理凭据
	BMC BMCCredential `json:"bmc" validate:"required"`
	// ManagementIP 节点管理网IP(OS安装后使用)
	ManagementIP string `json:"management_ip" validate:"required,ip"`
	// ManagementCIDR 管理网CIDR(如 192.168.10.20/24)
	ManagementCIDR string `json:"management_cidr,omitempty"`
	// ManagementGateway 管理网网关
	ManagementGateway string `json:"management_gateway,omitempty"`
	// Nameserver DNS服务器
	Nameserver string `json:"nameserver,omitempty"`
	// OSImage OS镜像名称(覆盖pxe.default_image)
	OSImage string `json:"os_image,omitempty"`
	// Labels K8s节点标签
	Labels map[string]string `json:"labels,omitempty"`
}

// BareMetalNode 物理节点运行时模型(持久化到DB)
type BareMetalNode struct {
	// ID 内部主键
	ID uint `json:"id" gorm:"primaryKey;autoIncrement"`
	// UUID 节点UUID(对外暴露)
	UUID string `json:"uuid" gorm:"uniqueIndex;size:36;not null"`
	// ClusterID 所属集群ID
	ClusterID string `json:"cluster_id" gorm:"index;size:36;not null"`
	// Hostname 主机名
	Hostname string `json:"hostname" gorm:"size:255;not null"`
	// Role 节点角色
	Role NodeRole `json:"role" gorm:"size:32;not null"`
	// State 供应状态
	State NodeState `json:"state" gorm:"size:32;not null;default:pending"`
	// ManagementIP 管理网IP
	ManagementIP string `json:"management_ip" gorm:"size:64"`
	// ManagementCIDR 管理网CIDR
	ManagementCIDR string `json:"management_cidr,omitempty" gorm:"size:64"`
	// ManagementGateway 管理网网关
	ManagementGateway string `json:"management_gateway,omitempty" gorm:"size:64"`
	// Nameserver DNS
	Nameserver string `json:"nameserver,omitempty" gorm:"size:64"`
	// OSImage 实际使用的OS镜像
	OSImage string `json:"os_image,omitempty" gorm:"size:128"`
	// BMC BMC凭据(序列化存储)
	BMCHost     string `json:"bmc_host" gorm:"column:bmc_host;size:128"`
	BMCUsername string `json:"bmc_username" gorm:"column:bmc_username;size:64"`
	BMCPassword string `json:"-" gorm:"column:bmc_password;size:128"`
	BMCVendor   string `json:"bmc_vendor,omitempty" gorm:"column:bmc_vendor;size:32"`
	// RedfishSystemID Redfish Systems集合中的系统ID(采集后填充)
	RedfishSystemID string `json:"redfish_system_id,omitempty" gorm:"column:redfish_system_id;size:64"`
	// HardwareInfo 硬件信息(内嵌)
	HardwareInfo HardwareInfo `json:"hardware_info,omitempty" gorm:"embedded;embeddedPrefix:hw_"`
	// K8sNodeName 实际加入K8s后的节点名(默认与Hostname一致)
	K8sNodeName string `json:"k8s_node_name,omitempty" gorm:"size:255"`
	// LabelsJSON K8s节点标签(JSON序列化)
	LabelsJSON string `json:"-" gorm:"column:labels;type:text"`
	// LastError 最近一次错误信息
	LastError string `json:"last_error,omitempty" gorm:"column:last_error;type:text"`
	// ProvisionedAt 供应完成时间
	ProvisionedAt *time.Time `json:"provisioned_at,omitempty" gorm:"column:provisioned_at"`
	// JoinedAt 加入K8s集群时间
	JoinedAt *time.Time `json:"joined_at,omitempty" gorm:"column:joined_at"`
	// CreatedAt 创建时间
	CreatedAt time.Time `json:"created_at" gorm:"autoCreateTime"`
	// UpdatedAt 更新时间
	UpdatedAt time.Time `json:"updated_at" gorm:"autoUpdateTime"`
}

// TableName 指定GORM表名
func (BareMetalNode) TableName() string {
	return "baremetal_nodes"
}

// ToSpec 将运行时模型转换为规格模型(用于供应流程)
func (n *BareMetalNode) ToSpec() NodeSpec {
	return NodeSpec{
		Hostname:          n.Hostname,
		Role:              n.Role,
		ManagementIP:      n.ManagementIP,
		ManagementCIDR:    n.ManagementCIDR,
		ManagementGateway: n.ManagementGateway,
		Nameserver:        n.Nameserver,
		OSImage:           n.OSImage,
		BMC: BMCCredential{
			Host:     n.BMCHost,
			Username: n.BMCUsername,
			Password: n.BMCPassword,
			Vendor:   n.BMCVendor,
		},
	}
}

// ScaleRequest 扩缩容请求
type ScaleRequest struct {
	// Action 动作: add / remove
	Action string `json:"action" validate:"required,oneof=add remove"`
	// Nodes 待加入/移除的节点列表
	Nodes []NodeSpec `json:"nodes" validate:"required,min=1"`
}
