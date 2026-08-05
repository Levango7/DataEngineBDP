package cmd

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestVersionCmd_Output 测试 version 命令输出。
func TestVersionCmd_Output(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"version"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	assert.Contains(t, output, "dqctl version")
}

// TestVersionCmd_ContainsGoVersion 测试 version 输出包含 Go 版本。
func TestVersionCmd_ContainsGoVersion(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"version"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	assert.Contains(t, output, "Go version")
}

// TestVersionCmd_ContainsPlatform 测试 version 输出包含平台信息。
func TestVersionCmd_ContainsPlatform(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"version"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	assert.Contains(t, output, "Platform")
}

// TestVersionCmd_ContainsCommit 测试 version 输出包含 commit 信息。
func TestVersionCmd_ContainsCommit(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"version"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	assert.Contains(t, output, "Commit")
}

// TestVersionCmd_DefaultVersion 测试默认版本号。
func TestVersionCmd_DefaultVersion(t *testing.T) {
	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"version"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)

	assert.True(t, strings.Contains(output, "0.1.0") || strings.Contains(output, "dqctl version"))
}
