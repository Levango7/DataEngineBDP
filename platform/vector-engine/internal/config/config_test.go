package config

import (
	"os"
	"testing"
)

// TestLoad_Defaults 验证未设置任何环境变量时返回默认配置。
func TestLoad_Defaults(t *testing.T) {
	// 清理可能影响测试的环境变量
	keys := []string{"STORE_TYPE", "VECTOR_ENGINE_PORT", "DEFAULT_TOP_K", "MAX_VECTOR_DIM",
		"MILVUS_HOST", "MILVUS_PORT", "MILVUS_USERNAME", "MILVUS_PASSWORD", "MILVUS_DATABASE"}
	for _, k := range keys {
		os.Unsetenv(k)
	}

	cfg := Load()

	if cfg.StoreType != "milvus" {
		t.Errorf("default StoreType = %q, want %q", cfg.StoreType, "milvus")
	}
	if cfg.HTTPPort != "8086" {
		t.Errorf("default HTTPPort = %q, want %q", cfg.HTTPPort, "8086")
	}
	if cfg.DefaultTopK != 10 {
		t.Errorf("default DefaultTopK = %d, want 10", cfg.DefaultTopK)
	}
	if cfg.MaxVectorDim != 32768 {
		t.Errorf("default MaxVectorDim = %d, want 32768", cfg.MaxVectorDim)
	}
	if cfg.Milvus.Host != "127.0.0.1" {
		t.Errorf("default Milvus.Host = %q, want %q", cfg.Milvus.Host, "127.0.0.1")
	}
	if cfg.Milvus.Port != "19530" {
		t.Errorf("default Milvus.Port = %q, want %q", cfg.Milvus.Port, "19530")
	}
	if cfg.Milvus.Database != "default" {
		t.Errorf("default Milvus.Database = %q, want %q", cfg.Milvus.Database, "default")
	}
}

// TestLoad_EnvOverride 验证环境变量可覆盖默认配置。
func TestLoad_EnvOverride(t *testing.T) {
	os.Setenv("STORE_TYPE", "milvus")
	os.Setenv("VECTOR_ENGINE_PORT", "9090")
	os.Setenv("DEFAULT_TOP_K", "50")
	os.Setenv("MAX_VECTOR_DIM", "4096")
	os.Setenv("MILVUS_HOST", "milvus.example.com")
	os.Setenv("MILVUS_PORT", "19531")
	os.Setenv("MILVUS_USERNAME", "admin")
	os.Setenv("MILVUS_PASSWORD", "secret")
	os.Setenv("MILVUS_DATABASE", "testdb")
	defer func() {
		os.Unsetenv("STORE_TYPE")
		os.Unsetenv("VECTOR_ENGINE_PORT")
		os.Unsetenv("DEFAULT_TOP_K")
		os.Unsetenv("MAX_VECTOR_DIM")
		os.Unsetenv("MILVUS_HOST")
		os.Unsetenv("MILVUS_PORT")
		os.Unsetenv("MILVUS_USERNAME")
		os.Unsetenv("MILVUS_PASSWORD")
		os.Unsetenv("MILVUS_DATABASE")
	}()

	cfg := Load()

	if cfg.StoreType != "milvus" {
		t.Errorf("StoreType = %q, want %q", cfg.StoreType, "milvus")
	}
	if cfg.HTTPPort != "9090" {
		t.Errorf("HTTPPort = %q, want %q", cfg.HTTPPort, "9090")
	}
	if cfg.DefaultTopK != 50 {
		t.Errorf("DefaultTopK = %d, want 50", cfg.DefaultTopK)
	}
	if cfg.MaxVectorDim != 4096 {
		t.Errorf("MaxVectorDim = %d, want 4096", cfg.MaxVectorDim)
	}
	if cfg.Milvus.Host != "milvus.example.com" {
		t.Errorf("Milvus.Host = %q, want %q", cfg.Milvus.Host, "milvus.example.com")
	}
	if cfg.Milvus.Port != "19531" {
		t.Errorf("Milvus.Port = %q, want %q", cfg.Milvus.Port, "19531")
	}
	if cfg.Milvus.Username != "admin" {
		t.Errorf("Milvus.Username = %q, want %q", cfg.Milvus.Username, "admin")
	}
	if cfg.Milvus.Password != "secret" {
		t.Errorf("Milvus.Password = %q, want %q", cfg.Milvus.Password, "secret")
	}
	if cfg.Milvus.Database != "testdb" {
		t.Errorf("Milvus.Database = %q, want %q", cfg.Milvus.Database, "testdb")
	}
}

// TestLoad_InvalidInt 验证无效的整型环境变量被忽略，使用默认值。
func TestLoad_InvalidInt(t *testing.T) {
	os.Setenv("DEFAULT_TOP_K", "not-a-number")
	os.Setenv("MAX_VECTOR_DIM", "-1")
	defer func() {
		os.Unsetenv("DEFAULT_TOP_K")
		os.Unsetenv("MAX_VECTOR_DIM")
	}()

	cfg := Load()

	if cfg.DefaultTopK != 10 {
		t.Errorf("DefaultTopK with invalid env = %d, want default 10", cfg.DefaultTopK)
	}
	if cfg.MaxVectorDim != 32768 {
		t.Errorf("MaxVectorDim with invalid env = %d, want default 32768", cfg.MaxVectorDim)
	}
}
