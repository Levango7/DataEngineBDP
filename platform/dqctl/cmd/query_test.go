package cmd

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestQueryCmd_RequiresArgs 测试 query 命令必须提供 SQL 参数。
func TestQueryCmd_RequiresArgs(t *testing.T) {
	_, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"query"})
		return rootCmd.Execute()
	})
	assert.Error(t, err)
}

// TestQueryCmd_DefaultEngine 测试 query 默认使用 trino 引擎。
func TestQueryCmd_DefaultEngine(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"query", "SELECT 1"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)
	assert.Contains(t, output, "trino")
	assert.Contains(t, output, "SELECT 1")
}

// TestQueryCmd_CustomEngine 测试 query 指定 doris 引擎。
func TestQueryCmd_CustomEngine(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"query", "--engine", "doris", "SHOW TABLES"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)
	assert.Contains(t, output, "doris")
	assert.Contains(t, output, "SHOW TABLES")
}

// TestQueryCmd_OutputFormat 测试 query 指定输出格式。
func TestQueryCmd_OutputFormat(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"query", "--output", "json", "SELECT 1"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)
	assert.Contains(t, output, "json")
}

// TestQueryCmd_Verbose 测试 query --verbose 模式。
func TestQueryCmd_Verbose(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"--verbose", "query", "SELECT 1"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)
	assert.True(t, strings.Contains(output, "trino") || strings.Contains(output, "verbose"))
}
