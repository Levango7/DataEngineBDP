package middleware

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func TestVerifySHA256(t *testing.T) {
	sum := sha256.Sum256([]byte("s3cret-pw"))
	cfg := &CredentialConfig{Username: "admin", PasswordSHA256: hex.EncodeToString(sum[:])}
	if !cfg.Verify("admin", "s3cret-pw") {
		t.Fatal("正确凭据应通过")
	}
	if cfg.Verify("admin", "wrong") {
		t.Fatal("错误密码应拒绝")
	}
	if cfg.Verify("root", "s3cret-pw") {
		t.Fatal("错误用户名应拒绝")
	}
}

func TestLoadCredentialConfigFailFast(t *testing.T) {
	t.Setenv("AUTH_PASSWORD_SHA256", "")
	t.Setenv("AUTH_PASSWORD", "")
	t.Setenv("AUTH_DEV_MODE", "")
	if _, err := LoadCredentialConfig(); err == nil {
		t.Fatal("无任何凭据配置时应报错")
	}
}

func TestLoadCredentialConfigDevMode(t *testing.T) {
	t.Setenv("AUTH_PASSWORD", "devpw")
	t.Setenv("AUTH_PASSWORD_SHA256", "")
	t.Setenv("AUTH_DEV_MODE", "true")
	cfg, err := LoadCredentialConfig()
	if err != nil {
		t.Fatalf("dev 模式应加载成功: %v", err)
	}
	if !cfg.Verify("admin", "devpw") {
		t.Fatal("dev 凭据校验应通过")
	}
	if cfg.DevPasswordSet() == false {
		t.Fatal("DevPasswordSet 应为 true")
	}
}

func TestLoadCredentialConfigRejectDevWithoutFlag(t *testing.T) {
	t.Setenv("AUTH_PASSWORD", "devpw")
	t.Setenv("AUTH_PASSWORD_SHA256", "")
	t.Setenv("AUTH_DEV_MODE", "false")
	if _, err := LoadCredentialConfig(); err == nil {
		t.Fatal("明文密码未开 dev 标志应报错")
	}
}

func TestLoadCredentialConfigBadHash(t *testing.T) {
	t.Setenv("AUTH_PASSWORD_SHA256", "not-hex")
	t.Setenv("AUTH_PASSWORD", "")
	if _, err := LoadCredentialConfig(); err == nil {
		t.Fatal("非法哈希应报错")
	}
}

func TestLoadCredentialConfigShortHash(t *testing.T) {
	sum := sha256.Sum256([]byte("x"))
	short := hex.EncodeToString(sum[:])[:32]
	t.Setenv("AUTH_PASSWORD_SHA256", short)
	if _, err := LoadCredentialConfig(); err == nil {
		t.Fatal("长度不足 64 的哈希应报错")
	}
}

func TestLoadCredentialConfigSHA256Mode(t *testing.T) {
	sum := sha256.Sum256([]byte("prod-pw"))
	t.Setenv("AUTH_PASSWORD_SHA256", hex.EncodeToString(sum[:]))
	t.Setenv("AUTH_PASSWORD", "")
	t.Setenv("AUTH_USERNAME", "ops")
	cfg, err := LoadCredentialConfig()
	if err != nil {
		t.Fatalf("生产模式应加载成功: %v", err)
	}
	if cfg.DevPasswordSet() {
		t.Fatal("生产模式不应有明文开发密码")
	}
	if !cfg.Verify("ops", "prod-pw") {
		t.Fatal("自定义用户名+正确密码应通过")
	}
	if cfg.Verify("admin", "prod-pw") {
		t.Fatal("用户名不匹配应拒绝（admin 不再硬编码）")
	}
}
