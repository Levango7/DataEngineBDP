// Package security - crypto_test.go 加密工具单元测试。
//
// 覆盖：
//   - AES-256-GCM 加密往返（encrypt → decrypt 恢复明文）
//   - 密码脱敏（MaskPassword 不泄露明文）
//   - 密钥初始化校验（缺失/错误长度 fail-fast）
//   - 密文篡改检测（GCM 认证标签）
package security

import (
	"bytes"
	"encoding/base64"
	"strings"
	"testing"
)

// setTestKey 设置测试用加密密钥（32 字节，Base64 编码）。
func setTestKey(t *testing.T) {
	t.Helper()
	key := bytes.Repeat([]byte{0x01}, 32)
	setTestKeyCustom(t, key)
}

// setTestKeyCustom 设置自定义测试密钥并立即重新初始化全局密钥。
func setTestKeyCustom(t *testing.T, key []byte) {
	t.Helper()
	t.Setenv(EncryptionKeyEnv, base64.StdEncoding.EncodeToString(key))
	if err := InitEncryptionKey(); err != nil {
		t.Fatalf("InitEncryptionKey失败: %v", err)
	}
}

func TestEncryptDecryptRoundTrip(t *testing.T) {
	setTestKey(t)
	tests := []struct {
		name      string
		plaintext string
	}{
		{"空字符串", ""},
		{"短密码", "pw"},
		{"常见密码", "admin123"},
		{"长密码", strings.Repeat("a", 256)},
		{"含特殊字符", "P@ssw0rd!#$%^&*()"},
		{"含中文", "密码123"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			enc, err := Encrypt(tt.plaintext)
			if err != nil {
				t.Fatalf("Encrypt失败: %v", err)
			}
			// 空明文应返回空密文
			if tt.plaintext == "" {
				if enc != "" {
					t.Errorf("空明文应返回空字符串，得到 %q", enc)
				}
				return
			}
			// 密文不应等于明文
			if enc == tt.plaintext {
				t.Error("密文不应等于明文")
			}
			dec, err := Decrypt(enc)
			if err != nil {
				t.Fatalf("Decrypt失败: %v", err)
			}
			if dec != tt.plaintext {
				t.Errorf("往返失败：期望 %q，得到 %q", tt.plaintext, dec)
			}
		})
	}
}

func TestEncryptProducesDifferentCiphertext(t *testing.T) {
	setTestKey(t)
	// 相同明文每次加密应产生不同密文（随机 nonce）
	enc1, err := Encrypt("same-password")
	if err != nil {
		t.Fatalf("Encrypt失败: %v", err)
	}
	enc2, err := Encrypt("same-password")
	if err != nil {
		t.Fatalf("Encrypt失败: %v", err)
	}
	if enc1 == enc2 {
		t.Error("相同明文应产生不同密文（随机 nonce），但两次加密结果相同")
	}
	// 两个密文都应能解密为同一明文
	dec1, _ := Decrypt(enc1)
	dec2, _ := Decrypt(enc2)
	if dec1 != dec2 || dec1 != "same-password" {
		t.Errorf("两个密文应解密为同一明文，得到 %q 和 %q", dec1, dec2)
	}
}

func TestDecryptTamperedCiphertext(t *testing.T) {
	setTestKey(t)
	enc, err := Encrypt("secret-password")
	if err != nil {
		t.Fatalf("Encrypt失败: %v", err)
	}
	// 篡改密文：翻转最后一个字符
	tampered := enc[:len(enc)-1]
	if enc[len(enc)-1] == 'A' {
		tampered += "B"
	} else {
		tampered += "A"
	}
	_, err = Decrypt(tampered)
	if err == nil {
		t.Fatal("篡改密文后应解密失败（GCM认证），但成功了")
	}
}

func TestDecryptWrongKey(t *testing.T) {
	// 用密钥 A 加密
	setTestKeyCustom(t, bytes.Repeat([]byte{0x01}, 32))
	enc, err := Encrypt("password-with-key-a")
	if err != nil {
		t.Fatalf("Encrypt失败: %v", err)
	}
	// 切换到密钥 B 解密
	setTestKeyCustom(t, bytes.Repeat([]byte{0x02}, 32))
	_, err = Decrypt(enc)
	if err == nil {
		t.Fatal("用错误密钥解密应失败，但成功了")
	}
}

func TestInitEncryptionKeyMissing(t *testing.T) {
	t.Setenv(EncryptionKeyEnv, "")
	err := InitEncryptionKey()
	if err == nil {
		t.Fatal("密钥缺失应返回错误，但成功了")
	}
	if !strings.Contains(err.Error(), "required") {
		t.Errorf("错误消息应包含 'required'，得到: %v", err)
	}
}

func TestInitEncryptionKeyWrongLength(t *testing.T) {
	// 16 字节密钥（AES-128），不满足 32 字节要求
	// 直接设置环境变量，不通过 setTestKeyCustom（后者会触发初始化失败）
	t.Setenv(EncryptionKeyEnv, base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{0x01}, 16)))
	err := InitEncryptionKey()
	if err == nil {
		t.Fatal("密钥长度不足应返回错误，但成功了")
	}
	if !strings.Contains(err.Error(), "32 bytes") {
		t.Errorf("错误消息应包含 '32 bytes'，得到: %v", err)
	}
}

func TestInitEncryptionKeyInvalidBase64(t *testing.T) {
	t.Setenv(EncryptionKeyEnv, "!!!not-valid-base64!!!")
	err := InitEncryptionKey()
	if err == nil {
		t.Fatal("非法 Base64 应返回错误，但成功了")
	}
}

func TestInitEncryptionKeyValid(t *testing.T) {
	setTestKey(t)
	if err := InitEncryptionKey(); err != nil {
		t.Fatalf("有效密钥应初始化成功，但失败: %v", err)
	}
}

func TestMaskPassword(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  string
	}{
		{"空字符串", "", ""},
		{"短密码", "pw", "********"},
		{"长密码", "very-long-password-123", "********"},
		{"含特殊字符", "P@ss!", "********"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := MaskPassword(tt.input)
			if got != tt.want {
				t.Errorf("MaskPassword(%q) = %q, want %q", tt.input, got, tt.want)
			}
			// 掩码不应包含原始密码
			if tt.input != "" && strings.Contains(got, tt.input) {
				t.Errorf("掩码 %q 不应包含原始密码 %q", got, tt.input)
			}
		})
	}
}

func TestEncryptDecryptEmptyString(t *testing.T) {
	setTestKey(t)
	// 空明文应返回空密文，不执行加密
	enc, err := Encrypt("")
	if err != nil {
		t.Fatalf("Encrypt(\"\")失败: %v", err)
	}
	if enc != "" {
		t.Errorf("Encrypt(\"\") 应返回空字符串，得到 %q", enc)
	}
	dec, err := Decrypt("")
	if err != nil {
		t.Fatalf("Decrypt(\"\")失败: %v", err)
	}
	if dec != "" {
		t.Errorf("Decrypt(\"\") 应返回空字符串，得到 %q", dec)
	}
}
