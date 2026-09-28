package middleware

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// captureWarn 返回一个告警捕获函数及其已捕获的消息切片指针。
// 传入 checkJWTSigningKey 的 warn 参数，便于断言告警内容。
func captureWarn() (func(string, ...any), *[]string) {
	var msgs []string
	fn := func(format string, args ...any) {
		msgs = append(msgs, fmt.Sprintf(format, args...))
	}
	return fn, &msgs
}

// ============ checkJWTSigningKey 核心校验逻辑测试 ============

// TestCheckJWTSigningKey_StrongKeyPass 密钥长度 > 32 字节时，非 dev 模式校验通过。
func TestCheckJWTSigningKey_StrongKeyPass(t *testing.T) {
	warn, _ := captureWarn()
	// 48 字节密钥，超过 32 字节下限。
	secret := strings.Repeat("a", 48)
	err := checkJWTSigningKey(secret, false, warn)
	assert.NoError(t, err)
}

// TestCheckJWTSigningKey_Exact32BytesPass 密钥长度正好 32 字节（256 位）时校验通过。
func TestCheckJWTSigningKey_Exact32BytesPass(t *testing.T) {
	warn, _ := captureWarn()
	secret := strings.Repeat("a", jwtMinKeyLen) // 正好 32 字节
	err := checkJWTSigningKey(secret, false, warn)
	assert.NoError(t, err)
}

// TestCheckJWTSigningKey_ShortKeyFail 密钥长度 < 32 字节时，非 dev 模式校验失败。
func TestCheckJWTSigningKey_ShortKeyFail(t *testing.T) {
	warn, _ := captureWarn()
	secret := "shortkey" // 8 字节，远低于 32 字节下限
	err := checkJWTSigningKey(secret, false, warn)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "too short")
	assert.Contains(t, err.Error(), "32")
}

// TestCheckJWTSigningKey_EmptyKeyFail 空密钥时，非 dev 模式校验失败并提示 required。
func TestCheckJWTSigningKey_EmptyKeyFail(t *testing.T) {
	warn, _ := captureWarn()
	err := checkJWTSigningKey("", false, warn)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "required")
}

// TestCheckJWTSigningKey_DevModeShortKeyWarn dev 模式下短密钥可启动，但输出告警。
func TestCheckJWTSigningKey_DevModeShortKeyWarn(t *testing.T) {
	warn, msgs := captureWarn()
	secret := "short" // 5 字节
	err := checkJWTSigningKey(secret, true, warn)
	assert.NoError(t, err, "dev 模式应放宽校验，不返回错误")
	require.Len(t, *msgs, 1, "应输出一条告警")
	assert.Contains(t, (*msgs)[0], "JWT_DEV_MODE")
	assert.Contains(t, (*msgs)[0], "开发模式")
}

// TestCheckJWTSigningKey_DevModeEmptyKeyWarn dev 模式下空密钥也可启动（放宽校验）。
func TestCheckJWTSigningKey_DevModeEmptyKeyWarn(t *testing.T) {
	warn, msgs := captureWarn()
	err := checkJWTSigningKey("", true, warn)
	assert.NoError(t, err)
	require.Len(t, *msgs, 1)
	assert.Contains(t, (*msgs)[0], "JWT_DEV_MODE")
}

// TestCheckJWTSigningKey_DevModeStrongKeyStillWarns dev 模式下强密钥仍打印告警（提醒勿用于生产）。
func TestCheckJWTSigningKey_DevModeStrongKeyStillWarns(t *testing.T) {
	warn, msgs := captureWarn()
	secret := strings.Repeat("a", 64)
	err := checkJWTSigningKey(secret, true, warn)
	assert.NoError(t, err)
	require.Len(t, *msgs, 1)
	assert.Contains(t, (*msgs)[0], "生产环境切勿开启")
}

// ============ ValidateJWTSigningKey 端到端测试 ============

// TestValidateJWTSigningKey_StrongKeyNoFatal 32 字节密钥、非 dev 模式下启动校验通过（不 fatal）。
func TestValidateJWTSigningKey_StrongKeyNoFatal(t *testing.T) {
	t.Setenv("JWT_DEV_MODE", "false")
	t.Setenv("JWT_SIGNING_KEY", strings.Repeat("x", 32))
	// 若校验失败会 log.Fatal 退出进程，测试框架会报 FAIL。
	ValidateJWTSigningKey()
}

// TestValidateJWTSigningKey_DevModeShortKeyNoFatal dev 模式下短密钥可启动（不 fatal）。
func TestValidateJWTSigningKey_DevModeShortKeyNoFatal(t *testing.T) {
	t.Setenv("JWT_DEV_MODE", "true")
	t.Setenv("JWT_SIGNING_KEY", "short")
	// dev 模式放宽校验，不会 fatal。
	ValidateJWTSigningKey()
}

// TestValidateJWTSigningKey_DevModeEmptyKeyNoFatal dev 模式下空密钥也可启动。
func TestValidateJWTSigningKey_DevModeEmptyKeyNoFatal(t *testing.T) {
	t.Setenv("JWT_DEV_MODE", "true")
	t.Setenv("JWT_SIGNING_KEY", "")
	ValidateJWTSigningKey()
}

// TestValidateJWTSigningKey_ShortKeyFatal 短密钥、非 dev 模式下 log.Fatal 拒绝启动。
//
// 由于 log.Fatal 会调用 os.Exit(1) 终止进程，无法在当前测试进程内直接捕获，
// 因此采用子进程模式：父进程以特定环境变量重新执行本测试，子进程调用
// ValidateJWTSigningKey 后若未 fatal 则以非 1 退出码标记，父进程据此断言。
func TestValidateJWTSigningKey_ShortKeyFatal(t *testing.T) {
	if os.Getenv("JWT_FATAL_SUBPROC") == "1" {
		// 子进程模式：直接调用校验，期望 log.Fatal 以退出码 1 终止。
		ValidateJWTSigningKey()
		// 若执行到这里，说明未 fatal（校验未生效），以退出码 99 标记异常。
		os.Exit(99)
	}

	// 父进程：启动子进程运行本测试，注入短密钥环境。
	cmd := exec.Command(os.Args[0], "-test.run=^TestValidateJWTSigningKey_ShortKeyFatal$")
	cmd.Env = append(os.Environ(),
		"JWT_FATAL_SUBPROC=1",
		"JWT_DEV_MODE=false",
		"JWT_SIGNING_KEY=shortkey",
	)
	err := cmd.Run()
	require.Error(t, err, "期望子进程因 log.Fatal 退出，但实际正常退出")

	var exitErr *exec.ExitError
	require.True(t, errors.As(err, &exitErr), "期望 *exec.ExitError，实际错误: %v", err)
	assert.Equal(t, 1, exitErr.ExitCode(), "期望 log.Fatal 退出码 1，实际 %d", exitErr.ExitCode())
}

// TestValidateJWTSigningKey_EmptyKeyFatal 空密钥、非 dev 模式下 log.Fatal 拒绝启动。
func TestValidateJWTSigningKey_EmptyKeyFatal(t *testing.T) {
	if os.Getenv("JWT_FATAL_SUBPROC") == "1" {
		ValidateJWTSigningKey()
		os.Exit(99)
	}

	cmd := exec.Command(os.Args[0], "-test.run=^TestValidateJWTSigningKey_EmptyKeyFatal$")
	cmd.Env = append(os.Environ(),
		"JWT_FATAL_SUBPROC=1",
		"JWT_DEV_MODE=false",
		"JWT_SIGNING_KEY=",
	)
	err := cmd.Run()
	require.Error(t, err)

	var exitErr *exec.ExitError
	require.True(t, errors.As(err, &exitErr), "期望 *exec.ExitError，实际错误: %v", err)
	assert.Equal(t, 1, exitErr.ExitCode(), "期望 log.Fatal 退出码 1，实际 %d", exitErr.ExitCode())
}
