package cmd

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestStatusCmd_OutputFormat 测试 status 命令输出表格格式。
func TestStatusCmd_OutputFormat(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"status"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	assert.Contains(t, output, "COMPONENT")
	assert.Contains(t, output, "STATUS")
	assert.Contains(t, output, "ENDPOINT")
}

// TestStatusCmd_ContainsComponents 测试 status 命令包含所有组件。
func TestStatusCmd_ContainsComponents(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"status"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	assert.True(t, strings.Contains(output, "encaps-layer"))
	assert.True(t, strings.Contains(output, "sql-gateway"))
	assert.True(t, strings.Contains(output, "rule-engine"))
	assert.True(t, strings.Contains(output, "catalog"))
}

// TestStatusCmd_HealthyStatus 测试 status 命令返回 healthy 状态。
func TestStatusCmd_HealthyStatus(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"status"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	assert.Contains(t, output, "healthy")
}

// TestStatusCmd_TabularFormat 测试 status 输出使用 tabwriter 格式化。
func TestStatusCmd_TabularFormat(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"status"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	lines := strings.Split(strings.TrimSpace(output), "\n")
	assert.GreaterOrEqual(t, len(lines), 2) // 至少 header + 1 行数据
}
