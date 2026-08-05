package gateway

import (
	"sort"
	"sync"
	"time"
)

// ============ Token 计量 ============
//
// 按租户 / 模型统计 Token 用量与调用延迟。
// 线程安全，供网关在每次调用后 Record。

// TokenMeter Token 计量器。
type TokenMeter struct {
	mu       sync.RWMutex
	byTenant map[string]*TenantMetrics // tenantId -> metrics
}

// TenantMetrics 单个租户的聚合指标。
type TenantMetrics struct {
	TenantID     string
	TotalTokens  int64
	TotalCalls   int64
	TotalLatency time.Duration
	ByModel      map[string]*ModelMetrics // model -> metrics
}

// ModelMetrics 单个模型的聚合指标。
type ModelMetrics struct {
	Model      string
	Tokens     int64
	Calls      int64
	Latency    time.Duration
	LastUsedAt time.Time
}

// NewTokenMeter 构造计量器。
func NewTokenMeter() *TokenMeter {
	return &TokenMeter{
		byTenant: make(map[string]*TenantMetrics),
	}
}

// Record 记录一次调用。
func (m *TokenMeter) Record(tenantID, model string, tokens int64, latency time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()

	tm, ok := m.byTenant[tenantID]
	if !ok {
		tm = &TenantMetrics{
			TenantID: tenantID,
			ByModel:  make(map[string]*ModelMetrics),
		}
		m.byTenant[tenantID] = tm
	}
	tm.TotalTokens += tokens
	tm.TotalCalls++
	tm.TotalLatency += latency

	mm, ok := tm.ByModel[model]
	if !ok {
		mm = &ModelMetrics{Model: model}
		tm.ByModel[model] = mm
	}
	mm.Tokens += tokens
	mm.Calls++
	mm.Latency += latency
	mm.LastUsedAt = time.Now()
}

// TenantStats 返回指定租户的指标快照。
func (m *TokenMeter) TenantStats(tenantID string) *TenantMetrics {
	m.mu.RLock()
	defer m.mu.RUnlock()
	tm, ok := m.byTenant[tenantID]
	if !ok {
		return &TenantMetrics{TenantID: tenantID, ByModel: map[string]*ModelMetrics{}}
	}
	return cloneTenantMetrics(tm)
}

// AllTenantStats 返回所有租户的指标快照（按 TenantID 排序）。
func (m *TokenMeter) AllTenantStats() []*TenantMetrics {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]*TenantMetrics, 0, len(m.byTenant))
	for _, tm := range m.byTenant {
		out = append(out, cloneTenantMetrics(tm))
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i].TenantID < out[j].TenantID
	})
	return out
}

// TotalTokens 返回全局 Token 总量。
func (m *TokenMeter) TotalTokens() int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var total int64
	for _, tm := range m.byTenant {
		total += tm.TotalTokens
	}
	return total
}

// TotalCalls 返回全局调用总次数。
func (m *TokenMeter) TotalCalls() int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var total int64
	for _, tm := range m.byTenant {
		total += tm.TotalCalls
	}
	return total
}

// AverageLatency 返回全局平均延迟。
func (m *TokenMeter) AverageLatency() time.Duration {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var totalLatency time.Duration
	var totalCalls int64
	for _, tm := range m.byTenant {
		totalLatency += tm.TotalLatency
		totalCalls += tm.TotalCalls
	}
	if totalCalls == 0 {
		return 0
	}
	return totalLatency / time.Duration(totalCalls)
}

// Reset 清空所有指标。
func (m *TokenMeter) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.byTenant = make(map[string]*TenantMetrics)
}

// cloneTenantMetrics 深拷贝租户指标，避免外部修改影响内部状态。
func cloneTenantMetrics(tm *TenantMetrics) *TenantMetrics {
	clone := &TenantMetrics{
		TenantID:     tm.TenantID,
		TotalTokens:  tm.TotalTokens,
		TotalCalls:   tm.TotalCalls,
		TotalLatency: tm.TotalLatency,
		ByModel:      make(map[string]*ModelMetrics, len(tm.ByModel)),
	}
	for k, v := range tm.ByModel {
		clone.ByModel[k] = &ModelMetrics{
			Model:      v.Model,
			Tokens:     v.Tokens,
			Calls:      v.Calls,
			Latency:    v.Latency,
			LastUsedAt: v.LastUsedAt,
		}
	}
	return clone
}
