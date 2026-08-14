// Package store 定义向量存储的抽象接口与数据模型。
//
// 核心抽象是 VectorStore 接口，封装向量集合管理与检索能力。
// 当前实现：
//   - internal/store/mock: 基于内存 map 的 Mock 实现，用于单元测试与本地开发
//   - internal/store/milvus: 基于 Milvus Go SDK 的生产实现（需 build tag milvus_enabled）
//
// 设计遵循"接口抽象 + 依赖注入"原则，上层 service/api 不感知具体存储后端。
package store

import (
	"context"
	"errors"
)

// 哨兵错误，便于上层通过 errors.Is 判别。
var (
	// ErrCollectionNotFound 集合不存在。
	ErrCollectionNotFound = errors.New("collection not found")
	// ErrCollectionAlreadyExists 集合已存在。
	ErrCollectionAlreadyExists = errors.New("collection already exists")
	// ErrVectorNotFound 向量不存在。
	ErrVectorNotFound = errors.New("vector not found")
	// ErrInvalidDimension 向量维度与集合声明维度不匹配。
	ErrInvalidDimension = errors.New("invalid vector dimension")
	// ErrInvalidMetricType 不支持的度量类型。
	ErrInvalidMetricType = errors.New("invalid metric type")
	// ErrInvalidIndexType 不支持的索引类型。
	ErrInvalidIndexType = errors.New("invalid index type")
)

// 支持的度量类型与索引类型常量。
const (
	MetricL2     = "L2"
	MetricIP     = "IP"
	MetricCOSINE = "COSINE"

	IndexFLAT    = "FLAT"
	IndexIVFFlat = "IVF_FLAT"
	IndexHNSW    = "HNSW"
	IndexIVFPQ   = "IVF_PQ"
)

// Collection 描述一个向量集合的元信息。
type Collection struct {
	Name       string `json:"name"`
	Dimension  int    `json:"dimension"`
	MetricType string `json:"metricType"`
	IndexType  string `json:"indexType"`
	// VectorCount 集合中向量数量（由后端维护，可能为近似值）。
	VectorCount int64 `json:"vectorCount"`
}

// Vector 表示一条向量记录。
type Vector struct {
	ID       string                 `json:"id"`
	Vector   []float32              `json:"vector"`
	Metadata map[string]interface{} `json:"metadata,omitempty"`
}

// SearchResult 表示一次检索命中的结果。
type SearchResult struct {
	ID       string                 `json:"id"`
	Score    float32                `json:"score"`
	Metadata map[string]interface{} `json:"metadata,omitempty"`
}

// CreateCollectionRequest 创建集合请求。
type CreateCollectionRequest struct {
	Name       string `json:"name"`
	Dimension  int    `json:"dimension"`
	MetricType string `json:"metricType"` // L2, IP, COSINE
	IndexType  string `json:"indexType"`  // FLAT, IVF_FLAT, HNSW, IVF_PQ
}

// InsertRequest 插入向量请求。
type InsertRequest struct {
	CollectionName string   `json:"collectionName"`
	Vectors        []Vector `json:"vectors"`
}

// SearchRequest 向量检索请求。
type SearchRequest struct {
	CollectionName string    `json:"collectionName"`
	Vector         []float32 `json:"vector"`
	TopK           int       `json:"topK"`
	// Filter 可选的标量过滤表达式（语法依后端实现而定，Milvus 使用其表达式语法）。
	Filter string `json:"filter,omitempty"`
}

// HybridSearchRequest 混合检索请求（向量 + 标量过滤）。
//
// 混合检索在向量召回的基础上叠加标量条件过滤，常用于 RAG 场景的元数据筛选。
type HybridSearchRequest struct {
	CollectionName string    `json:"collectionName"`
	Vector         []float32 `json:"vector"`
	TopK           int       `json:"topK"`
	// Filter 必填的标量过滤表达式。
	Filter string `json:"filter"`
	// MinScore 最小分数阈值，低于此分数的结果被过滤。
	MinScore float32 `json:"minScore,omitempty"`
}

// CollectionStats 集合统计信息。
type CollectionStats struct {
	Name        string `json:"name"`
	Dimension   int    `json:"dimension"`
	MetricType  string `json:"metricType"`
	IndexType   string `json:"indexType"`
	VectorCount int64  `json:"vectorCount"`
}

// VectorStore 向量存储抽象接口。
//
// 该接口封装向量集合管理与检索的全部能力，是上层 service 的唯一依赖。
// 实现方需保证 goroutine 安全。
type VectorStore interface {
	// CreateCollection 创建向量集合。
	// 若同名集合已存在，返回 ErrCollectionAlreadyExists。
	CreateCollection(ctx context.Context, req CreateCollectionRequest) error

	// ListCollections 列出全部集合（前端 /vector 列表契约）。
	ListCollections(ctx context.Context) ([]Collection, error)

	// DropCollection 删除向量集合。
	// 若集合不存在，返回 ErrCollectionNotFound。
	DropCollection(ctx context.Context, collectionName string) error

	// Insert 插入向量到指定集合。
	// 若集合不存在，返回 ErrCollectionNotFound；维度不匹配返回 ErrInvalidDimension。
	Insert(ctx context.Context, req InsertRequest) error

	// Search 执行向量检索，返回 topK 个最相似的结果。
	// 相似度依集合声明的 MetricType 计算（L2 距离越小越相似，IP/COSINE 越大越相似）。
	Search(ctx context.Context, req SearchRequest) ([]SearchResult, error)

	// HybridSearch 执行混合检索（向量 + 标量过滤）。
	HybridSearch(ctx context.Context, req HybridSearchRequest) ([]SearchResult, error)

	// Delete 按 ID 删除向量。
	Delete(ctx context.Context, collectionName string, ids []string) error

	// GetStats 返回集合统计信息。
	GetStats(ctx context.Context, collectionName string) (*CollectionStats, error)
}
