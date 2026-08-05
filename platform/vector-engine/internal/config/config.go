// Package config 提供向量检索引擎的配置加载能力。
//
// 配置来源优先级：环境变量 > 默认值。
// 支持的配置项：
//
//	STORE_TYPE        - 存储后端类型，"mock" 或 "milvus"，默认 "mock"
//	MILVUS_HOST       - Milvus 主机地址，默认 "127.0.0.1"
//	MILVUS_PORT       - Milvus 端口，默认 "19530"
//	MILVUS_USERNAME   - Milvus 认证用户名（可选）
//	MILVUS_PASSWORD   - Milvus 认证密码（可选）
//	MILVUS_DATABASE   - Milvus 数据库（可选，默认 "default"）
//	VECTOR_ENGINE_PORT - HTTP 服务端口，默认 "8084"
//	DEFAULT_TOP_K     - 默认检索 topK，默认 10
//	MAX_VECTOR_DIM    - 最大向量维度，默认 32768
package config

import (
	"os"
	"strconv"
)

// Config 持有向量检索引擎的运行时配置。
type Config struct {
	// StoreType 存储后端类型："mock" 或 "milvus"。
	StoreType string

	// HTTPPort HTTP 服务监听端口。
	HTTPPort string

	// DefaultTopK 默认检索返回的 topK 数量。
	DefaultTopK int

	// MaxVectorDim 允许的最大向量维度。
	MaxVectorDim int

	// Milvus Milvus 连接配置，仅当 StoreType == "milvus" 时使用。
	Milvus MilvusConfig
}

// MilvusConfig 持有 Milvus 连接参数。
type MilvusConfig struct {
	Host     string
	Port     string
	Username string
	Password string
	Database string
}

// Load 从环境变量加载配置，未设置的项使用默认值。
func Load() *Config {
	cfg := &Config{
		StoreType:    "mock",
		HTTPPort:     "8084",
		DefaultTopK:  10,
		MaxVectorDim: 32768,
		Milvus: MilvusConfig{
			Host:     "127.0.0.1",
			Port:     "19530",
			Database: "default",
		},
	}

	if v := os.Getenv("STORE_TYPE"); v != "" {
		cfg.StoreType = v
	}
	if v := os.Getenv("VECTOR_ENGINE_PORT"); v != "" {
		cfg.HTTPPort = v
	}
	if v := os.Getenv("DEFAULT_TOP_K"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			cfg.DefaultTopK = n
		}
	}
	if v := os.Getenv("MAX_VECTOR_DIM"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			cfg.MaxVectorDim = n
		}
	}

	// Milvus 配置
	if v := os.Getenv("MILVUS_HOST"); v != "" {
		cfg.Milvus.Host = v
	}
	if v := os.Getenv("MILVUS_PORT"); v != "" {
		cfg.Milvus.Port = v
	}
	if v := os.Getenv("MILVUS_USERNAME"); v != "" {
		cfg.Milvus.Username = v
	}
	if v := os.Getenv("MILVUS_PASSWORD"); v != "" {
		cfg.Milvus.Password = v
	}
	if v := os.Getenv("MILVUS_DATABASE"); v != "" {
		cfg.Milvus.Database = v
	}

	return cfg
}
