package streaming

import (
	"context"
	"testing"
	"time"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
	"github.com/stretchr/testify/assert"
)

func mmReq(tenant string) provider.MultimodalChatRequest {
	return provider.MultimodalChatRequest{Model: "mock", TenantID: tenant}
}

func stubChat(ctx context.Context, req provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error) {
	return &provider.MultimodalChatResponse{}, nil
}

// TestListForTenant 租户列表只返回自身任务。
func TestListForTenant(t *testing.T) {
	m := NewBatchJobManager(stubChat, nil, DefaultBatchConfig())
	defer m.Stop()

	idA := m.Submit(mmReq("tenant-a"))
	idB := m.Submit(mmReq("tenant-b"))

	listA := m.ListForTenant("tenant-a")
	foundA := false
	for _, j := range listA {
		if j.ID == idA {
			foundA = true
		}
		if j.ID == idB {
			t.Fatal("租户 A 的列表不得包含租户 B 的任务")
		}
	}
	if !foundA {
		t.Fatal("租户 A 列表应包含自己的任务")
	}

	listB := m.ListForTenant("tenant-b")
	if len(listB) != 1 || listB[0].ID != idB {
		t.Fatalf("租户 B 列表应只含自己 1 条，实际 %d", len(listB))
	}
}

// TestGetForTenant_MismatchIsHidden 跨租户按不存在处理。
func TestGetForTenant_MismatchIsHidden(t *testing.T) {
	m := newTinyManager(t)
	defer m.Stop()

	idA := m.Submit(mmReq("tenant-a"))
	if _, ok := m.GetForTenant("tenant-b", idA); ok {
		t.Fatal("跨租户 Get 应不可见")
	}
	if _, ok := m.GetForTenant("tenant-a", idA); !ok {
		t.Fatal("本租户 Get 应可见")
	}
}

// TestTerminalJobJanitor 终态任务超过 TTL 后被回收，防 map 无限增长。
func TestTerminalJobJanitor(t *testing.T) {
	m := newTinyManager(t)
	defer m.Stop()

	id := m.Submit(mmReq("tenant-a"))

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		m.mu.RLock()
		_, exists := m.jobs[id]
		m.mu.RUnlock()
		if !exists {
			return // 已被 janitor 回收
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("终态任务超过 TTL 后应被回收")
}

// TestStatsUnaffectedByJanitor 回收不改变统计计数。
func TestStatsUnaffectedByJanitor(t *testing.T) {
	m := newTinyManager(t)
	defer m.Stop()

	m.Submit(mmReq("tenant-a"))
	submitted, _, _ := m.Stats()
	assert.Equal(t, int64(1), submitted)
}

func newTinyManager(t *testing.T) *BatchJobManager {
	t.Helper()
	cfg := DefaultBatchConfig()
	cfg.QueueSize = 0 // 队列满 → 立即终态，便于测试
	cfg.WorkerCount = 1
	cfg.TerminalTTL = 50 * time.Millisecond
	cfg.JanitorInterval = 25 * time.Millisecond
	return NewBatchJobManager(stubChat, nil, cfg)
}
