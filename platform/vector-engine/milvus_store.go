//go:build milvus_enabled
// +build milvus_enabled

// 启用 milvus_enabled build tag 时，newMilvusStore 返回真实 Milvus 实现。
//
// 构建命令：go build -tags milvus_enabled
package main

import (
	"log"

	"github.com/shuqing/bigdata/vector-engine/internal/config"
	"github.com/shuqing/bigdata/vector-engine/internal/store"
	"github.com/shuqing/bigdata/vector-engine/internal/store/milvus"
)

func newMilvusStore(cfg *config.Config) store.VectorStore {
	s, err := milvus.NewMilvusVectorStore(cfg.Milvus.Host, cfg.Milvus.Port, cfg.Milvus.Database)
	if err != nil {
		log.Printf("[vector-engine] failed to create milvus store: %v", err)
		return nil
	}
	return s
}
