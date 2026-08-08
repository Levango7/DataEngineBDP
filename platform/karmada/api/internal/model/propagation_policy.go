package model

// PropagationPolicy 数据模型。
//
// 对应 Karmada CRD policy.karmada.io/v1alpha1 PropagationPolicy。
// 本模型持久化策略元数据（控制台 API 管理），后端通过 Karmada 控制面
// client 将策略同步到集群。
//
// 字段说明：
//   - Name：策略名（K8s 资源名，namespace 内唯一）
//   - Namespace：策略所在 namespace
//   - TenantID：租户 ID（多租户隔离）
//   - Spec：策略规格（JSON 字符串，对应 CRD spec）
//   - Status：策略状态（JSON 字符串，由控制器回写）

import (
	"encoding/json"
	"time"
)

// PropagationPolicy 传播策略持久化模型。
type PropagationPolicy struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	Name      string    `gorm:"index:idx_ns_name,unique;size:253;not null" json:"name"`                        // 策略名
	Namespace string    `gorm:"index:idx_ns_name,unique;size:253;not null;default:'default'" json:"namespace"` // 命名空间
	TenantID  string    `gorm:"index;size:64;not null" json:"tenantId"`                                        // 租户 ID
	Spec      string    `gorm:"type:text;not null" json:"spec"`                                                // 策略规格 JSON
	Status    string    `gorm:"type:text" json:"status"`                                                       // 策略状态 JSON
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// TableName 指定表名。
func (PropagationPolicy) TableName() string {
	return "propagation_policies"
}

// ParseSpec 解析 Spec JSON 字段为结构化对象。
func (p *PropagationPolicy) ParseSpec() (*PropagationPolicySpec, error) {
	var spec PropagationPolicySpec
	if err := json.Unmarshal([]byte(p.Spec), &spec); err != nil {
		return nil, err
	}
	return &spec, nil
}

// PropagationPolicySpec 传播策略规格（对应 Karmada CRD spec）。
type PropagationPolicySpec struct {
	ResourceSelectors []ResourceSelector `json:"resourceSelectors"`
	Placement         Placement          `json:"placement"`
	Priority          int                `json:"priority,omitempty"`
	Preemption        string             `json:"preemption,omitempty"`
}

// ResourceSelector 资源选择器。
type ResourceSelector struct {
	APIVersion  string            `json:"apiVersion"`
	Kind        string            `json:"kind"`
	Name        string            `json:"name,omitempty"`
	Namespace   string            `json:"namespace,omitempty"`
	MatchLabels map[string]string `json:"matchLabels,omitempty"`
}

// Placement 调度策略。
type Placement struct {
	ClusterAffinity   *ClusterAffinity   `json:"clusterAffinity,omitempty"`
	ReplicaScheduling *ReplicaScheduling `json:"replicaScheduling,omitempty"`
	SpreadConstraints []SpreadConstraint `json:"spreadConstraints,omitempty"`
}

// ClusterAffinity 集群亲和性。
type ClusterAffinity struct {
	MatchLabels      map[string]string `json:"matchLabels,omitempty"`
	MatchExpressions []LabelExpression `json:"matchExpressions,omitempty"`
}

// LabelExpression 标签选择表达式。
type LabelExpression struct {
	Key      string   `json:"key"`
	Operator string   `json:"operator"`
	Values   []string `json:"values,omitempty"`
}

// ReplicaScheduling 副本调度策略。
type ReplicaScheduling struct {
	ReplicaSchedulingType     string            `json:"replicaSchedulingType"`     // Duplicated/Divided
	ReplicaDivisionPreference string            `json:"replicaDivisionPreference"` // Aggregated/Weighted
	WeightPreference          *WeightPreference `json:"weightPreference,omitempty"`
}

// WeightPreference 加权偏好。
type WeightPreference struct {
	StaticWeightList []StaticWeight `json:"staticWeightList,omitempty"`
}

// StaticWeight 静态权重项。
type StaticWeight struct {
	TargetCluster TargetCluster `json:"targetCluster"`
	Weight        int           `json:"weight"`
}

// TargetCluster 目标集群。
type TargetCluster struct {
	ClusterNames []string `json:"clusterNames"`
}

// SpreadConstraint 分散约束。
type SpreadConstraint struct {
	SpreadByField string `json:"spreadByField,omitempty"`
	SpreadByLabel string `json:"spreadByLabel,omitempty"`
	MinGroups     int    `json:"minGroups,omitempty"`
	MaxGroups     int    `json:"maxGroups,omitempty"`
}
