package model

import (
	"testing"
)

// TestClusterInfo_IsReady_ReadyTrue Ready=True 应返回 true。
func TestClusterInfo_IsReady_ReadyTrue(t *testing.T) {
	c := &ClusterInfo{
		Name: "c1",
		Conditions: []ClusterCondition{
			{Type: "Ready", Status: "True"},
			{Type: "Syncable", Status: "True"},
		},
	}
	if !c.IsReady() {
		t.Fatal("expected IsReady=true")
	}
}

// TestClusterInfo_IsReady_ReadyFalse Ready=False 应返回 false。
func TestClusterInfo_IsReady_ReadyFalse(t *testing.T) {
	c := &ClusterInfo{
		Conditions: []ClusterCondition{
			{Type: "Ready", Status: "False"},
		},
	}
	if c.IsReady() {
		t.Fatal("expected IsReady=false")
	}
}

// TestClusterInfo_IsReady_NoCondition 无 Ready 条件应返回 false。
func TestClusterInfo_IsReady_NoCondition(t *testing.T) {
	c := &ClusterInfo{
		Conditions: []ClusterCondition{
			{Type: "Syncable", Status: "True"},
		},
	}
	if c.IsReady() {
		t.Fatal("expected IsReady=false when no Ready condition")
	}
}

// TestClusterInfo_IsSyncable_True Syncable=True 应返回 true。
func TestClusterInfo_IsSyncable_True(t *testing.T) {
	c := &ClusterInfo{
		Conditions: []ClusterCondition{
			{Type: "Syncable", Status: "True"},
		},
	}
	if !c.IsSyncable() {
		t.Fatal("expected IsSyncable=true")
	}
}

// TestClusterInfo_IsSyncable_False Syncable=False 应返回 false。
func TestClusterInfo_IsSyncable_False(t *testing.T) {
	c := &ClusterInfo{
		Conditions: []ClusterCondition{
			{Type: "Syncable", Status: "False"},
		},
	}
	if c.IsSyncable() {
		t.Fatal("expected IsSyncable=false")
	}
}

// TestStatusConstants 状态常量应正确。
func TestStatusConstants(t *testing.T) {
	if StatusHealthy != "healthy" {
		t.Fatalf("expected healthy, got %q", StatusHealthy)
	}
	if StatusDegraded != "degraded" {
		t.Fatalf("expected degraded, got %q", StatusDegraded)
	}
	if StatusDown != "down" {
		t.Fatalf("expected down, got %q", StatusDown)
	}
}

// TestEventConstants 事件状态常量应正确。
func TestEventConstants(t *testing.T) {
	if EventPending != "pending" {
		t.Fatalf("expected pending, got %q", EventPending)
	}
	if EventRunning != "running" {
		t.Fatalf("expected running, got %q", EventRunning)
	}
	if EventSucceeded != "succeeded" {
		t.Fatalf("expected succeeded, got %q", EventSucceeded)
	}
	if EventFailed != "failed" {
		t.Fatalf("expected failed, got %q", EventFailed)
	}
}

// TestReasonConstants 触发原因常量应正确。
func TestReasonConstants(t *testing.T) {
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