package model

import (
	"encoding/json"
	"testing"
)

// TestOverridePolicy_TableName 表名应为 override_policies。
func TestOverridePolicy_TableName(t *testing.T) {
	if got := (OverridePolicy{}).TableName(); got != "override_policies" {
		t.Fatalf("expected override_policies, got %q", got)
	}
}

// TestFailoverEvent_TableName 表名应为 failover_events。
func TestFailoverEvent_TableName(t *testing.T) {
	if got := (FailoverEvent{}).TableName(); got != "failover_events" {
		t.Fatalf("expected failover_events, got %q", got)
	}
}

// TestClusterHealthRecord_TableName 表名应为 cluster_health_records。
func TestClusterHealthRecord_TableName(t *testing.T) {
	if got := (ClusterHealthRecord{}).TableName(); got != "cluster_health_records" {
		t.Fatalf("expected cluster_health_records, got %q", got)
	}
}

// TestReplicaWeightPlan_TableName 表名应为 replica_weight_plans。
func TestReplicaWeightPlan_TableName(t *testing.T) {
	if got := (ReplicaWeightPlan{}).TableName(); got != "replica_weight_plans" {
		t.Fatalf("expected replica_weight_plans, got %q", got)
	}
}

// TestFailoverPolicy_TableName 表名应为 failover_policies。
func TestFailoverPolicy_TableName(t *testing.T) {
	if got := (FailoverPolicy{}).TableName(); got != "failover_policies" {
		t.Fatalf("expected failover_policies, got %q", got)
	}
}

// TestOverridePolicy_ParseSpec_ValidJSON 合法 JSON 应正确解析。
func TestOverridePolicy_ParseSpec_ValidJSON(t *testing.T) {
	spec := OverridePolicySpec{
		ResourceSelectors: []ResourceSelector{
			{APIVersion: "apps/v1", Kind: "Deployment", Name: "nginx"},
		},
		OverrideRules: []OverrideRule{
			{
				TargetCluster: &TargetCluster{ClusterNames: []string{"cluster-bj"}},
				Overriders: Overriders{
					ImageOverrider: []ImageOverrider{
						{Component: "Registry", Operator: ImageOpReplace, Value: "registry.cn-bj.example.com"},
					},
				},
			},
		},
	}
	raw, _ := json.Marshal(spec)
	p := &OverridePolicy{Spec: string(raw)}
	got, err := p.ParseSpec()
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(got.OverrideRules) != 1 {
		t.Fatalf("expected 1 rule, got %d", len(got.OverrideRules))
	}
	if got.OverrideRules[0].Overriders.ImageOverrider[0].Operator != ImageOpReplace {
		t.Fatalf("expected ImageOpReplace, got %q", got.OverrideRules[0].Overriders.ImageOverrider[0].Operator)
	}
}

// TestOverridePolicy_ParseSpec_InvalidJSON 非法 JSON 应返回错误。
func TestOverridePolicy_ParseSpec_InvalidJSON(t *testing.T) {
	p := &OverridePolicy{Spec: "not-json"}
	if _, err := p.ParseSpec(); err == nil {
		t.Fatal("expected error for invalid JSON")
	}
}

// TestImageOperatorConstants 镜像操作常量应正确。
func TestImageOperatorConstants(t *testing.T) {
	if ImageOpReplace != "replace" {
		t.Fatalf("expected ImageOpReplace=replace, got %q", ImageOpReplace)
	}
	if ImageOpPrepend != "prepend" {
		t.Fatalf("expected ImageOpPrepend=prepend, got %q", ImageOpPrepend)
	}
	if ImageOpAppend != "append" {
		t.Fatalf("expected ImageOpAppend=append, got %q", ImageOpAppend)
	}
}

// TestFailoverEventStatusConstants 事件状态常量应正确。
func TestFailoverEventStatusConstants(t *testing.T) {
	cases := []struct{ name, want string }{
		{"pending", EventStatusPending},
		{"running", EventStatusRunning},
		{"succeeded", EventStatusSucceeded},
		{"failed", EventStatusFailed},
		{"rolled_back", EventStatusRolledBack},
	}
	for _, c := range cases {
		if c.want != c.name {
			t.Fatalf("expected %s, got %q", c.name, c.want)
		}
	}
}

// TestClusterStatusConstants 集群状态常量应正确。
func TestClusterStatusConstants(t *testing.T) {
	if ClusterStatusHealthy != "healthy" {
		t.Fatalf("expected healthy, got %q", ClusterStatusHealthy)
	}
	if ClusterStatusDegraded != "degraded" {
		t.Fatalf("expected degraded, got %q", ClusterStatusDegraded)
	}
	if ClusterStatusDown != "down" {
		t.Fatalf("expected down, got %q", ClusterStatusDown)
	}
}

// TestTriggerReasonConstants 触发原因常量应正确。
func TestTriggerReasonConstants(t *testing.T) {
	if ReasonHealthCheck != "health_check" {
		t.Fatalf("expected health_check, got %q", ReasonHealthCheck)
	}
	if ReasonPrometheusAlert != "prometheus_alert" {
		t.Fatalf("expected prometheus_alert, got %q", ReasonPrometheusAlert)
	}
	if ReasonManual != "manual" {
		t.Fatalf("expected manual, got %q", ReasonManual)
	}
	if ReasonRebalance != "rebalance" {
		t.Fatalf("expected rebalance, got %q", ReasonRebalance)
	}
}

// TestOverridePolicySpec_JSONRoundTrip JSON 序列化/反序列化应可往返。
func TestOverridePolicySpec_JSONRoundTrip(t *testing.T) {
	spec := OverridePolicySpec{
		OverrideRules: []OverrideRule{
			{
				Overriders: Overriders{
					Plaintext: []PlaintextOverrider{
						{Path: "/spec/replicas", Operator: "replace", Value: 3},
					},
					CommandOverrider: []CommandOverrider{
						{ContainerName: "app", Operator: "add", Value: []string{"--debug"}},
					},
					EnvOverrider: []EnvOverrider{
						{
							ContainerName: "app",
							Operator:      "add",
							Value: []EnvVar{
								{Name: "REGION", Value: "us-east-1"},
							},
						},
					},
				},
			},
		},
		TargetClusters: &TargetCluster{ClusterNames: []string{"c1", "c2"}},
	}
	raw, err := json.Marshal(spec)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var got OverridePolicySpec
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if len(got.OverrideRules) != 1 {
		t.Fatalf("expected 1 rule, got %d", len(got.OverrideRules))
	}
	if got.TargetClusters == nil || len(got.TargetClusters.ClusterNames) != 2 {
		t.Fatalf("expected 2 target clusters, got %+v", got.TargetClusters)
	}
}