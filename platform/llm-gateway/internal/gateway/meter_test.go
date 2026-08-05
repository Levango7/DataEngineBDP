package gateway

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// ============ Token 计量器测试 ============

// TestTokenMeter_Record 验证单租户单模型计量。
func TestTokenMeter_Record(t *testing.T) {
	m := NewTokenMeter()
	m.Record("tenant-a", "gpt-4", 100, 50*time.Millisecond)
	m.Record("tenant-a", "gpt-4", 200, 150*time.Millisecond)

	stats := m.TenantStats("tenant-a")
	assert.Equal(t, "tenant-a", stats.TenantID)
	assert.Equal(t, int64(300), stats.TotalTokens)
	assert.Equal(t, int64(2), stats.TotalCalls)
	assert.Equal(t, 200*time.Millisecond, stats.TotalLatency)

	mm := stats.ByModel["gpt-4"]
	assert.NotNil(t, mm)
	assert.Equal(t, int64(300), mm.Tokens)
	assert.Equal(t, int64(2), mm.Calls)
}

// TestTokenMeter_MultiTenant 验证多租户隔离。
func TestTokenMeter_MultiTenant(t *testing.T) {
	m := NewTokenMeter()
	m.Record("tenant-a", "gpt-4", 100, 10*time.Millisecond)
	m.Record("tenant-b", "wenxin", 200, 20*time.Millisecond)

	a := m.TenantStats("tenant-a")
	b := m.TenantStats("tenant-b")
	assert.Equal(t, int64(100), a.TotalTokens)
	assert.Equal(t, int64(200), b.TotalTokens)
	assert.NotContains(t, a.ByModel, "wenxin")
	assert.NotContains(t, b.ByModel, "gpt-4")
}

// TestTokenMeter_MultiModel 验证单租户多模型。
func TestTokenMeter_MultiModel(t *testing.T) {
	m := NewTokenMeter()
	m.Record("t1", "gpt-4", 100, 10*time.Millisecond)
	m.Record("t1", "wenxin", 50, 5*time.Millisecond)
	m.Record("t1", "qianwen", 30, 3*time.Millisecond)

	stats := m.TenantStats("t1")
	assert.Equal(t, int64(180), stats.TotalTokens)
	assert.Len(t, stats.ByModel, 3)
}

// TestTokenMeter_TotalTokens 验证全局 Token 总量。
func TestTokenMeter_TotalTokens(t *testing.T) {
	m := NewTokenMeter()
	m.Record("t1", "m1", 100, 1*time.Millisecond)
	m.Record("t2", "m2", 200, 1*time.Millisecond)
	assert.Equal(t, int64(300), m.TotalTokens())
}

// TestTokenMeter_TotalCalls 验证全局调用次数。
func TestTokenMeter_TotalCalls(t *testing.T) {
	m := NewTokenMeter()
	m.Record("t1", "m1", 100, 1*time.Millisecond)
	m.Record("t1", "m1", 100, 1*time.Millisecond)
	m.Record("t2", "m2", 200, 1*time.Millisecond)
	assert.Equal(t, int64(3), m.TotalCalls())
}

// TestTokenMeter_AverageLatency 验证平均延迟。
func TestTokenMeter_AverageLatency(t *testing.T) {
	m := NewTokenMeter()
	m.Record("t1", "m1", 100, 100*time.Millisecond)
	m.Record("t1", "m1", 100, 200*time.Millisecond)
	// 平均 = (100+200)/2 = 150ms
	assert.Equal(t, 150*time.Millisecond, m.AverageLatency())
}

// TestTokenMeter_AverageLatency_NoCalls 验证无调用时平均延迟为 0。
func TestTokenMeter_AverageLatency_NoCalls(t *testing.T) {
	m := NewTokenMeter()
	assert.Equal(t, time.Duration(0), m.AverageLatency())
}

// TestTokenMeter_AllTenantStats 验证按租户排序的快照。
func TestTokenMeter_AllTenantStats(t *testing.T) {
	m := NewTokenMeter()
	m.Record("z-tenant", "m1", 10, 1*time.Millisecond)
	m.Record("a-tenant", "m1", 20, 1*time.Millisecond)
	m.Record("m-tenant", "m1", 30, 1*time.Millisecond)

	all := m.AllTenantStats()
	require.Len(t, all, 3)
	assert.Equal(t, "a-tenant", all[0].TenantID)
	assert.Equal(t, "m-tenant", all[1].TenantID)
	assert.Equal(t, "z-tenant", all[2].TenantID)
}

// TestTokenMeter_Reset 验证清空。
func TestTokenMeter_Reset(t *testing.T) {
	m := NewTokenMeter()
	m.Record("t1", "m1", 100, 1*time.Millisecond)
	m.Reset()
	assert.Equal(t, int64(0), m.TotalTokens())
	assert.Equal(t, int64(0), m.TotalCalls())
}

// TestTokenMeter_TenantStats_NotExist 验证未知租户返回空指标。
func TestTokenMeter_TenantStats_NotExist(t *testing.T) {
	m := NewTokenMeter()
	stats := m.TenantStats("unknown")
	assert.Equal(t, "unknown", stats.TenantID)
	assert.Equal(t, int64(0), stats.TotalTokens)
	assert.NotNil(t, stats.ByModel)
}

// TestTokenMeter_SnapshotIsolation 验证快照不影响内部状态。
func TestTokenMeter_SnapshotIsolation(t *testing.T) {
	m := NewTokenMeter()
	m.Record("t1", "m1", 100, 1*time.Millisecond)

	stats := m.TenantStats("t1")
	stats.TotalTokens = 999999
	stats.ByModel["m1"].Tokens = 999999

	// 内部状态不应被修改
	again := m.TenantStats("t1")
	assert.Equal(t, int64(100), again.TotalTokens)
	assert.Equal(t, int64(100), again.ByModel["m1"].Tokens)
}
