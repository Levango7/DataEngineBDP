package model

import (
	"encoding/json"
	"testing"
)

// TestPropagationPolicy_TableName 表名应为 propagation_policies。
func TestPropagationPolicy_TableName(t *testing.T) {
	p := PropagationPolicy{}
	if got := p.TableName(); got != "propagation_policies" {
		t.Fatalf("expected table=propagation_policies, got %q", got)
	}
}

// TestPropagationPolicy_ParseSpec_ValidJSON 合法 JSON 应正确解析。
func TestPropagationPolicy_ParseSpec_ValidJSON(t *testing.T) {
	spec := PropagationPolicySpec{
		ResourceSelectors: []ResourceSelector{
			{APIVersion: "v1", Kind: "Deployment", Name: "nginx", Namespace: "default"},
		},
		Placement: Placement{
			ClusterAffinity: &ClusterAffinity{
				MatchLabels: map[string]string{"region": "us-east-1"},
			},
		},
		Priority: 100,
	}
	raw, err := json.Marshal(spec)
	if err != nil {
		t.Fatalf("marshal spec: %v", err)
	}
	p := &PropagationPolicy{Spec: string(raw)}
	got, err := p.ParseSpec()
	if err != nil {
		t.Fatalf("parse spec: %v", err)
	}
	if len(got.ResourceSelectors) != 1 {
		t.Fatalf("expected 1 selector, got %d", len(got.ResourceSelectors))
	}
	if got.ResourceSelectors[0].Name != "nginx" {
		t.Fatalf("expected selector name=nginx, got %q", got.ResourceSelectors[0].Name)
	}
	if got.Priority != 100 {
		t.Fatalf("expected priority=100, got %d", got.Priority)
	}
	if got.Placement.ClusterAffinity == nil {
		t.Fatal("expected non-nil ClusterAffinity")
	}
	if got.Placement.ClusterAffinity.MatchLabels["region"] != "us-east-1" {
		t.Fatalf("expected region=us-east-1, got %q", got.Placement.ClusterAffinity.MatchLabels["region"])
	}
}

// TestPropagationPolicy_ParseSpec_InvalidJSON 非法 JSON 应返回错误。
func TestPropagationPolicy_ParseSpec_InvalidJSON(t *testing.T) {
	p := &PropagationPolicy{Spec: "not-json"}
	if _, err := p.ParseSpec(); err == nil {
		t.Fatal("expected error for invalid JSON, got nil")
	}
}

// TestPropagationPolicySpec_JSONRoundTrip JSON 序列化/反序列化应可往返。
func TestPropagationPolicySpec_JSONRoundTrip(t *testing.T) {
	spec := PropagationPolicySpec{
		ResourceSelectors: []ResourceSelector{
			{
				APIVersion: "apps/v1",
				Kind:       "Deployment",
				MatchLabels: map[string]string{
					"app": "web",
				},
			},
		},
		Placement: Placement{
			ReplicaScheduling: &ReplicaScheduling{
				ReplicaSchedulingType:     "Divided",
				ReplicaDivisionPreference: "Weighted",
				WeightPreference: &WeightPreference{
					StaticWeightList: []StaticWeight{
						{
							TargetCluster: TargetCluster{ClusterNames: []string{"cluster-a"}},
							Weight:        80,
						},
					},
				},
			},
			SpreadConstraints: []SpreadConstraint{
				{SpreadByField: "cluster", MinGroups: 2, MaxGroups: 4},
			},
		},
		Preemption: "Always",
	}

	raw, err := json.Marshal(spec)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var got PropagationPolicySpec
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got.Placement.ReplicaScheduling.ReplicaSchedulingType != "Divided" {
		t.Fatalf("expected ReplicaSchedulingType=Divided, got %q", got.Placement.ReplicaScheduling.ReplicaSchedulingType)
	}
	if len(got.Placement.SpreadConstraints) != 1 {
		t.Fatalf("expected 1 spread constraint, got %d", len(got.Placement.SpreadConstraints))
	}
	if got.Placement.SpreadConstraints[0].MinGroups != 2 {
		t.Fatalf("expected MinGroups=2, got %d", got.Placement.SpreadConstraints[0].MinGroups)
	}
}
