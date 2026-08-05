package cmd

import (
	"bytes"
	"io"
	"os"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// captureOutput 捕获 os.Stdout 输出并执行 f，返回捕获的内容。
func captureOutput(t *testing.T, f func() error) (string, error) {
	t.Helper()

	tmpDir := t.TempDir()
	origHome := os.Getenv("HOME")
	origUserProfile := os.Getenv("USERPROFILE")
	os.Setenv("HOME", tmpDir)
	os.Setenv("USERPROFILE", tmpDir)
	defer func() {
		os.Setenv("HOME", origHome)
		os.Setenv("USERPROFILE", origUserProfile)
	}()

	// 重置全局 flag 状态
	flagConfig = ""
	flagTenant = ""
	flagVerbose = false
	flagOutput = "table"
	applyDryRun = false
	applyFile = ""
	queryEngine = "trino"
	queryOutput = "table"

	oldStdout := os.Stdout
	r, w, _ := os.Pipe()
	os.Stdout = w

	buf := new(bytes.Buffer)
	done := make(chan struct{})
	go func() {
		io.Copy(buf, r)
		close(done)
	}()

	err := f()

	// 关闭写入端并等待读取完成
	w.Close()
	<-done
	os.Stdout = oldStdout

	return buf.String(), err
}

// TestApplyCmd_RequiresFile 测试 apply 命令必须指定 -f 参数。
func TestApplyCmd_RequiresFile(t *testing.T) {
	_, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"apply"})
		return rootCmd.Execute()
	})
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "required flag")
}

// TestApplyCmd_DryRun 测试 apply --dry-run 模式。
func TestApplyCmd_DryRun(t *testing.T) {
	tmpFile, err := os.CreateTemp("", "dqctl-apply-*.yaml")
	require.NoError(t, err)
	defer os.Remove(tmpFile.Name())
	_, _ = tmpFile.WriteString("test: value")

	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"apply", "-f", tmpFile.Name(), "--dry-run"})
		return rootCmd.Execute()
	})
	require.NoError(t, err)
	assert.Contains(t, output, "dry-run")
}

// TestApplyCmd_FileNotFound 测试 apply 指定不存在的文件。
func TestApplyCmd_FileNotFound(t *testing.T) {
	_, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"apply", "-f", "/nonexistent/file.yaml"})
		return rootCmd.Execute()
	})
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "读取配置文件")
}

// TestApplyCmd_Normal 测试 apply 正常模式。
func TestApplyCmd_Normal(t *testing.T) {
	tmpFile, err := os.CreateTemp("", "dqctl-apply-*.yaml")
	require.NoError(t, err)
	defer os.Remove(tmpFile.Name())
	_, _ = tmpFile.WriteString("test: value")

	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"apply", "-f", tmpFile.Name()})
		return rootCmd.Execute()
	})
	require.NoError(t, err)
	assert.Contains(t, output, "apply 完成")
}

// TestApplyCmd_Verbose 测试 apply --verbose 模式输出。
func TestApplyCmd_Verbose(t *testing.T) {
	tmpFile, err := os.CreateTemp("", "dqctl-apply-*.yaml")
	require.NoError(t, err)
	defer os.Remove(tmpFile.Name())
	_, _ = tmpFile.WriteString("test: value")

	output, err := captureOutput(t, func() error {
		rootCmd.SetArgs([]string{"--verbose", "apply", "-f", tmpFile.Name()})
		return rootCmd.Execute()
	})
	require.NoError(t, err)
	assert.True(t, strings.Contains(output, "verbose") || strings.Contains(output, "apply 完成"))
}
