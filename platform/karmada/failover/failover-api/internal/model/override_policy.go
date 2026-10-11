package model

// OverridePolicy 数据模型。
//
// 对应 Karmada CRD policy.karmada.io/v1alpha1 OverridePolicy。
// 用于按集群差异覆盖资源字段（镜像/配置/环境变量/副本数等），
// 让同一份应用清单在不同集群呈现不同形态。
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

// OverridePolicy 集群本地化覆盖策略持久化模型。
type OverridePolicy struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	Name      string    `gorm:"index:idx_op_ns_name,unique;size:253;not null" json:"name"`                        // 策略名
	Namespace string    `gorm:"index:idx_op_ns_name,unique;size:253;not null;default:'default'" json:"namespace"` // 命名空间
	TenantID  string    `gorm:"index;size:64;not null" json:"tenantId"`                                           // 租户 ID
	Spec      string    `gorm:"type:text;not null" json:"spec"`                                                   // 策略规格 JSON
	Status    string    `gorm:"type:text" json:"status"`                                                          // 策略状态 JSON
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// TableName 指定表名。
func (OverridePolicy) TableName() string {
	return "override_policies"
}

// ParseSpec 解析 Spec JSON 字段为结构化对象。
func (p *OverridePolicy) ParseSpec() (*OverridePolicySpec, error) {
	var spec OverridePolicySpec
	if err := json.Unmarshal([]byte(p.Spec), &spec); err != nil {
		return nil, err
	}
	return &spec, nil
}

// OverridePolicySpec 覆盖策略规格（对应 Karmada CRD spec）。
type OverridePolicySpec struct {
	ResourceSelectors []ResourceSelector `json:"resourceSelectors,omitempty"`
	OverrideRules     []OverrideRule     `json:"overrideRules"`
	TargetClusters    *TargetCluster     `json:"targetClusters,omitempty"`
}

// OverrideRule 单条覆盖规则。
//
// 通过 Overriders 描述对资源字段的覆盖动作，Karmada 控制器在
// 同步资源到目标集群前应用这些覆盖。
type OverrideRule struct {
	TargetCluster *TargetCluster `json:"targetCluster,omitempty"`
	Overriders    Overriders     `json:"overriders"`
}

// Overriders 覆盖器集合。
//
//   - Plaintext：明文覆盖（替换字段值）
//   - ImageOverrider：镜像覆盖（按 registry/tag 替换）
//   - CommandOverrider：命令覆盖
//   - ArgsOverrider：参数覆盖
//   - EnvOverrider：环境变量覆盖
type Overriders struct {
	Plaintext        []PlaintextOverrider `json:"plaintext,omitempty"`
	ImageOverrider   []ImageOverrider     `json:"imageOverrider,omitempty"`
	CommandOverrider []CommandOverrider   `json:"commandOverrider,omitempty"`
	ArgsOverrider    []ArgsOverrider      `json:"argsOverrider,omitempty"`
	EnvOverrider     []EnvOverrider       `json:"envOverrider,omitempty"`
}

// PlaintextOverrider 明文覆盖器。
type PlaintextOverrider struct {
	Path     string      `json:"path"`
	Operator string      `json:"operator,omitempty"` // add/replace/remove
	Value    interface{} `json:"value,omitempty"`
}

// ImageOverrider 镜像覆盖器（按集群替换镜像 registry/tag）。
type ImageOverrider struct {
	Component string        `json:"component,omitempty"` // Registry/Repository/Tag
	Operator  ImageOperator `json:"operator"`
	Value     string        `json:"value"`
}

// ImageOperator 镜像覆盖操作类型。
type ImageOperator string

const (
	// ImageOpReplace 替换镜像组件。
	ImageOpReplace ImageOperator = "replace"
	// ImageOpPrepend 前置 registry。
	ImageOpPrepend ImageOperator = "prepend"
	// ImageOpAppend 追加 tag。
	ImageOpAppend ImageOperator = "append"
)

// CommandOverrider 命令覆盖器。
type CommandOverrider struct {
	ContainerName string   `json:"containerName"`
	Operator      string   `json:"operator"` // add/remove
	Value         []string `json:"value"`
}

// ArgsOverrider 参数覆盖器。
type ArgsOverrider struct {
	ContainerName string   `json:"containerName"`
	Operator      string   `json:"operator"` // add/remove
	Value         []string `json:"value"`
}

// EnvOverrider 环境变量覆盖器。
type EnvOverrider struct {
	ContainerName string   `json:"containerName"`
	Operator      string   `json:"operator"` // add/remove/replace
	Value         []EnvVar `json:"value"`
}

// EnvVar 环境变量。
type EnvVar struct {
	Name      string        `json:"name"`
	Value     string        `json:"value,omitempty"`
	ValueFrom *EnvVarSource `json:"valueFrom,omitempty"`
}

// EnvVarSource 环境变量来源。
type EnvVarSource struct {
	ConfigMapKeyRef *ConfigMapKeySelector `json:"configMapKeyRef,omitempty"`
	SecretKeyRef    *SecretKeySelector    `json:"secretKeyRef,omitempty"`
}

// ConfigMapKeySelector ConfigMap 键选择器。
type ConfigMapKeySelector struct {
	Name string `json:"name"`
	Key  string `json:"key"`
}

// SecretKeySelector Secret 键选择器。
type SecretKeySelector struct {
	Name string `json:"name"`
	Key  string `json:"key"`
}

// TargetCluster 目标集群。
type TargetCluster struct {
	ClusterNames []string `json:"clusterNames"`
}

// ResourceSelector 资源选择器（与 PropagationPolicy 共享语义）。
type ResourceSelector struct {
	APIVersion  string            `json:"apiVersion"`
	Kind        string            `json:"kind"`
	Name        string            `json:"name,omitempty"`
	Namespace   string            `json:"namespace,omitempty"`
	MatchLabels map[string]string `json:"matchLabels,omitempty"`
}
