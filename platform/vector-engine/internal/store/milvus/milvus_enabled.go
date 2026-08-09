//go:build milvus_enabled
// +build milvus_enabled

// Package milvus 提供 VectorStore 接口的 Milvus 生产实现。
//
// 本文件仅在 milvus_enabled build tag 启用时编译，链接真实的 Milvus Go SDK
// （github.com/milvus-io/milvus-sdk-go/v2）。
//
// 启用构建：go build -tags milvus_enabled
//
// 集合 Schema 约定：
//   - id       VarChar(65535) 主键，存储向量 ID（字符串）
//   - vector   FloatVector(dim)，存储向量数据
//   - metadata JSON，存储元数据 map[string]interface{}
//
// 索引约定：CreateCollection 时根据 IndexType 在 vector 字段上创建对应索引并加载。
// 度量类型（L2/IP/COSINE）与索引类型（FLAT/IVF_FLAT/HNSW/IVF_PQ）的常量值
// 与 store 包定义一致，可直接映射到 Milvus SDK 的 entity.MetricType / entity.IndexType。
package milvus

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/milvus-io/milvus-sdk-go/v2/client"
	"github.com/milvus-io/milvus-sdk-go/v2/entity"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
)

// 集合 Schema 字段命名约定。
const (
	fieldID        = "id"             // 主键字段名
	fieldVector    = "vector"         // 向量字段名
	fieldMetadata  = "metadata"       // 元数据字段名（JSON）
	idMaxLength    = 65535            // VarChar 主键最大长度
	shardNum       = 2                // 默认分片数
	connectTimeout = 10 * time.Second // 连接超时
)

// 默认索引参数。
const (
	defaultNlist           = 128 // IVF 系列索引的 nlist
	defaultNprobe          = 10  // IVF 系列检索的 nprobe
	defaultHNSWM           = 16  // HNSW 的 M
	defaultHNSWEfConstruct = 200 // HNSW 的 efConstruction
	defaultHNSWEf          = 64  // HNSW 检索的 ef
	defaultPQNbits         = 8   // IVF_PQ 的 nbits
)

// open 在 milvus_enabled 构建下连接真实 Milvus 实例。
//
// 连接策略：
//  1. 通过 client.NewClient 建立 gRPC 连接（支持可选认证）
//  2. 若指定了非默认数据库，幂等创建（忽略已存在错误）并切换
//
// 连接失败返回 error，构造器会将此错误传播给调用方。
func (s *MilvusVectorStore) open() error {
	addr := fmt.Sprintf("%s:%s", s.host, s.port)
	cfg := client.Config{
		Address:  addr,
		Username: s.username,
		Password: s.password,
		DBName:   s.database,
	}

	ctx, cancel := context.WithTimeout(context.Background(), connectTimeout)
	defer cancel()

	c, err := client.NewClient(ctx, cfg)
	if err != nil {
		return fmt.Errorf("connect milvus %s: %w", addr, err)
	}
	s.client = c

	// 若指定了非默认数据库，幂等创建并切换。
	// CreateDatabase 对已存在的数据库返回错误，此处忽略以支持重复启动。
	if s.database != "" && s.database != "default" {
		_ = c.CreateDatabase(ctx, s.database)
		if err := c.UsingDatabase(ctx, s.database); err != nil {
			return fmt.Errorf("using database %q: %w", s.database, err)
		}
	}
	return nil
}

// getClient 返回已建立的 Milvus client。
//
// 调用前应确保 open() 已成功执行（由 NewMilvusVectorStore 保证）。
func (s *MilvusVectorStore) getClient() client.Client {
	return s.client.(client.Client)
}

// CreateCollection 创建向量集合。
//
// 流程：
//  1. 校验请求参数
//  2. 检查同名集合是否已存在
//  3. 构造 Schema（id/vector/metadata 三字段）并创建集合
//  4. 在 vector 字段上创建索引
//  5. 加载集合到内存以支持检索
func (s *MilvusVectorStore) CreateCollection(ctx context.Context, req store.CreateCollectionRequest) error {
	if err := validateCreateRequest(req); err != nil {
		return err
	}

	c := s.getClient()

	// 检查同名集合是否已存在。
	exists, err := c.HasCollection(ctx, req.Name)
	if err != nil {
		return fmt.Errorf("check collection existence: %w", err)
	}
	if exists {
		return fmt.Errorf("%w: %s", store.ErrCollectionAlreadyExists, req.Name)
	}

	// 构造 Schema：id(VarChar PK) + vector(FloatVector) + metadata(JSON)。
	schema := entity.NewSchema().WithName(req.Name).WithAutoID(false).
		WithField(entity.NewField().
			WithName(fieldID).
			WithDataType(entity.FieldTypeVarChar).
			WithMaxLength(idMaxLength).
			WithIsPrimaryKey(true)).
		WithField(entity.NewField().
			WithName(fieldVector).
			WithDataType(entity.FieldTypeFloatVector).
			WithDim(int64(req.Dimension))).
		WithField(entity.NewField().
			WithName(fieldMetadata).
			WithDataType(entity.FieldTypeJSON))

	if err := c.CreateCollection(ctx, schema, shardNum); err != nil {
		return fmt.Errorf("create collection: %w", err)
	}

	// 创建索引。
	idx, err := buildIndex(req.IndexType, req.MetricType, req.Dimension)
	if err != nil {
		return err
	}
	if err := c.CreateIndex(ctx, req.Name, fieldVector, idx, false); err != nil {
		return fmt.Errorf("create index: %w", err)
	}

	// 加载集合到内存（同步等待加载完成）。
	if err := c.LoadCollection(ctx, req.Name, false); err != nil {
		return fmt.Errorf("load collection: %w", err)
	}
	return nil
}

// DropCollection 删除向量集合。
//
// 若集合不存在，返回 ErrCollectionNotFound。
func (s *MilvusVectorStore) DropCollection(ctx context.Context, collectionName string) error {
	if collectionName == "" {
		return errors.New("collection name is required")
	}

	c := s.getClient()

	exists, err := c.HasCollection(ctx, collectionName)
	if err != nil {
		return fmt.Errorf("check collection existence: %w", err)
	}
	if !exists {
		return fmt.Errorf("%w: %s", store.ErrCollectionNotFound, collectionName)
	}

	if err := c.DropCollection(ctx, collectionName); err != nil {
		return fmt.Errorf("drop collection: %w", err)
	}
	return nil
}

// Insert 插入向量到指定集合。
//
// 流程：
//  1. 校验请求与集合存在性
//  2. 反查集合维度并校验每条向量维度
//  3. 构造列数据（id/vector/metadata）并插入
//  4. Flush 确保数据落盘可搜
func (s *MilvusVectorStore) Insert(ctx context.Context, req store.InsertRequest) error {
	if req.CollectionName == "" {
		return errors.New("collection_name is required")
	}
	if len(req.Vectors) == 0 {
		return errors.New("vectors must not be empty")
	}
	for i, v := range req.Vectors {
		if v.ID == "" {
			return fmt.Errorf("vector at index %d: id is required", i)
		}
	}

	c := s.getClient()

	exists, err := c.HasCollection(ctx, req.CollectionName)
	if err != nil {
		return fmt.Errorf("check collection existence: %w", err)
	}
	if !exists {
		return fmt.Errorf("%w: %s", store.ErrCollectionNotFound, req.CollectionName)
	}

	dim, _, _, err := s.getCollectionMeta(ctx, req.CollectionName)
	if err != nil {
		return err
	}

	// 校验每条向量维度。
	for _, v := range req.Vectors {
		if len(v.Vector) != dim {
			return fmt.Errorf("%w: vector %s has dim %d, collection %s expects %d",
				store.ErrInvalidDimension, v.ID, len(v.Vector), req.CollectionName, dim)
		}
	}

	// 构造列数据。
	ids := make([]string, len(req.Vectors))
	vectors := make([][]float32, len(req.Vectors))
	metas := make([][]byte, len(req.Vectors))
	for i, v := range req.Vectors {
		ids[i] = v.ID
		vectors[i] = v.Vector
		metas[i] = marshalMetadata(v.Metadata)
	}

	idCol := entity.NewColumnVarChar(fieldID, ids)
	vecCol := entity.NewColumnFloatVector(fieldVector, dim, vectors)
	metaCol := entity.NewColumnJSONBytes(fieldMetadata, metas)

	if _, err := c.Insert(ctx, req.CollectionName, "", idCol, vecCol, metaCol); err != nil {
		return fmt.Errorf("insert: %w", err)
	}

	// 同步 Flush，确保数据立即可检索。
	if err := c.Flush(ctx, req.CollectionName, true); err != nil {
		return fmt.Errorf("flush: %w", err)
	}
	return nil
}

// Search 执行向量检索，返回 topK 个最相似结果。
//
// req.Filter 为可选的标量过滤表达式（Milvus 表达式语法）。
func (s *MilvusVectorStore) Search(ctx context.Context, req store.SearchRequest) ([]store.SearchResult, error) {
	return s.searchInternal(ctx, req.CollectionName, req.Vector, req.TopK, req.Filter, 0)
}

// HybridSearch 执行混合检索（向量 + 标量过滤 + 分数阈值）。
//
// req.Filter 为必填的标量过滤表达式。
// req.MinScore 为分数阈值：L2 度量下保留距离 <= MinScore 的结果；
// IP/COSINE 度量下保留相似度 >= MinScore 的结果。
func (s *MilvusVectorStore) HybridSearch(ctx context.Context, req store.HybridSearchRequest) ([]store.SearchResult, error) {
	return s.searchInternal(ctx, req.CollectionName, req.Vector, req.TopK, req.Filter, req.MinScore)
}

// searchInternal 是 Search 与 HybridSearch 的共享实现。
//
// 流程：
//  1. 校验请求与集合存在性
//  2. 反查集合元信息（维度/度量类型/索引类型）
//  3. 构造 SearchParam 并调用 Milvus Search
//  4. 转换 SDK SearchResult 到 store.SearchResult，应用 minScore 阈值过滤
func (s *MilvusVectorStore) searchInternal(
	ctx context.Context,
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
		topK = 10
	}

	c := s.getClient()

	exists, err := c.HasCollection(ctx, collectionName)
	if err != nil {
		return nil, fmt.Errorf("check collection existence: %w", err)
	}
	if !exists {
		return nil, fmt.Errorf("%w: %s", store.ErrCollectionNotFound, collectionName)
	}

	dim, metricType, indexType, err := s.getCollectionMeta(ctx, collectionName)
	if err != nil {
		return nil, err
	}
	if len(query) != dim {
		return nil, fmt.Errorf("%w: query dim %d, collection expects %d",
			store.ErrInvalidDimension, len(query), dim)
	}

	sp, err := buildSearchParam(indexType)
	if err != nil {
		return nil, err
	}

	// 执行检索：outputFields 请求 metadata，id 从主键列 IDs 获取。
	results, err := c.Search(
		ctx,
		collectionName,
		[]string{}, // 所有分区
		filter,     // 标量过滤表达式（空表示无过滤）
		[]string{fieldMetadata},
		[]entity.Vector{entity.FloatVector(query)},
		fieldVector,
		entity.MetricType(metricType),
		topK,
		sp,
	)
	if err != nil {
		return nil, fmt.Errorf("search: %w", err)
	}
	if len(results) == 0 {
		return []store.SearchResult{}, nil
	}

	sr := results[0]
	if sr.Err != nil {
		return nil, fmt.Errorf("search result error: %w", sr.Err)
	}

	return convertSearchResults(sr, metricType, minScore), nil
}

// Delete 按 ID 删除向量。
func (s *MilvusVectorStore) Delete(ctx context.Context, collectionName string, ids []string) error {
	if collectionName == "" {
		return errors.New("collection_name is required")
	}
	if len(ids) == 0 {
		return nil
	}

	c := s.getClient()

	exists, err := c.HasCollection(ctx, collectionName)
	if err != nil {
		return fmt.Errorf("check collection existence: %w", err)
	}
	if !exists {
		return fmt.Errorf("%w: %s", store.ErrCollectionNotFound, collectionName)
	}

	idCol := entity.NewColumnVarChar(fieldID, ids)
	if err := c.DeleteByPks(ctx, collectionName, "", idCol); err != nil {
		return fmt.Errorf("delete: %w", err)
	}
	return nil
}

// GetStats 返回集合统计信息。
//
// 流程：
//  1. 检查集合存在性
//  2. 反查维度/度量类型/索引类型
//  3. 调用 GetCollectionStatistics 获取向量数量
func (s *MilvusVectorStore) GetStats(ctx context.Context, collectionName string) (*store.CollectionStats, error) {
	if collectionName == "" {
		return nil, errors.New("collection name is required")
	}

	c := s.getClient()

	exists, err := c.HasCollection(ctx, collectionName)
	if err != nil {
		return nil, fmt.Errorf("check collection existence: %w", err)
	}
	if !exists {
		return nil, fmt.Errorf("%w: %s", store.ErrCollectionNotFound, collectionName)
	}

	dim, metricType, indexType, err := s.getCollectionMeta(ctx, collectionName)
	if err != nil {
		return nil, err
	}

	stats, err := c.GetCollectionStatistics(ctx, collectionName)
	if err != nil {
		return nil, fmt.Errorf("get collection statistics: %w", err)
	}

	var count int64
	if v, ok := stats["row_count"]; ok {
		count, _ = strconv.ParseInt(v, 10, 64)
	}

	return &store.CollectionStats{
		Name:        collectionName,
		Dimension:   dim,
		MetricType:  metricType,
		IndexType:   indexType,
		VectorCount: count,
	}, nil
}

// 编译期断言：MilvusVectorStore 实现 VectorStore 接口。
var _ store.VectorStore = (*MilvusVectorStore)(nil)

// ============ 内部辅助函数 ============

// getCollectionMeta 反查集合的维度、度量类型与索引类型。
//
// 维度从 Schema 的 vector 字段 TypeParams["dim"] 获取；
// 度量类型与索引类型从 vector 字段上的索引元信息获取。
func (s *MilvusVectorStore) getCollectionMeta(ctx context.Context, name string) (dim int, metricType, indexType string, err error) {
	c := s.getClient()

	coll, err := c.DescribeCollection(ctx, name)
	if err != nil {
		return 0, "", "", fmt.Errorf("describe collection: %w", err)
	}

	// 从 Schema 中提取 vector 字段的维度。
	for _, f := range coll.Schema.Fields {
		if f.Name == fieldVector {
			if f.TypeParams != nil {
				if v, ok := f.TypeParams[entity.TypeParamDim]; ok {
					dim, _ = strconv.Atoi(v)
				}
			}
			break
		}
	}
	if dim <= 0 {
		return 0, "", "", fmt.Errorf("cannot determine vector dimension for collection %q", name)
	}

	// 从索引元信息提取度量类型与索引类型。
	idxs, err := c.DescribeIndex(ctx, name, fieldVector)
	if err != nil {
		return 0, "", "", fmt.Errorf("describe index: %w", err)
	}
	if len(idxs) > 0 {
		indexType = string(idxs[0].IndexType())
		if p := idxs[0].Params(); p != nil {
			if mt, ok := p["metric_type"]; ok {
				metricType = mt
			}
		}
	}
	if metricType == "" {
		metricType = store.MetricL2 // 安全回退
	}
	if indexType == "" {
		indexType = store.IndexFLAT // 安全回退
	}
	return dim, metricType, indexType, nil
}

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

// buildIndex 根据 indexType/metricType/dim 构造 Milvus 索引。
func buildIndex(indexType, metricType string, dim int) (entity.Index, error) {
	mt := entity.MetricType(metricType)
	switch indexType {
	case store.IndexFLAT:
		return entity.NewIndexFlat(mt)
	case store.IndexIVFFlat:
		return entity.NewIndexIvfFlat(mt, defaultNlist)
	case store.IndexHNSW:
		return entity.NewIndexHNSW(mt, defaultHNSWM, defaultHNSWEfConstruct)
	case store.IndexIVFPQ:
		return entity.NewIndexIvfPQ(mt, defaultNlist, choosePQM(dim), defaultPQNbits)
	default:
		return nil, fmt.Errorf("%w: %s", store.ErrInvalidIndexType, indexType)
	}
}

// buildSearchParam 根据 indexType 构造检索参数。
func buildSearchParam(indexType string) (entity.SearchParam, error) {
	switch indexType {
	case store.IndexFLAT:
		return entity.NewIndexFlatSearchParam()
	case store.IndexIVFFlat:
		return entity.NewIndexIvfFlatSearchParam(defaultNprobe)
	case store.IndexHNSW:
		return entity.NewIndexHNSWSearchParam(defaultHNSWEf)
	case store.IndexIVFPQ:
		return entity.NewIndexIvfPQSearchParam(defaultNprobe)
	default:
		// 未知索引类型回退到 FLAT 检索参数（不依赖额外参数）。
		return entity.NewIndexFlatSearchParam()
	}
}

// choosePQM 为 IVF_PQ 索引选择合适的 m 值（m 必须整除 dim）。
//
// 优先选择较大的 m 以保留更多精度，从 32 起降序尝试。
func choosePQM(dim int) int {
	for _, m := range []int{32, 16, 8, 4, 2, 1} {
		if m <= dim && dim%m == 0 {
			return m
		}
	}
	return 1
}

// marshalMetadata 将元数据 map 序列化为 JSON bytes。
//
// nil 或空 map 序列化为 "{}" 以保证 JSON 字段非空。
func marshalMetadata(metadata map[string]interface{}) []byte {
	if len(metadata) == 0 {
		return []byte("{}")
	}
	bs, err := json.Marshal(metadata)
	if err != nil {
		return []byte("{}")
	}
	return bs
}

// convertSearchResults 将 Milvus SDK 的 SearchResult 转换为 store.SearchResult 切片，
// 并应用 minScore 阈值过滤。
func convertSearchResults(sr client.SearchResult, metricType string, minScore float32) []store.SearchResult {
	if sr.ResultCount <= 0 {
		return []store.SearchResult{}
	}

	// 获取 metadata 列（可能为 nil）。
	var metaCol entity.Column
	if sr.Fields != nil {
		metaCol = sr.Fields.GetColumn(fieldMetadata)
	}

	out := make([]store.SearchResult, 0, sr.ResultCount)
	for i := 0; i < sr.ResultCount; i++ {
		id := extractID(sr, i)
		meta := extractMetadata(metaCol, i)
		var score float32
		if i < len(sr.Scores) {
			score = sr.Scores[i]
		}

		// minScore 阈值过滤。
		if minScore > 0 {
			if metricType == store.MetricL2 {
				// L2: score 是距离，保留 score <= minScore。
				if score > minScore {
					continue
				}
			} else {
				// IP/COSINE: score 是相似度，保留 score >= minScore。
				if score < minScore {
					continue
				}
			}
		}

		out = append(out, store.SearchResult{
			ID:       id,
			Score:    score,
			Metadata: meta,
		})
	}
	return out
}

// extractID 从检索结果提取第 i 条记录的 ID。
//
// 优先从主键列 IDs 获取，回退到 outputFields 中的 id 列。
func extractID(sr client.SearchResult, i int) string {
	if sr.IDs != nil {
		if id, err := sr.IDs.GetAsString(i); err == nil {
			return id
		}
	}
	if sr.Fields != nil {
		if idCol := sr.Fields.GetColumn(fieldID); idCol != nil {
			if id, err := idCol.GetAsString(i); err == nil {
				return id
			}
		}
	}
	return ""
}

// extractMetadata 从 JSON 列提取第 i 条记录的元数据并反序列化为 map。
func extractMetadata(col entity.Column, i int) map[string]interface{} {
	if col == nil {
		return nil
	}
	val, err := col.Get(i)
	if err != nil {
		return nil
	}
	var bs []byte
	switch v := val.(type) {
	case []byte:
		bs = v
	case string:
		bs = []byte(v)
	default:
		// 尝试重新 marshal。
		if raw, err := json.Marshal(v); err == nil {
			bs = raw
		} else {
			return nil
		}
	}
	if len(bs) == 0 {
		return nil
	}
	var meta map[string]interface{}
	if err := json.Unmarshal(bs, &meta); err != nil {
		return nil
	}
	if len(meta) == 0 {
		return nil
	}
	return meta
}
