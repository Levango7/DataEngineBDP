//go:build !milvus_enabled
// +build !milvus_enabled

// Package main 提供 Milvus store 的构建开关。
//
// 默认构建（无 build tag）下，newMilvusStore 返回 nil，main.go 会回退到 Mock 实现。
// 启用 Milvus 实现需通过 `-tags milvus_enabled` 构建，此时会链接真实的 Milvus Go SDK。
//
// 这种 stub + 实现分离的策略保证了：
//   - 默认构建无需安装 Milvus Go SDK 即可编译通过
//   - 单元测试使用 Mock 实现，不依赖外部服务
//   - 生产环境通过 build tag 注入真实实现
package main

import (
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/config"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
)

// newMilvusStore 在默认构建下返回 nil，表示 Milvus 实现未启用。
// 启用 milvus_enabled build tag 后，此函数会被 milvus_store.go 中的实现替换。
func newMilvusStore(_ *config.Config) store.VectorStore {
	return nil
}
