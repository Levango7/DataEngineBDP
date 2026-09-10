// Package service 封装向量检索的业务逻辑层。
//
// VectorService 是 API 层与存储层之间的中介，负责：
//   - 参数校验与归一化（如 topK 默认值、向量非空检查）
//   - 调用 VectorStore 接口完成持久化与检索
//   - 错误包装与哨兵错误映射
//
// service 层不感知具体存储后端（Mock / Milvus），仅依赖 store.VectorStore 接口。
package service

import (
	"context"
	"errors"
	"fmt"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
)

// 默认参数。
const (
	defaultTopK = 10
	maxTopK     = 1000
)

// ErrInvalidArgument 服务层参数校验失败（客户端错误）。
var ErrInvalidArgument = errors.New("invalid argument")

// VectorService 封装向量检索业务逻辑。
type VectorService struct {
	store store.VectorStore
}

// NewVectorService 创建一个新的 VectorService。
func NewVectorService(s store.VectorStore) *VectorService {
	return &VectorService{store: s}
}

// requireTenant 从 context 提取租户身份，缺失时 fail-fast 返回 ErrMissingTenant。
// 这是租户隔离的安全闸门：所有数据操作必须先过此闸门，
// 确保即使 handler 遗漏注入也不会静默退化为无隔离的裸访问。
func requireTenant(ctx context.Context) (string, error) {
	tenantID, ok := TenantIDFromContext(ctx)
	if !ok {
		return "", ErrMissingTenant
	}
	return tenantID, nil
}

// CreateCollection 创建向量集合。
//
// 租户隔离：集合名映射为 "<tenantId>__<name>" 的内部存储名，
// 不同租户的同名集合互不冲突。
func (s *VectorService) CreateCollection(ctx context.Context, req store.CreateCollectionRequest) error {
	tenantID, err := requireTenant(ctx)
	if err != nil {
		return err
	}
	if err := validateCreateCollectionRequest(req); err != nil {
		return err
	}
	req.Name = namespacedCollectionName(tenantID, req.Name)
	return s.store.CreateCollection(ctx, req)
}

// DropCollection 删除向量集合。
//
// 租户隔离：仅能删除当前租户命名空间下的集合，
// 跨租户删除同名集合会得到 ErrCollectionNotFound（隔离保护）。
func (s *VectorService) DropCollection(ctx context.Context, collectionName string) error {
	tenantID, err := requireTenant(ctx)
	if err != nil {
		return err
	}
	if collectionName == "" {
		return fmt.Errorf("%w: collection name is required", ErrInvalidArgument)
	}
	return s.store.DropCollection(ctx, namespacedCollectionName(tenantID, collectionName))
}

// Insert 插入向量。
//
// 租户隔离：向量插入到当前租户命名空间下的集合。
func (s *VectorService) Insert(ctx context.Context, req store.InsertRequest) error {
	tenantID, err := requireTenant(ctx)
	if err != nil {
		return err
	}
	if req.CollectionName == "" {
		return fmt.Errorf("%w: collection_name is required", ErrInvalidArgument)
	}
	if len(req.Vectors) == 0 {
		return fmt.Errorf("%w: vectors must not be empty", ErrInvalidArgument)
	}
	for i, v := range req.Vectors {
		if v.ID == "" {
			return fmt.Errorf("%w: vector at index %d: id is required", ErrInvalidArgument, i)
		}
		if len(v.Vector) == 0 {
			return fmt.Errorf("%w: vector %s: vector data must not be empty", ErrInvalidArgument, v.ID)
		}
	}
	req.CollectionName = namespacedCollectionName(tenantID, req.CollectionName)
	return s.store.Insert(ctx, req)
}

// Search 向量检索。
//
// 租户隔离：仅在当前租户命名空间下的集合中检索。
func (s *VectorService) Search(ctx context.Context, req store.SearchRequest) ([]store.SearchResult, error) {
	tenantID, err := requireTenant(ctx)
	if err != nil {
		return nil, err
	}
	if err := validateSearchRequest(req.CollectionName, req.Vector, req.TopK); err != nil {
		return nil, err
	}
	req.TopK = normalizeTopK(req.TopK)
	req.CollectionName = namespacedCollectionName(tenantID, req.CollectionName)
	return s.store.Search(ctx, req)
}

// HybridSearch 混合检索（向量 + 标量过滤 + 分数阈值）。
//
// 租户隔离：仅在当前租户命名空间下的集合中检索。
func (s *VectorService) HybridSearch(ctx context.Context, req store.HybridSearchRequest) ([]store.SearchResult, error) {
	tenantID, err := requireTenant(ctx)
	if err != nil {
		return nil, err
	}
	if err := validateSearchRequest(req.CollectionName, req.Vector, req.TopK); err != nil {
		return nil, err
	}
	if req.Filter == "" {
		return nil, fmt.Errorf("%w: filter is required for hybrid search", ErrInvalidArgument)
	}
	req.TopK = normalizeTopK(req.TopK)
	req.CollectionName = namespacedCollectionName(tenantID, req.CollectionName)
	return s.store.HybridSearch(ctx, req)
}

// Delete 删除向量。
//
// 租户隔离：仅能删除当前租户命名空间下集合中的向量。
func (s *VectorService) Delete(ctx context.Context, collectionName string, ids []string) error {
	tenantID, err := requireTenant(ctx)
	if err != nil {
		return err
	}
	if collectionName == "" {
		return fmt.Errorf("%w: collection_name is required", ErrInvalidArgument)
	}
	if len(ids) == 0 {
		return fmt.Errorf("%w: ids must not be empty", ErrInvalidArgument)
	}
	return s.store.Delete(ctx, namespacedCollectionName(tenantID, collectionName), ids)
}

// ListCollections 列出当前租户的全部集合（前端 /vector 列表）。
//
// 租户隔离：仅返回当前租户前缀的集合，并剥离前缀还原用户可见的原始名。
// 不同租户的集合互不可见。
func (s *VectorService) ListCollections(ctx context.Context) ([]store.Collection, error) {
	tenantID, err := requireTenant(ctx)
	if err != nil {
		return nil, err
	}
	all, err := s.store.ListCollections(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]store.Collection, 0, len(all))
	for _, col := range all {
		if name, ok := stripTenantPrefix(tenantID, col.Name); ok {
			col.Name = name
			out = append(out, col)
		}
	}
	return out, nil
}

// GetStats 返回集合统计信息。
//
// 租户隔离：仅能获取当前租户命名空间下集合的统计；
// 跨租户获取同名集合的统计会得到 ErrCollectionNotFound。
// 返回的 stats.Name 还原为用户可见的原始名（剥离租户前缀）。
func (s *VectorService) GetStats(ctx context.Context, collectionName string) (*store.CollectionStats, error) {
	tenantID, err := requireTenant(ctx)
	if err != nil {
		return nil, err
	}
	if collectionName == "" {
		return nil, fmt.Errorf("%w: collection name is required", ErrInvalidArgument)
	}
	stats, err := s.store.GetStats(ctx, namespacedCollectionName(tenantID, collectionName))
	if err != nil {
		return nil, err
	}
	// 还原用户可见的原始名（剥离租户前缀）
	stats.Name = collectionName
	return stats, nil
}

// ============ 内部校验函数 ============

// validateCreateCollectionRequest 校验创建集合请求。
func validateCreateCollectionRequest(req store.CreateCollectionRequest) error {
	if req.Name == "" {
		return fmt.Errorf("%w: collection name is required", ErrInvalidArgument)
	}
	if req.Dimension <= 0 {
		return fmt.Errorf("%w: dimension must be positive", ErrInvalidArgument)
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

// validateSearchRequest 校验检索请求的公共字段。
func validateSearchRequest(collectionName string, query []float32, topK int) error {
	if collectionName == "" {
		return fmt.Errorf("%w: collection_name is required", ErrInvalidArgument)
	}
	if len(query) == 0 {
		return fmt.Errorf("%w: query vector must not be empty", ErrInvalidArgument)
	}
	if topK < 0 {
		return fmt.Errorf("%w: top_k must not be negative", ErrInvalidArgument)
	}
	return nil
}

// normalizeTopK 归一化 topK：<=0 时取默认值，超过上限时截断。
func normalizeTopK(topK int) int {
	if topK <= 0 {
		return defaultTopK
	}
	if topK > maxTopK {
		return maxTopK
	}
	return topK
}
