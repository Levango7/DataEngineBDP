package config

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// ============ 配置加载测试 ============

// TestLoadFromJSON 验证从 JSON 加载配置。
func TestLoadFromJSON(t *testing.T) {
	cfg, err := LoadFromJSON(`{
		"server": {"port": "9090", "version": "1.2.3"},
		"providers": [
			{"name": "mock", "type": "mock", "weight": 2}
		]
	}`)
	require.NoError(t, err)
	assert.Equal(t, "9090", cfg.Server.Port)
	assert.Equal(t, "1.2.3", cfg.Server.Version)
	require.Len(t, cfg.Providers, 1)
	assert.Equal(t, "mock", cfg.Providers[0].Name)
	assert.Equal(t, 2, cfg.Providers[0].Weight)
}

// TestLoadFromJSON_Defaults 验证默认值填充。
func TestLoadFromJSON_Defaults(t *testing.T) {
	cfg, err := LoadFromJSON(`{"providers": [{"name": "m", "type": "mock"}]}`)
	require.NoError(t, err)
	assert.Equal(t, "8084", cfg.Server.Port)
	// 默认版本与 main.go defaultVersion 保持一致（Phase 2 多模态增强）。
	assert.Equal(t, "0.2.0", cfg.Server.Version)
	assert.Equal(t, 1, cfg.Providers[0].Weight) // 默认权重 1
}

// TestBuildProviders_Mock 验证构造 Mock Provider。
func TestBuildProviders_Mock(t *testing.T) {
	providers, err := BuildProviders([]ProviderConfig{
		{Name: "mock", Type: "mock"},
	})
	require.NoError(t, err)
	require.Len(t, providers, 1)
	assert.Equal(t, "mock", providers[0].Name())
}

// TestBuildProviders_Multiple 验证构造多个 Provider。
func TestBuildProviders_Multiple(t *testing.T) {
	providers, err := BuildProviders([]ProviderConfig{
		{Name: "openai", Type: "openai", APIKey: "sk-xxx"},
		{Name: "wenxin", Type: "wenxin", APIKey: "xxx"},
		{Name: "qianwen", Type: "qianwen", APIKey: "xxx"},
		{Name: "zhipu", Type: "zhipu", APIKey: "xxx"},
		{Name: "mock", Type: "mock"},
	})
	require.NoError(t, err)
	require.Len(t, providers, 5)
	assert.Equal(t, "openai", providers[0].Name())
	assert.Equal(t, "wenxin", providers[1].Name())
	assert.Equal(t, "qianwen", providers[2].Name())
	assert.Equal(t, "zhipu", providers[3].Name())
	assert.Equal(t, "mock", providers[4].Name())
}

// TestBuildProviders_UnknownType 验证未知类型报错。
func TestBuildProviders_UnknownType(t *testing.T) {
	_, err := BuildProviders([]ProviderConfig{
		{Name: "bad", Type: "unknown"},
	})
	assert.Error(t, err)
}

// TestBuildProviders_Empty 验证空配置报错。
func TestBuildProviders_Empty(t *testing.T) {
	_, err := BuildProviders(nil)
	assert.Error(t, err)
}

// TestLoadFromEnv_Default 验证环境变量默认配置（真实 Provider 优先，不再强制 Mock）。
func TestLoadFromEnv_Default(t *testing.T) {
	cfg := LoadFromEnv()
	assert.Equal(t, "8084", cfg.Server.Port)
	assert.NotEmpty(t, cfg.Providers)
	assert.Equal(t, "openai", cfg.Providers[0].Type)
	assert.Equal(t, "qianwen", cfg.Providers[1].Type)
}

// TestMockSwitch_Off 验证开关关闭（默认）：真实 Provider 优先，不注入 Mock。
func TestMockSwitch_Off(t *testing.T) {
	t.Setenv("LLM_GATEWAY_MOCK_MODE", "false")
	t.Setenv("LLM_GATEWAY_PROVIDERS", "openai,qianwen")
	cfg := LoadFromEnv()
	assert.False(t, cfg.MockMode)
	require.Len(t, cfg.Providers, 2)
	assert.Equal(t, "openai", cfg.Providers[0].Type)
	assert.Equal(t, "qianwen", cfg.Providers[1].Type)

	// YAML/JSON 路径：不写 mockMode 时同样保持真实 Provider。
	cfg2, err := LoadFromJSON(`{"providers": [{"name": "openai", "type": "openai", "apiKey": "sk-xxx"}]}`)
	require.NoError(t, err)
	assert.False(t, cfg2.MockMode)
	require.Len(t, cfg2.Providers, 1)
	assert.Equal(t, "openai", cfg2.Providers[0].Type)
}

// TestMockSwitch_On 验证开关打开：仅注入 Mock Provider（env 与 YAML/JSON 同语义）。
func TestMockSwitch_On(t *testing.T) {
	t.Setenv("LLM_GATEWAY_MOCK_MODE", "true")
	cfg := LoadFromEnv()
	assert.True(t, cfg.MockMode)
	require.Len(t, cfg.Providers, 1)
	assert.Equal(t, "mock", cfg.Providers[0].Type)

	// YAML/JSON 路径：mockMode=true 且无 providers 时兜底注入 Mock。
	cfg2, err := LoadFromJSON(`{"mockMode": true}`)
	require.NoError(t, err)
	assert.True(t, cfg2.MockMode)
	require.Len(t, cfg2.Providers, 1)
	assert.Equal(t, "mock", cfg2.Providers[0].Type)

	// mockMode=true 且写了 providers 时开关优先（与 env 强制 Mock 同语义）。
	cfg3, err := LoadFromJSON(`{"mockMode": true, "providers": [{"name": "openai", "type": "openai", "apiKey": "sk-xxx"}]}`)
	require.NoError(t, err)
	assert.True(t, cfg3.MockMode)
	require.Len(t, cfg3.Providers, 1)
	assert.Equal(t, "mock", cfg3.Providers[0].Type)
}
