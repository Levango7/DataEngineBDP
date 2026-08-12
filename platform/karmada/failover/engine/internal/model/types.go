package model

// 故障迁移引擎数据模型。
//
// 引擎内部使用的类型，与控制台 API 的 model 包分离，
// 避免引擎依赖 Gin/GORM 等 web 框架。

import (
	"time"
)

// ClusterInfo 集群信息（来自 Karmada API）。
type ClusterInfo struct {
	Name        string             `json:"name"`
	Labels      map[string]string  `json:"labels"`
	MaxReplicas int                `json:"maxReplicas"`
	Conditions  []ClusterCondition `json:"conditions"`
	Arch        string             `json:"arch"`
	Vendor      string             `json:"vendor"`
}

// ClusterCondition 集群状态条件。
type ClusterCondition struct {
	Type   string `json:"type"`
	Status string `json:"status"`
}

// IsReady 集群是否 Ready。
func (c *ClusterInfo) IsReady() bool {
	for _, cond := range c.Conditions {
		if cond.Type == "Ready" {
			return cond.Status == "True"
		}
	}
	return false
}

// IsSyncable 集群是否可同步。
func (c *ClusterInfo) IsSyncable() bool {
	for _, cond := range c.Conditions {
		if cond.Type == "Syncable" {
			return cond.Status == "True"
		}
	}
	return false
}

// ClusterHealth 集群健康状态（综合 Karmada API + Prometheus 指标）。
type ClusterHealth struct {
	ClusterName       string    `json:"clusterName"`
	Status            string    `json:"status"` // healthy/degraded/down
	Ready             bool      `json:"ready"`
	Syncable          bool      `json:"syncable"`
	CPULoad           float64   `json:"cpuLoad"`
	MemoryLoad        float64   `json:"memoryLoad"`
	PodCount          int       `json:"podCount"`
	NodeCount         int       `json:"nodeCount"`
	AvailableReplicas int       `json:"availableReplicas"`
	MaxReplicas       int       `json:"maxReplicas"`
	CheckSource       string    `json:"checkSource"`
	Detail            string    `json:"detail"`
	CheckedAt         time.Time `json:"checkedAt"`
}

// FailoverPolicyConfig 故障迁移策略配置。
type FailoverPolicyConfig struct {
	Name                       string   `json:"name"`
	Namespace                  string   `json:"namespace"`
	PrimaryCluster             string   `json:"primaryCluster"`
	BackupClusters             []string `json:"backupClusters"`
	DetectionWindowSeconds     int      `json:"detectionWindowSeconds"`
	MigrationTimeoutSeconds    int      `json:"migrationTimeoutSeconds"`
	HealthCheckIntervalSeconds int      `json:"healthCheckIntervalSeconds"`
	Enabled                    bool     `json:"enabled"`
}

// FailoverEvent 迁移事件。
type FailoverEvent struct {
	EventID           string    `json:"eventId"`
	TenantID          string    `json:"tenantId"`
	SourceCluster     string    `json:"sourceCluster"`
	TargetCluster     string    `json:"targetCluster"`
	TriggerReason     string    `json:"triggerReason"`
	PolicyName        string    `json:"policyName"`
	Status            string    `json:"status"`
	DurationMs        int64     `json:"durationMs"`
	AffectedWorkloads []string  `json:"affectedWorkloads"`
	StartedAt         time.Time `json:"startedAt"`
	FinishedAt        time.Time `json:"finishedAt"`
}

// WeightAllocation 副本权重分配方案。
type WeightAllocation struct {
	PolicyName    string         `json:"policyName"`
	Workload      string         `json:"workload"`
	TotalReplicas int            `json:"totalReplicas"`
	Allocation    map[string]int `json:"allocation"`
	Weights       map[string]int `json:"weights"`
	Reason        string         `json:"reason"`
}

// 状态常量。
const (
	StatusHealthy  = "healthy"
	StatusDegraded = "degraded"
	StatusDown     = "down"

	EventPending   = "pending"
	EventRunning   = "running"
	EventSucceeded = "succeeded"
	EventFailed    = "failed"

	ReasonHealthCheck     = "health_check"
	ReasonPrometheusAlert = "prometheus_alert"
	ReasonManual          = "manual"
	ReasonRebalance       = "rebalance"
)
