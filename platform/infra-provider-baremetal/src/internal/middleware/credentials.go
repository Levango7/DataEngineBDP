// credentials.go 实现登录凭据配置的加载与常量时间校验。
//
// 生产模式：AUTH_PASSWORD_SHA256 = 密码的 SHA-256 十六进制（64 字符），
// 由部署方在密钥管理系统生成后注入，明文密码不落任何配置文件。
// 开发模式：AUTH_DEV_MODE=true 且设置 AUTH_PASSWORD 明文，仅限本地联调，
// main.go 检测到该组合时打印醒目警告。
// 两者都缺失时 LoadCredentialConfig 返回错误，进程拒绝启动（fail-fast）。
package middleware

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"os"
	"strings"
)

// CredentialConfig 登录凭据配置
type CredentialConfig struct {
	Username       string
	PasswordSHA256 string // 64 位小写十六进制；为空表示开发明文模式
	devPassword    string // 仅 AUTH_DEV_MODE=true 时使用，生产恒为空
}

// LoadCredentialConfig 从环境变量加载凭据配置。
//
// 环境变量：
//   - AUTH_USERNAME: 管理员用户名，默认 admin
//   - AUTH_PASSWORD_SHA256: 生产密码哈希（SHA-256 hex）
//   - AUTH_PASSWORD: 开发明文密码（必须配合 AUTH_DEV_MODE=true）
//   - AUTH_DEV_MODE: "true" 时允许明文开发凭据
func LoadCredentialConfig() (*CredentialConfig, error) {
	username := os.Getenv("AUTH_USERNAME")
	if username == "" {
		username = "admin"
	}
	hashHex := strings.ToLower(strings.TrimSpace(os.Getenv("AUTH_PASSWORD_SHA256")))
	devPwd := os.Getenv("AUTH_PASSWORD")
	devMode := os.Getenv("AUTH_DEV_MODE") == "true"

	switch {
	case hashHex != "":
		if len(hashHex) != 64 {
			return nil, errors.New("AUTH_PASSWORD_SHA256 必须是 64 位十六进制(SHA-256)")
		}
		if _, err := hex.DecodeString(hashHex); err != nil {
			return nil, errors.New("AUTH_PASSWORD_SHA256 不是合法十六进制")
		}
		return &CredentialConfig{Username: username, PasswordSHA256: hashHex}, nil
	case devPwd != "" && devMode:
		return &CredentialConfig{Username: username, devPassword: devPwd}, nil
	case devPwd != "":
		return nil, errors.New("检测到 AUTH_PASSWORD 但未设置 AUTH_DEV_MODE=true；生产环境请改用 AUTH_PASSWORD_SHA256")
	default:
		return nil, errors.New("缺少登录凭据：请配置 AUTH_PASSWORD_SHA256（生产）或 AUTH_DEV_MODE=true + AUTH_PASSWORD（仅开发）")
	}
}

// LoadCredentialConfigWith 直接以参数构造凭据配置（供测试与程序化装配使用）。
func LoadCredentialConfigWith(username, passwordSHA256Hex, devPassword string) (*CredentialConfig, error) {
	if username == "" {
		username = "admin"
	}
	hash := strings.ToLower(strings.TrimSpace(passwordSHA256Hex))
	if hash != "" {
		if len(hash) != 64 {
			return nil, errors.New("密码哈希必须是 64 位十六进制(SHA-256)")
		}
		if _, err := hex.DecodeString(hash); err != nil {
			return nil, errors.New("密码哈希不是合法十六进制")
		}
	}
	if hash == "" && devPassword == "" {
		return nil, errors.New("至少提供密码哈希或开发明文密码之一")
	}
	if hash != "" && devPassword != "" {
		return nil, errors.New("密码哈希与开发明文密码不可同时提供")
	}
	return &CredentialConfig{Username: username, PasswordSHA256: hash, devPassword: devPassword}, nil
}

// Verify 常量时间比较用户名与密码，防时序侧信道与用户名枚举。
func (c *CredentialConfig) Verify(username, password string) bool {
	userOK := subtle.ConstantTimeCompare([]byte(c.Username), []byte(username)) == 1
	var pwdOK bool
	if c.PasswordSHA256 != "" {
		sum := sha256.Sum256([]byte(password))
		pwdOK = subtle.ConstantTimeCompare([]byte(c.PasswordSHA256), []byte(hex.EncodeToString(sum[:]))) == 1
	} else {
		pwdOK = subtle.ConstantTimeCompare([]byte(c.devPassword), []byte(password)) == 1
	}
	// 即使用户名已失败也完成密码比较，保持时间曲线一致
	return userOK && pwdOK
}

// DevPasswordSet 报告是否处于明文开发凭据模式（供启动告警使用）。
func (c *CredentialConfig) DevPasswordSet() bool {
	return c.devPassword != ""
}
