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

// CreateCollection 创建向量集合。
func (s *VectorService) CreateCollection(ctx context.Context, req store.CreateCollectionRequest) error {
	if err := validateCreateCollectionRequest(req); err != nil {
		return err
	}
	return s.store.CreateCollection(ctx, req)
}

// DropCollection 删除向量集合。
func (s *VectorService) DropCollection(ctx context.Context, collectionName string) error {
	if collectionName == "" {
		return fmt.Errorf("%w: collection name is required", ErrInvalidArgument)
	}
	return s.store.DropCollection(ctx, collectionName)
}

// Insert 插入向量。
func (s *VectorService) Insert(ctx context.Context, req store.InsertRequest) error {
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
	return s.store.Insert(ctx, req)
}

// Search 向量检索。
func (s *VectorService) Search(ctx context.Context, req store.SearchRequest) ([]store.SearchResult, error) {
	if err := validateSearchRequest(req.CollectionName, req.Vector, req.TopK); err != nil {
		return nil, err
	}
	req.TopK = normalizeTopK(req.TopK)
	return s.store.Search(ctx, req)
}

// HybridSearch 混合检索（向量 + 标量过滤 + 分数阈值）。
func (s *VectorService) HybridSearch(ctx context.Context, req store.HybridSearchRequest) ([]store.SearchResult, error) {
	if err := validateSearchRequest(req.CollectionName, req.Vector, req.TopK); err != nil {
		return nil, err
	}
	if req.Filter == "" {
		return nil, fmt.Errorf("%w: filter is required for hybrid search", ErrInvalidArgument)
	}
	req.TopK = normalizeTopK(req.TopK)
	return s.store.HybridSearch(ctx, req)
}

// Delete 删除向量。
func (s *VectorService) Delete(ctx context.Context, collectionName string, ids []string) error {
	if collectionName == "" {
		return fmt.Errorf("%w: collection_name is required", ErrInvalidArgument)
	}
	if len(ids) == 0 {
		return fmt.Errorf("%w: ids must not be empty", ErrInvalidArgument)
	}
	return s.store.Delete(ctx, collectionName, ids)
}

// ListCollections 列出全部集合（前端 /vector 列表）。
func (s *VectorService) ListCollections(ctx context.Context) ([]store.Collection, error) {
	return s.store.ListCollections(ctx)
}

// GetStats 返回集合统计信息。
func (s *VectorService) GetStats(ctx context.Context, collectionName string) (*store.CollectionStats, error) {
	if collectionName == "" {
		return nil, fmt.Errorf("%w: collection name is required", ErrInvalidArgument)
	}
	return s.store.GetStats(ctx, collectionName)
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
