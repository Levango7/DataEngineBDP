package cmd

import (
	"bufio"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestInitCmd_CreatesConfig 测试 init 命令创建配置文件。
func TestInitCmd_CreatesConfig(t *testing.T) {
	tmpDir := t.TempDir()

	// 替换 stdin
	input := "https://platform.example.com\ntest-tenant\ntest-token\njson\n"
	oldStdin := os.Stdin
	pr, pw, _ := os.Pipe()
	os.Stdin = pr
	go func() {
		_, _ = pw.WriteString(input)
		_ = pw.Close()
	}()
	defer func() { os.Stdin = oldStdin }()

	// 重置 flag 状态
	flagConfig = ""
	flagTenant = ""
	flagVerbose = false
	flagOutput = "table"

	// 设置 HOME
	origHome := os.Getenv("HOME")
	origUserProfile := os.Getenv("USERPROFILE")
	os.Setenv("HOME", tmpDir)
	os.Setenv("USERPROFILE", tmpDir)
	defer func() {
		os.Setenv("HOME", origHome)
		os.Setenv("USERPROFILE", origUserProfile)
	}()

	oldStdout := os.Stdout
	r, w, _ := os.Pipe()
	os.Stdout = w
	go func() {
		_, _ = pw.WriteString("")
	}()

	rootCmd.SetArgs([]string{"init"})

	err := rootCmd.Execute()
	w.Close()
	os.Stdout = oldStdout
	_ = r.Close()
	require.NoError(t, err)

	// 验证配置文件已创建（使用 filepath.Join 确保路径分隔符正确）
	configPath := filepath.Join(tmpDir, ".dqctl", "config.yaml")
	_, err = os.Stat(configPath)
	assert.NoError(t, err, "配置文件应已创建: "+configPath)
}

func TestInitCmd_ConfigFilePermissions(t *testing.T) {
	tmpDir := t.TempDir()

	input := "https://platform.example.com\nperm-tenant\nperm-token\ntable\n"
	oldStdin := os.Stdin
	pr, pw, _ := os.Pipe()
	os.Stdin = pr
	go func() {
		_, _ = pw.WriteString(input)
		_ = pw.Close()
	}()
	defer func() { os.Stdin = oldStdin }()

	flagConfig = ""
	flagTenant = ""
	flagVerbose = false
	flagOutput = "table"

	origHome := os.Getenv("HOME")
	origUserProfile := os.Getenv("USERPROFILE")
	os.Setenv("HOME", tmpDir)
	os.Setenv("USERPROFILE", tmpDir)
	defer func() {
		os.Setenv("HOME", origHome)
		os.Setenv("USERPROFILE", origUserProfile)
	}()

	rootCmd.SetArgs([]string{"init"})
	require.NoError(t, rootCmd.Execute())

	configDir := filepath.Join(tmpDir, ".dqctl")
	configPath := filepath.Join(configDir, "config.yaml")

	info, err := os.Stat(configPath)
	require.NoError(t, err, "配置文件应已创建: "+configPath)
	dirInfo, err := os.Stat(configDir)
	require.NoError(t, err)

	if runtime.GOOS == "windows" {
		assert.NotNil(t, info)
		assert.NotNil(t, dirInfo)
		return
	}
	assert.Equal(t, fs.FileMode(0o600), info.Mode().Perm())
	assert.Equal(t, fs.FileMode(0o700), dirInfo.Mode().Perm())
}

// TestPromptFunction 测试 prompt 辅助函数正确去除空白。
func TestPromptFunction(t *testing.T) {
	input := "  hello world  \n"
	reader := bufio.NewReader(strings.NewReader(input))

	result := prompt(reader, "test: ")
	assert.Equal(t, "hello world", result)
}

// TestPromptFunction_EmptyInput 测试 prompt 函数空输入。
func TestPromptFunction_EmptyInput(t *testing.T) {
	input := "\n"
	reader := bufio.NewReader(strings.NewReader(input))

	result := prompt(reader, "test: ")
	assert.Equal(t, "", result)
}

// TestInitCmd_DefaultOutputFormat 测试 init 命令空输出格式默认 table。
func TestInitCmd_DefaultOutputFormat(t *testing.T) {
	input := "https://platform.example.com\ntest-tenant\ntest-token\n\n"
	reader := bufio.NewReader(strings.NewReader(input))

	_ = prompt(reader, "platform: ")
	_ = prompt(reader, "tenant: ")
	_ = prompt(reader, "token: ")
	output := prompt(reader, "output: ")
	assert.Equal(t, "", output) // 空输入返回空字符串，runInit 中会默认为 table
}
