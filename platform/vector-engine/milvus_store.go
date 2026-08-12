//go:build milvus_enabled
// +build milvus_enabled

// 启用 milvus_enabled build tag 时，newMilvusStore 返回真实 Milvus 实现。
//
// 构建命令：go build -tags milvus_enabled
package main

import (
	"log"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/config"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store/milvus"
)

// newMilvusStore 创建真实 Milvus 存储实例。
//
// 连接参数来自配置（MILVUS_HOST/PORT/DATABASE/USERNAME/PASSWORD）。
// 连接失败时返回 nil，由 newMilvusStoreOrFallback 回退到 Mock。
func newMilvusStore(cfg *config.Config) store.VectorStore {
	s, err := milvus.NewMilvusVectorStore(
		cfg.Milvus.Host,
		cfg.Milvus.Port,
		cfg.Milvus.Database,
		cfg.Milvus.Username,
		cfg.Milvus.Password,
	)
	if err != nil {
		log.Printf("[vector-engine] failed to create milvus store: %v", err)
		return nil
	}
	return s
}
