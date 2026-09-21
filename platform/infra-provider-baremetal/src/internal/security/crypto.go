// Package security 提供凭据加密/解密与脱敏工具。
//
// 使用 AES-256-GCM 对称加密（仅依赖 Go 标准库 crypto/aes + crypto/cipher），
// 密钥从环境变量 CREDENTIAL_ENCRYPTION_KEY 读取（Base64 编码的 32 字节密钥）。
// 启动时校验密钥长度，不满足则 fail-fast（log.Fatal）。
//
// 加密结果布局：Base64( nonce(12B) || ciphertext || GCM_tag(16B) )，
// 每次加密使用 crypto/rand 生成的随机 nonce，保证相同明文产出不同密文。
package security

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"sync"
)

const (
	// encryptionKeyLen AES-256 密钥长度（字节），256 位 = 32 字节。
	encryptionKeyLen = 32
	// EncryptionKeyEnv 加密密钥环境变量名。
	// 部署方应通过密钥管理系统生成 32 字节随机数据，Base64 编码后注入。
	EncryptionKeyEnv = "CREDENTIAL_ENCRYPTION_KEY"
)

var (
	globalKey []byte
	keyMu     sync.RWMutex
)

// InitEncryptionKey 从环境变量 CREDENTIAL_ENCRYPTION_KEY 读取并校验加密密钥。
//
// 密钥必须为 Base64 编码的 32 字节（256 位）随机数据。
// 缺失或长度不满足则返回错误，调用方应 fail-fast。
// 本函数可重复调用（测试友好），每次调用重新读取环境变量。
func InitEncryptionKey() error {
	raw := os.Getenv(EncryptionKeyEnv)
	if raw == "" {
		return fmt.Errorf("environment variable %s is required (Base64-encoded %d-byte key)", EncryptionKeyEnv, encryptionKeyLen)
	}
	key, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		return fmt.Errorf("decode %s failed: %w", EncryptionKeyEnv, err)
	}
	if len(key) != encryptionKeyLen {
		return fmt.Errorf("%s must be %d bytes after Base64 decode, got %d", EncryptionKeyEnv, encryptionKeyLen, len(key))
	}
	keyMu.Lock()
	globalKey = key
	keyMu.Unlock()
	return nil
}

// MustInitEncryptionKey 初始化加密密钥，失败则 log.Fatal 拒绝启动。
//
// 应在应用启动早期（路由注册之前）调用，确保无有效密钥时服务无法启动。
func MustInitEncryptionKey() {
	if err := InitEncryptionKey(); err != nil {
		log.Fatalf("FATAL: %v", err)
	}
}

// getKey 获取已初始化的密钥；未初始化则尝试从环境变量加载。
func getKey() ([]byte, error) {
	keyMu.RLock()
	key := globalKey
	keyMu.RUnlock()
	if key != nil {
		return key, nil
	}
	if err := InitEncryptionKey(); err != nil {
		return nil, err
	}
	keyMu.RLock()
	defer keyMu.RUnlock()
	return globalKey, nil
}

// Encrypt 使用 AES-256-GCM 加密明文，返回 Base64 编码的 nonce+密文+tag。
//
// 空明文返回空字符串（不加密），便于表示"未设置密码"的语义。
func Encrypt(plaintext string) (string, error) {
	if plaintext == "" {
		return "", nil
	}
	key, err := getKey()
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", fmt.Errorf("create AES cipher failed: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("create GCM failed: %w", err)
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", fmt.Errorf("generate nonce failed: %w", err)
	}
	// Seal 将密文与 tag 追加到 nonce 之后：nonce || ciphertext || tag
	ciphertext := gcm.Seal(nonce, nonce, []byte(plaintext), nil)
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// Decrypt 解密 Encrypt 产出的 Base64 字符串，返回明文。
//
// 空字符串输入返回空字符串（与 Encrypt 的空明文语义对称）。
// 若密文被篡改或密钥不匹配，GCM 认证标签校验将失败并返回错误。
func Decrypt(encoded string) (string, error) {
	if encoded == "" {
		return "", nil
	}
	key, err := getKey()
	if err != nil {
		return "", err
	}
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", fmt.Errorf("base64 decode failed: %w", err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", fmt.Errorf("create AES cipher failed: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("create GCM failed: %w", err)
	}
	if len(data) < gcm.NonceSize() {
		return "", errors.New("ciphertext too short: missing nonce")
	}
	nonce, ciphertext := data[:gcm.NonceSize()], data[gcm.NonceSize():]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return "", fmt.Errorf("GCM authenticate and decrypt failed: %w", err)
	}
	return string(plaintext), nil
}

// MaskPassword 脱敏密码，返回固定掩码，不泄露原长度。
//
// 用于日志输出场景，确保明文密码不出现在任何日志中。
// 空字符串返回空字符串，保留"未设置"语义。
func MaskPassword(s string) string {
	if s == "" {
		return ""
	}
	return "********"
}
