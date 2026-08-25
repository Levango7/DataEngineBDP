package main

import (
	"bytes"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/config"
)

// TestMockModeEmitsDemoWarning LLM_GATEWAY_MOCK_MODE=true 时启动日志输出演示模式告警。
func TestMockModeEmitsDemoWarning(t *testing.T) {
	t.Setenv("LLM_GATEWAY_MOCK_MODE", "true")
	cfg := config.LoadFromEnv()
	require.True(t, hasMockProvider(cfg.Providers))

	var buf bytes.Buffer
	warnMockProvider(&buf, hasMockProvider(cfg.Providers), serviceName)

	assert.Contains(t, buf.String(), "演示模式")
	assert.Contains(t, buf.String(), "LLM Provider 为 Mock")
	assert.Contains(t, buf.String(), serviceName)
}

// TestRealProvidersNoDemoWarning 配置真实 Provider 时不输出演示模式告警。
func TestRealProvidersNoDemoWarning(t *testing.T) {
	t.Setenv("LLM_GATEWAY_MOCK_MODE", "false")
	t.Setenv("LLM_GATEWAY_PROVIDERS", "openai,qianwen")
	cfg := config.LoadFromEnv()
	require.False(t, hasMockProvider(cfg.Providers))

	var buf bytes.Buffer
	warnMockProvider(&buf, hasMockProvider(cfg.Providers), serviceName)

	assert.Empty(t, buf.String())
	assert.NotContains(t, buf.String(), "演示模式")
}
