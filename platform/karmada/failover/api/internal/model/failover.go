package model

// 故障迁移相关数据模型。
//
// 模型用于故障迁移策略引擎持久化迁移历史、健康检查记录、
// 副本权重分配方案等，供运营后台可视化查询。

import (
	"time"
)

// FailoverEvent 故障迁移事件记录。
//
// 一次迁移事件包含：源集群（故障集群）、目标集群、触发原因、
// 迁移开始/结束时间、迁移状态、受影响工作负载等。
type FailoverEvent struct {
	ID                uint      `gorm:"primaryKey" json:"id"`
	EventID           string    `gorm:"index;size:64;unique;not null" json:"eventId"`     // 事件唯一 ID
	TenantID          string    `gorm:"index;size:64;not null" json:"tenantId"`           // 租户 ID
	SourceCluster     string    `gorm:"size:253;not null" json:"sourceCluster"`           // 故障源集群
	TargetCluster     string    `gorm:"size:253;not null" json:"targetCluster"`           // 迁移目标集群
	TriggerReason     string    `gorm:"size:128;not null" json:"triggerReason"`           // 触发原因（health_check/prometheus_alert/manual）
	PolicyName        string    `gorm:"size:253" json:"policyName"`                       // 关联策略名
	Status            string    `gorm:"size:32;not null;default:'pending'" json:"status"` // pending/running/succeeded/failed/rolled_back
	DurationMs        int64     `json:"durationMs"`                                       // 迁移耗时（毫秒）
	AffectedWorkloads string    `gorm:"type:text" json:"affectedWorkloads"`               // 受影响工作负载 JSON
	StartedAt         time.Time `json:"startedAt"`
	FinishedAt        time.Time `json:"finishedAt"`
	CreatedAt         time.Time `json:"createdAt"`
}

// TableName 指定表名。
func (FailoverEvent) TableName() string {
	return "failover_events"
}

// ClusterHealthRecord 集群健康检查记录。
//
// 引擎周期性轮询各集群健康状态，写入此表供可视化查询。
type ClusterHealthRecord struct {
	ID                uint      `gorm:"primaryKey" json:"id"`
	ClusterName       string    `gorm:"index;size:253;not null" json:"clusterName"` // 集群名
	TenantID          string    `gorm:"index;size:64;not null" json:"tenantId"`     // 租户 ID
	Status            string    `gorm:"size:32;not null" json:"status"`             // healthy/degraded/down
	Ready             bool      `json:"ready"`
	Syncable          bool      `json:"syncable"`
	CPULoad           float64   `json:"cpuLoad"`    // CPU 负载 0-100
	MemoryLoad        float64   `json:"memoryLoad"` // 内存负载 0-100
	PodCount          int       `json:"podCount"`
	NodeCount         int       `json:"nodeCount"`
	AvailableReplicas int       `json:"availableReplicas"`
	MaxReplicas       int       `json:"maxReplicas"`
	CheckSource       string    `gorm:"size:32;not null" json:"checkSource"` // karmada_api/prometheus
	Detail            string    `gorm:"type:text" json:"detail"`             // 详细信息 JSON
	CheckedAt         time.Time `gorm:"index" json:"checkedAt"`
	CreatedAt         time.Time `json:"createdAt"`
}

// TableName 指定表名。
func (ClusterHealthRecord) TableName() string {
	return "cluster_health_records"
}

// ReplicaWeightPlan 副本权重分配方案。
//
// 记算后的副本分配方案，按集群权重 + 容量上限分配。
type ReplicaWeightPlan struct {
	ID            uint      `gorm:"primaryKey" json:"id"`
	TenantID      string    `gorm:"index;size:64;not null" json:"tenantId"`
	PolicyName    string    `gorm:"index;size:253;not null" json:"policyName"`
	Workload      string    `gorm:"size:253;not null" json:"workload"` // 工作负载标识
	TotalReplicas int       `json:"totalReplicas"`
	Allocation    string    `gorm:"type:text;not null" json:"allocation"` // 各集群分配 JSON
	Weights       string    `gorm:"type:text;not null" json:"weights"`    // 各集群权重 JSON
	Reason        string    `gorm:"size:128" json:"reason"`               // 触发原因（initial/failover/rebalance/manual）
	CreatedAt     time.Time `json:"createdAt"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

// TableName 指定表名。
func (ReplicaWeightPlan) TableName() string {
	return "replica_weight_plans"
}

// FailoverPolicy 故障迁移策略配置。
//
// 描述主集群故障时的迁移行为：检测窗口、迁移目标、迁移超时等。
type FailoverPolicy struct {
	ID                         uint      `gorm:"primaryKey" json:"id"`
	TenantID                   string    `gorm:"index;size:64;not null" json:"tenantId"`
	Name                       string    `gorm:"index:idx_fp_ns_name,unique;size:253;not null" json:"name"`
	Namespace                  string    `gorm:"index:idx_fp_ns_name,unique;size:253;not null;default:'default'" json:"namespace"`
	PrimaryCluster             string    `gorm:"size:253;not null" json:"primaryCluster"`  // 主集群
	BackupClusters             string    `gorm:"type:text;not null" json:"backupClusters"` // 备用集群列表 JSON
	DetectionWindowSeconds     int       `json:"detectionWindowSeconds"`                   // 故障检测窗口（秒）
	MigrationTimeoutSeconds    int       `json:"migrationTimeoutSeconds"`                  // 迁移超时（秒）
	HealthCheckIntervalSeconds int       `json:"healthCheckIntervalSeconds"`               // 健康检查间隔（秒）
	Enabled                    bool      `json:"enabled"`
	CreatedAt                  time.Time `json:"createdAt"`
	UpdatedAt                  time.Time `json:"updatedAt"`
}

// TableName 指定表名。
func (FailoverPolicy) TableName() string {
	return "failover_policies"
}

// FailoverEventStatus 事件状态常量。
const (
	EventStatusPending    = "pending"
	EventStatusRunning    = "running"
	EventStatusSucceeded  = "succeeded"
	EventStatusFailed     = "failed"
	EventStatusRolledBack = "rolled_back"
)

// ClusterStatus 集群状态常量。
const (
	ClusterStatusHealthy  = "healthy"
	ClusterStatusDegraded = "degraded"
	ClusterStatusDown     = "down"
)

// TriggerReason 触发原因常量。
const (
	ReasonHealthCheck     = "health_check"
	ReasonPrometheusAlert = "prometheus_alert"
	ReasonManual          = "manual"
	ReasonRebalance       = "rebalance"
)
