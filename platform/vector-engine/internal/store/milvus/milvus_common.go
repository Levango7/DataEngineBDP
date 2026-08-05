// Package milvus 提供 VectorStore 接口的 Milvus 生产实现。
//
// 本文件包含类型定义与构造器，不带 build tag，在所有构建模式下均编译。
// 方法实现分为两份：
//   - milvus.go（build tag !milvus_enabled）：骨架实现，返回 ErrNotImplemented
//   - milvus_enabled.go（build tag milvus_enabled）：真实实现，委托给 Milvus Go SDK
package milvus

import (
	"errors"
)

// ErrNotImplemented 表示当前构建未启用 Milvus 实现。
//
// 启用方法：go build -tags milvus_enabled
var ErrNotImplemented = errors.New("milvus store not built: rebuild with -tags milvus_enabled")

// MilvusVectorStore 是 VectorStore 接口的 Milvus 实现。
//
// 默认构建（无 milvus_enabled build tag）下，所有方法返回 ErrNotImplemented。
// 启用 build tag 后，构造器 NewMilvusVectorStore 会连接真实 Milvus 实例，
// 各方法委托给 Milvus Go SDK。
type MilvusVectorStore struct {
	// host Milvus 服务地址
	host string
	// port Milvus 服务端口
	port string
	// database Milvus 数据库（逻辑隔离单元）
	database string
	// client 在 milvus_enabled 构建下持有 Milvus SDK client，默认构建下为 nil。
	// 此处用 interface{} 避免默认构建引入 SDK 依赖。
	client interface{}
}

// NewMilvusVectorStore 创建 Milvus 实现实例。
//
// 默认构建下返回的实例所有方法均返回 ErrNotImplemented；
// milvus_enabled 构建下会尝试连接 Milvus 服务，连接失败返回 error。
func NewMilvusVectorStore(host, port, database string) (*MilvusVectorStore, error) {
	return &MilvusVectorStore{
		host:     host,
		port:     port,
		database: database,
	}, nil
}
