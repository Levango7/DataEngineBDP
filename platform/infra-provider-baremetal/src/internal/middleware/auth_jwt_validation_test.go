// Package middleware - auth_jwt_validation_test.go JWT 签名密钥校验单元测试。
//
// 覆盖：
//   - 密钥长度 >= 32 字节通过校验
//   - 密钥长度 < 32 字节返回错误（fail-fast）
//   - 空密钥返回错误
//   - JWT_DEV_MODE=true 放宽校验但打印告警
//   - ValidateJWTSigningKey 集成校验
package middleware

import (
	"strings"
	"testing"
)

// captureWarn 返回一个捕获告警消息的 warn 函数。
func captureWarn() (func(string, ...any), *[]string) {
	var msgs []string
	fn := func(format string, args ...any) {
		msgs = append(msgs, format)
	}
	return fn, &msgs
}

func TestCheckJWTSigningKey_StrongKey(t *testing.T) {
	// 32 字节密钥应通过校验
	secret := strings.Repeat("a", 32)
	warn, msgs := captureWarn()
	err := checkJWTSigningKey(secret, false, warn)
	if err != nil {
		t.Errorf("32字节密钥应通过校验，但返回错误: %v", err)
	}
	if len(*msgs) != 0 {
		t.Errorf("非dev模式不应打印告警，但得到: %v", *msgs)
	}
}

func TestCheckJWTSigningKey_TooShort(t *testing.T) {
	// 31 字节密钥应拒绝
	secret := strings.Repeat("a", 31)
	warn, _ := captureWarn()
	err := checkJWTSigningKey(secret, false, warn)
	if err == nil {
		t.Fatal("31字节密钥应返回错误，但通过了")
	}
	if !strings.Contains(err.Error(), "too short") {
		t.Errorf("错误消息应包含 'too short'，得到: %v", err)
	}
}

func TestCheckJWTSigningKey_Empty(t *testing.T) {
	warn, _ := captureWarn()
	err := checkJWTSigningKey("", false, warn)
	if err == nil {
		t.Fatal("空密钥应返回错误，但通过了")
	}
	if !strings.Contains(err.Error(), "required") {
		t.Errorf("错误消息应包含 'required'，得到: %v", err)
	}
}

func TestCheckJWTSigningKey_DevMode_AllowsShortKey(t *testing.T) {
	// dev 模式下短密钥应通过，但打印告警
	warn, msgs := captureWarn()
	err := checkJWTSigningKey("short", true, warn)
	if err != nil {
		t.Errorf("dev模式短密钥应通过校验，但返回错误: %v", err)
	}
	if len(*msgs) == 0 {
		t.Error("dev模式应打印告警，但未打印")
	}
	if !strings.Contains((*msgs)[0], "JWT_DEV_MODE") {
		t.Errorf("告警应包含 'JWT_DEV_MODE'，得到: %s", (*msgs)[0])
	}
}

func TestCheckJWTSigningKey_DevMode_AllowsEmptyKey(t *testing.T) {
	warn, msgs := captureWarn()
	err := checkJWTSigningKey("", true, warn)
	if err != nil {
		t.Errorf("dev模式空密钥应通过校验，但返回错误: %v", err)
	}
	if len(*msgs) == 0 {
		t.Error("dev模式应打印告警，但未打印")
	}
}

func TestValidateJWTSigningKey_StrongKey(t *testing.T) {
	// 非 dev 模式，32 字节密钥应通过
	t.Setenv("JWT_DEV_MODE", "false")
	err := ValidateJWTSigningKey(strings.Repeat("a", 32))
	if err != nil {
		t.Errorf("32字节密钥应通过校验，但返回错误: %v", err)
	}
}

func TestValidateJWTSigningKey_TooShort(t *testing.T) {
	t.Setenv("JWT_DEV_MODE", "false")
	err := ValidateJWTSigningKey(strings.Repeat("a", 16))
	if err == nil {
		t.Fatal("16字节密钥应返回错误，但通过了")
	}
}

func TestValidateJWTSigningKey_Empty(t *testing.T) {
	t.Setenv("JWT_DEV_MODE", "false")
	err := ValidateJWTSigningKey("")
	if err == nil {
		t.Fatal("空密钥应返回错误，但通过了")
	}
}

func TestValidateJWTSigningKey_DevMode(t *testing.T) {
	// dev 模式下短密钥应通过
	t.Setenv("JWT_DEV_MODE", "true")
	err := ValidateJWTSigningKey("short")
	if err != nil {
		t.Errorf("dev模式短密钥应通过校验，但返回错误: %v", err)
	}
}

func TestValidateJWTSigningKey_DevModeFalse(t *testing.T) {
	// JWT_DEV_MODE=false 应执行严格校验
	t.Setenv("JWT_DEV_MODE", "false")
	err := ValidateJWTSigningKey("short")
	if err == nil {
		t.Fatal("JWT_DEV_MODE=false 时短密钥应返回错误，但通过了")
	}
}

func TestCheckJWTSigningKey_Exactly32Bytes(t *testing.T) {
	// 恰好 32 字节应通过（边界值）
	warn, _ := captureWarn()
	err := checkJWTSigningKey(strings.Repeat("x", 32), false, warn)
	if err != nil {
		t.Errorf("恰好32字节应通过校验，但返回错误: %v", err)
	}
}

func TestCheckJWTSigningKey_33Bytes(t *testing.T) {
	// 33 字节也应通过
	warn, _ := captureWarn()
	err := checkJWTSigningKey(strings.Repeat("x", 33), false, warn)
	if err != nil {
		t.Errorf("33字节应通过校验，但返回错误: %v", err)
	}
}
