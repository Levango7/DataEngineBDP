//go:build milvus_enabled
// +build milvus_enabled

// Package milvus 提供 VectorStore 接口的 Milvus 生产实现。
//
// 本文件仅在 milvus_enabled build tag 启用时编译，链接真实的 Milvus Go SDK。
// 由于本环境未安装 Milvus Go SDK，此处提供注释化的实现骨架，
// 实际项目中取消注释并执行 go mod tidy github.com/milvus-io/milvus-sdk-go/v2 即可。
//
// 启用构建：go build -tags milvus_enabled
package milvus

import (
	"context"
	"fmt"
	"log"

	"github.com/shuqing/bigdata/vector-engine/internal/store"
)

// 注：以下为真实实现的占位说明，需引入 Milvus Go SDK 后启用。
//
// import (
//     "github.com/milvus-io/milvus-sdk-go/v2/client"
//     "github.com/milvus-io/milvus-sdk-go/v2/entity"
// )

// connect 在 milvus_enabled 构建下连接真实 Milvus 实例。
//
// 此函数在 milvus.go 的 NewMilvusVectorStore 中调用（需重构构造器以支持连接）。
// 当前为骨架占位，仅打印日志。
func (s *MilvusVectorStore) connect() error {
	addr := fmt.Sprintf("%s:%s", s.host, s.port)
	log.Printf("[milvus] would connect to %s, database=%s", addr, s.database)
	// 真实实现：
	//   c, err := client.NewClient(ctx, client.WithAddr(addr))
	//   if err != nil { return err }
	//   s.client = c
	//   return nil
	_ = addr
	return nil
}

// CreateCollection 在 milvus_enabled 构建下委托给 Milvus SDK。
func (s *MilvusVectorStore) CreateCollection(ctx context.Context, req store.CreateCollectionRequest) error {
	// 真实实现：
	//   1. 构造 schema：entity.NewSchema().WithField(entity.NewField().WithName("id").WithDataType(entity.FieldTypeVarChar)...)
	//   2. 调用 s.client.CreateCollection(ctx, req.Name, schema, ...)
	//   3. 创建索引：s.client.CreateIndex(ctx, req.Name, "vector", entity.NewHNSWIndex(...))
	//   4. 加载集合：s.client.LoadCollection(ctx, req.Name, false)
	// 当前为骨架，仍返回 ErrNotImplemented 以提示真实 SDK 未集成。
	_ = ctx
	_ = req
	return ErrNotImplemented
}

// DropCollection 在 milvus_enabled 构建下委托给 Milvus SDK。
func (s *MilvusVectorStore) DropCollection(ctx context.Context, collectionName string) error {
	_ = ctx
	_ = collectionName
	return ErrNotImplemented
}

// Insert 在 milvus_enabled 构建下委托给 Milvus SDK。
func (s *MilvusVectorStore) Insert(ctx context.Context, req store.InsertRequest) error {
	_ = ctx
	_ = req
	return ErrNotImplemented
}

// Search 在 milvus_enabled 构建下委托给 Milvus SDK。
func (s *MilvusVectorStore) Search(ctx context.Context, req store.SearchRequest) ([]store.SearchResult, error) {
	_ = ctx
	_ = req
	return nil, ErrNotImplemented
}

// HybridSearch 在 milvus_enabled 构建下委托给 Milvus SDK。
func (s *MilvusVectorStore) HybridSearch(ctx context.Context, req store.HybridSearchRequest) ([]store.SearchResult, error) {
	_ = ctx
	_ = req
	return nil, ErrNotImplemented
}

// Delete 在 milvus_enabled 构建下委托给 Milvus SDK。
func (s *MilvusVectorStore) Delete(ctx context.Context, collectionName string, ids []string) error {
	_ = ctx
	_ = collectionName
	_ = ids
	return ErrNotImplemented
}

// GetStats 在 milvus_enabled 构建下委托给 Milvus SDK。
func (s *MilvusVectorStore) GetStats(ctx context.Context, collectionName string) (*store.CollectionStats, error) {
	_ = ctx
	_ = collectionName
	return nil, ErrNotImplemented
}

// 编译期断言：MilvusVectorStore 实现 VectorStore 接口。
var _ store.VectorStore = (*MilvusVectorStore)(nil)
