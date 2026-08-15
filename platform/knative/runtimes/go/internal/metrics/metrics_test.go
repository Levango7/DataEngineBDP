package metrics

import (
	"testing"
	"time"
)

// sharedRecorder 包级共享 recorder，避免 Prometheus 重复注册。
var sharedRecorder = NewInvocationRecorder()

// TestRuntimeName 运行时名称常量应为 "go"。
func TestRuntimeName(t *testing.T) {
	if RuntimeName != "go" {
		t.Fatalf("expected RuntimeName=go, got %q", RuntimeName)
	}
}

// TestNewInvocationRecorder 构造函数应返回非 nil 并注册指标。
func TestNewInvocationRecorder(t *testing.T) {
	r := sharedRecorder
	if r == nil {
		t.Fatal("expected non-nil recorder")
	}
	if r.invocationCount == nil {
		t.Fatal("expected non-nil invocationCount")
	}
	if r.invocationDuration == nil {
		t.Fatal("expected non-nil invocationDuration")
	}
}

// TestInvocationRecorder_Record_Success 记录成功调用不应 panic。
func TestInvocationRecorder_Record_Success(t *testing.T) {
	r := sharedRecorder
	// 多次记录不应 panic。
	r.Record("tenant-1", "echo", "success", 10*time.Millisecond)
	r.Record("tenant-1", "echo", "success", 20*time.Millisecond)
	r.Record("tenant-2", "echo", "success", 5*time.Millisecond)
}

// TestInvocationRecorder_Record_Error 记录错误调用不应 panic。
func TestInvocationRecorder_Record_Error(t *testing.T) {
	r := sharedRecorder
	r.Record("tenant-1", "echo", "error", 100*time.Millisecond)
}

// TestInvocationRecorder_Record_ZeroDuration 零耗时不应 panic。
func TestInvocationRecorder_Record_ZeroDuration(t *testing.T) {
	r := sharedRecorder
	r.Record("tenant-1", "echo", "success", 0)
}

// TestInvocationRecorder_Warmup 预热不应 panic。
func TestInvocationRecorder_Warmup(t *testing.T) {
	r := sharedRecorder
	r.Warmup("default-tenant", "default-function")
}

// TestInvocationRecorder_Record_MultipleTenants 多租户隔离记录不应 panic。
func TestInvocationRecorder_Record_MultipleTenants(t *testing.T) {
	r := sharedRecorder
	for i := 0; i < 10; i++ {
		r.Record("tenant-"+string(rune('a'+i)), "fn", "success", time.Duration(i)*time.Millisecond)
	}
}
