// Package mock 提供 VectorStore 接口的内存 Mock 实现。
//
// MockVectorStore 使用内存 map 存储向量集合与向量数据，适用于：
//   - 单元测试：无需外部依赖，确定性结果
//   - 本地开发：零配置启动
//   - CI 流水线：避免 Milvus 服务依赖
//
// 检索策略：简化为暴力检索（brute-force），遍历所有向量计算相似度后取 topK。
// 这与真实 ANN 索引（HNSW/IVF）的语义一致但精度更高，适合测试断言。
package mock

import (
	"context"
	"errors"
	"fmt"
	"math"
	"sort"
	"strings"
	"sync"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
)

// MockVectorStore 是 VectorStore 接口的内存 Mock 实现。
//
// 所有方法均 goroutine 安全（通过 sync.RWMutex 保护内部状态）。
type MockVectorStore struct {
	mu sync.RWMutex
	// collections 集合名 → 集合元信息。
	collections map[string]*collectionData
}

// collectionData 持有一个集合的全部数据。
type collectionData struct {
	meta    store.Collection
	vectors map[string]*store.Vector // ID → Vector
}

// NewMockVectorStore 创建一个空的 MockVectorStore。
func NewMockVectorStore() *MockVectorStore {
	return &MockVectorStore{
		collections: make(map[string]*collectionData),
	}
}

// CreateCollection 创建向量集合。
func (m *MockVectorStore) CreateCollection(_ context.Context, req store.CreateCollectionRequest) error {
	if err := validateCreateRequest(req); err != nil {
		return err
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.collections[req.Name]; exists {
		return fmt.Errorf("%w: %s", store.ErrCollectionAlreadyExists, req.Name)
	}

	m.collections[req.Name] = &collectionData{
		meta: store.Collection{
			Name:        req.Name,
			Dimension:   req.Dimension,
			MetricType:  req.MetricType,
			IndexType:   req.IndexType,
			VectorCount: 0,
		},
		vectors: make(map[string]*store.Vector),
	}
	return nil
}

// DropCollection 删除向量集合。
func (m *MockVectorStore) DropCollection(_ context.Context, collectionName string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.collections[collectionName]; !exists {
		return fmt.Errorf("%w: %s", store.ErrCollectionNotFound, collectionName)
	}
	delete(m.collections, collectionName)
	return nil
}

// ListCollections 列出全部集合。
func (m *MockVectorStore) ListCollections(_ context.Context) ([]store.Collection, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]store.Collection, 0, len(m.collections))
	for _, cd := range m.collections {
		meta := cd.meta
		meta.VectorCount = int64(len(cd.vectors))
		out = append(out, meta)
	}
	return out, nil
}

// Insert 插入向量到指定集合。
func (m *MockVectorStore) Insert(_ context.Context, req store.InsertRequest) error {
	if req.CollectionName == "" {
		return errors.New("collection_name is required")
	}
	if len(req.Vectors) == 0 {
		return errors.New("vectors must not be empty")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	col, exists := m.collections[req.CollectionName]
	if !exists {
		return fmt.Errorf("%w: %s", store.ErrCollectionNotFound, req.CollectionName)
	}

	for i := range req.Vectors {
		v := &req.Vectors[i]
		if v.ID == "" {
			return fmt.Errorf("vector at index %d: id is required", i)
		}
		if len(v.Vector) != col.meta.Dimension {
			return fmt.Errorf("%w: vector %s has dim %d, collection %s expects %d",
				store.ErrInvalidDimension, v.ID, len(v.Vector), req.CollectionName, col.meta.Dimension)
		}
	}

	for i := range req.Vectors {
		v := req.Vectors[i]
		// 深拷贝 vector 切片与 metadata，避免外部修改影响内部状态。
		vecCopy := make([]float32, len(v.Vector))
		copy(vecCopy, v.Vector)
		var metaCopy map[string]interface{}
		if v.Metadata != nil {
			metaCopy = make(map[string]interface{}, len(v.Metadata))
			for k, val := range v.Metadata {
				metaCopy[k] = val
			}
		}
		col.vectors[v.ID] = &store.Vector{
			ID:       v.ID,
			Vector:   vecCopy,
			Metadata: metaCopy,
		}
	}
	col.meta.VectorCount = int64(len(col.vectors))
	return nil
}

// Search 执行向量检索，返回 topK 个最相似结果。
//
// 检索策略：暴力遍历 + 度量计算 + topK 排序。
// 当 req.Filter 非空时，叠加标量过滤（简化为 metadata key 存在性检查）。
func (m *MockVectorStore) Search(ctx context.Context, req store.SearchRequest) ([]store.SearchResult, error) {
	return m.searchInternal(ctx, req.CollectionName, req.Vector, req.TopK, req.Filter, 0)
}

// HybridSearch 执行混合检索（向量 + 标量过滤 + 分数阈值）。
func (m *MockVectorStore) HybridSearch(ctx context.Context, req store.HybridSearchRequest) ([]store.SearchResult, error) {
	return m.searchInternal(ctx, req.CollectionName, req.Vector, req.TopK, req.Filter, req.MinScore)
}

// searchInternal 是 Search 与 HybridSearch 的共享实现。
//
// minScore > 0 时启用分数阈值过滤（适用于 IP/COSINE 度量；L2 度量下分数越小越相似，
// 此时 minScore 语义为"距离上限"，即仅保留距离 <= minScore 的结果）。
func (m *MockVectorStore) searchInternal(
	_ context.Context,
	collectionName string,
	query []float32,
	topK int,
	filter string,
	minScore float32,
) ([]store.SearchResult, error) {
	if collectionName == "" {
		return nil, errors.New("collection_name is required")
	}
	if len(query) == 0 {
		return nil, errors.New("query vector must not be empty")
	}
	if topK <= 0 {
		topK = 10 // 默认 topK
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	col, exists := m.collections[collectionName]
	if !exists {
		return nil, fmt.Errorf("%w: %s", store.ErrCollectionNotFound, collectionName)
	}
	if len(query) != col.meta.Dimension {
		return nil, fmt.Errorf("%w: query dim %d, collection expects %d",
			store.ErrInvalidDimension, len(query), col.meta.Dimension)
	}

	results := make([]store.SearchResult, 0, len(col.vectors))
	for _, v := range col.vectors {
		// 标量过滤
		if filter != "" && !matchFilter(v.Metadata, filter) {
			continue
		}
		score := computeScore(query, v.Vector, col.meta.MetricType)
		// 分数阈值过滤
		if minScore > 0 {
			if col.meta.MetricType == store.MetricL2 {
				// L2: score 是距离，保留 score <= minScore
				if score > minScore {
					continue
				}
			} else {
				// IP/COSINE: score 是相似度，保留 score >= minScore
				if score < minScore {
					continue
				}
			}
		}
		results = append(results, store.SearchResult{
			ID:       v.ID,
			Score:    score,
			Metadata: v.Metadata,
		})
	}

	// 排序：L2 升序（距离小优先），IP/COSINE 降序（相似度高优先）。
	sortResults(results, col.meta.MetricType)

	if len(results) > topK {
		results = results[:topK]
	}
	return results, nil
}

// Delete 按 ID 删除向量。
func (m *MockVectorStore) Delete(_ context.Context, collectionName string, ids []string) error {
	if collectionName == "" {
		return errors.New("collection_name is required")
	}
	if len(ids) == 0 {
		return nil
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	col, exists := m.collections[collectionName]
	if !exists {
		return fmt.Errorf("%w: %s", store.ErrCollectionNotFound, collectionName)
	}

	for _, id := range ids {
		delete(col.vectors, id)
	}
	col.meta.VectorCount = int64(len(col.vectors))
	return nil
}

// GetStats 返回集合统计信息。
func (m *MockVectorStore) GetStats(_ context.Context, collectionName string) (*store.CollectionStats, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	col, exists := m.collections[collectionName]
	if !exists {
		return nil, fmt.Errorf("%w: %s", store.ErrCollectionNotFound, collectionName)
	}
	return &store.CollectionStats{
		Name:        col.meta.Name,
		Dimension:   col.meta.Dimension,
		MetricType:  col.meta.MetricType,
		IndexType:   col.meta.IndexType,
		VectorCount: col.meta.VectorCount,
	}, nil
}

// ============ 内部辅助函数 ============

// validateCreateRequest 校验创建集合请求。
func validateCreateRequest(req store.CreateCollectionRequest) error {
	if req.Name == "" {
		return errors.New("collection name is required")
	}
	if req.Dimension <= 0 {
		return errors.New("dimension must be positive")
	}
	switch req.MetricType {
	case store.MetricL2, store.MetricIP, store.MetricCOSINE:
	default:
		return fmt.Errorf("%w: %s", store.ErrInvalidMetricType, req.MetricType)
	}
	switch req.IndexType {
	case store.IndexFLAT, store.IndexIVFFlat, store.IndexHNSW, store.IndexIVFPQ:
	default:
		return fmt.Errorf("%w: %s", store.ErrInvalidIndexType, req.IndexType)
	}
	return nil
}

// computeScore 计算查询向量与目标向量的相似度分数。
//
// 度量类型：
//   - L2:     欧氏距离平方（越小越相似）
//   - IP:     内积（越大越相似）
//   - COSINE: 余弦相似度（越大越相似，范围 [-1, 1]）
func computeScore(a, b []float32, metricType string) float32 {
	switch metricType {
	case store.MetricL2:
		return l2Distance(a, b)
	case store.MetricIP:
		return innerProduct(a, b)
	case store.MetricCOSINE:
		return cosineSimilarity(a, b)
	default:
		return l2Distance(a, b)
	}
}

// l2Distance 计算欧氏距离平方。
func l2Distance(a, b []float32) float32 {
	var sum float32
	for i := range a {
		d := a[i] - b[i]
		sum += d * d
	}
	return sum
}

// innerProduct 计算内积。
func innerProduct(a, b []float32) float32 {
	var sum float32
	for i := range a {
		sum += a[i] * b[i]
	}
	return sum
}

// cosineSimilarity 计算余弦相似度。
func cosineSimilarity(a, b []float32) float32 {
	dot := innerProduct(a, b)
	normA := float32(math.Sqrt(float64(innerProduct(a, a))))
	normB := float32(math.Sqrt(float64(innerProduct(b, b))))
	if normA == 0 || normB == 0 {
		return 0
	}
	return dot / (normA * normB)
}

// sortResults 按度量类型排序检索结果。
//
// L2 升序（距离小优先），IP/COSINE 降序（相似度高优先）。
func sortResults(results []store.SearchResult, metricType string) {
	switch metricType {
	case store.MetricL2:
		sort.Slice(results, func(i, j int) bool {
			return results[i].Score < results[j].Score
		})
	default: // IP, COSINE
		sort.Slice(results, func(i, j int) bool {
			return results[i].Score > results[j].Score
		})
	}
}

// matchFilter 执行简化的标量过滤匹配。
//
// 简化语义：filter 形如 "key=value"，匹配 metadata[key] == value；
// 若 filter 不含 "="，则匹配 metadata 中存在该 key。
// 真实 Milvus 实现支持完整的表达式语法（如 "age > 18 && color == 'red'"）。
func matchFilter(metadata map[string]interface{}, filter string) bool {
	filter = strings.TrimSpace(filter)
	if filter == "" {
		return true
	}
	if idx := strings.Index(filter, "="); idx > 0 {
		key := strings.TrimSpace(filter[:idx])
		value := strings.TrimSpace(filter[idx+1:])
		val, ok := metadata[key]
		if !ok {
			return false
		}
		return fmt.Sprintf("%v", val) == value
	}
	_, ok := metadata[filter]
	return ok
}
