//go:build !milvus_enabled
// +build !milvus_enabled

// Package milvus 提供 VectorStore 接口的 Milvus 生产实现骨架。
//
// 本文件为骨架实现，仅包含接口方法签名与 ErrNotImplemented 占位，
// 不依赖 Milvus Go SDK，可在默认构建下编译通过。
//
// 真实实现位于 milvus_enabled.go，需通过 `-tags milvus_enabled` 构建启用，
// 届时会链接 Milvus Go SDK（github.com/milvus-io/milvus-sdk-go/v2）。
//
// 这种 stub + 实现分离的策略保证了：
//   - 默认构建无需安装 Milvus Go SDK 即可编译通过
//   - 单元测试使用 Mock 实现，不依赖外部服务
//   - 生产环境通过 build tag 注入真实实现
package milvus

import (
	"context"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
)

// open 在默认构建下为空操作。
//
// 默认构建不链接 Milvus Go SDK，故无需也无法建立连接。
// 真实连接逻辑位于 milvus_enabled.go（build tag milvus_enabled）。
func (s *MilvusVectorStore) open() error {
	return nil
}

// CreateCollection 创建向量集合。
func (s *MilvusVectorStore) CreateCollection(_ context.Context, _ store.CreateCollectionRequest) error {
	return ErrNotImplemented
}

// DropCollection 删除向量集合。
func (s *MilvusVectorStore) DropCollection(_ context.Context, _ string) error {
	return ErrNotImplemented
}

// Insert 插入向量。
func (s *MilvusVectorStore) Insert(_ context.Context, _ store.InsertRequest) error {
	return ErrNotImplemented
}

// Search 向量检索。
func (s *MilvusVectorStore) Search(_ context.Context, _ store.SearchRequest) ([]store.SearchResult, error) {
	return nil, ErrNotImplemented
}

// HybridSearch 混合检索。
func (s *MilvusVectorStore) HybridSearch(_ context.Context, _ store.HybridSearchRequest) ([]store.SearchResult, error) {
	return nil, ErrNotImplemented
}

// Delete 删除向量。
func (s *MilvusVectorStore) Delete(_ context.Context, _ string, _ []string) error {
	return ErrNotImplemented
}

// GetStats 集合统计。
func (s *MilvusVectorStore) GetStats(_ context.Context, _ string) (*store.CollectionStats, error) {
	return nil, ErrNotImplemented
}

// 编译期断言：MilvusVectorStore 实现 VectorStore 接口。
var _ store.VectorStore = (*MilvusVectorStore)(nil)
