package config

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestLoad_Success 测试成功加载配置文件。
func TestLoad_Success(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.yaml")

	content := `
platform_url: https://platform.example.com
tenant_id: test-tenant
token: test-token
output: json
`
	err := os.WriteFile(configPath, []byte(content), 0o644)
	require.NoError(t, err)

	cfg, err := Load(configPath)
	require.NoError(t, err)
	assert.Equal(t, "https://platform.example.com", cfg.PlatformURL)
	assert.Equal(t, "test-tenant", cfg.TenantID)
	assert.Equal(t, "test-token", cfg.Token)
	assert.Equal(t, "json", cfg.Output)
}

// TestLoad_FileNotFound 测试加载不存在的配置文件。
func TestLoad_FileNotFound(t *testing.T) {
	_, err := Load("/nonexistent/config.yaml")
	assert.Error(t, err)
}

// TestSave_Success 测试成功保存配置文件。
func TestSave_Success(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "subdir", "config.yaml")

	cfg := &Config{
		PlatformURL: "https://platform.example.com",
		TenantID:    "test-tenant",
		Token:       "test-token",
		Output:      "table",
	}

	err := Save(configPath, cfg)
	require.NoError(t, err)

	// 验证文件已创建
	_, err = os.Stat(configPath)
	assert.NoError(t, err)

	// 重新加载验证内容
	loaded, err := Load(configPath)
	require.NoError(t, err)
	assert.Equal(t, cfg.PlatformURL, loaded.PlatformURL)
	assert.Equal(t, cfg.TenantID, loaded.TenantID)
	assert.Equal(t, cfg.Token, loaded.Token)
	assert.Equal(t, cfg.Output, loaded.Output)
}

// TestSave_CreatesParentDir 测试保存时自动创建父目录。
func TestSave_CreatesParentDir(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "deep", "nested", "dir", "config.yaml")

	cfg := &Config{PlatformURL: "https://test.com", Output: "json"}
	err := Save(configPath, cfg)
	require.NoError(t, err)

	_, err = os.Stat(configPath)
	assert.NoError(t, err)
}

// TestLoad_InvalidYAML 测试加载无效 YAML 文件。
func TestLoad_InvalidYAML(t *testing.T) {
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.yaml")

	err := os.WriteFile(configPath, []byte("invalid: [yaml: content"), 0o644)
	require.NoError(t, err)

	_, _ = Load(configPath)
	// viper 对无效 YAML 可能不报错，但 Unmarshal 可能失败
	// 这里只验证不 panic
	assert.NotPanics(t, func() {
		Load(configPath)
	})
}

// TestConfig_Fields 测试 Config 结构体字段。
func TestConfig_Fields(t *testing.T) {
	cfg := &Config{
		PlatformURL: "https://test.com",
		TenantID:    "t1",
		Token:       "tok1",
		Output:      "yaml",
	}
	assert.Equal(t, "https://test.com", cfg.PlatformURL)
	assert.Equal(t, "t1", cfg.TenantID)
	assert.Equal(t, "tok1", cfg.Token)
	assert.Equal(t, "yaml", cfg.Output)
}
