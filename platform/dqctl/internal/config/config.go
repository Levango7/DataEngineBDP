// Package config 管理 dqctl 的配置文件加载与保存。
//
// 配置文件默认位于 ~/.dqctl/config.yaml，使用 viper 进行 mapstructure 解析。
package config

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/spf13/viper"
)

// Config 是 dqctl 的配置结构。
type Config struct {
	PlatformURL string `mapstructure:"platform_url"`
	TenantID    string `mapstructure:"tenant_id"`
	Token       string `mapstructure:"token"`
	Output      string `mapstructure:"output"`
}

// Load 从指定路径加载配置文件。若文件不存在则返回错误。
func Load(configPath string) (*Config, error) {
	v := viper.New()
	v.SetConfigFile(configPath)
	if err := v.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("读取配置文件 %s 失败: %w", configPath, err)
	}

	var cfg Config
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("解析配置文件失败: %w", err)
	}
	return &cfg, nil
}

// Save 将配置写入指定路径，必要时创建父目录。
// 配置含认证 token，目录与文件权限收紧为 0700/0600。
func Save(configPath string, cfg *Config) error {
	if err := os.MkdirAll(filepath.Dir(configPath), 0o700); err != nil {
		return fmt.Errorf("创建配置目录失败: %w", err)
	}

	v := viper.New()
	v.Set("platform_url", cfg.PlatformURL)
	v.Set("tenant_id", cfg.TenantID)
	v.Set("token", cfg.Token)
	v.Set("output", cfg.Output)
	v.SetConfigFile(configPath)
	v.SetConfigType("yaml")

	if err := v.WriteConfig(); err != nil {
		return fmt.Errorf("写入配置文件失败: %w", err)
	}
	if err := os.Chmod(configPath, 0o600); err != nil {
		return fmt.Errorf("设置配置文件权限失败: %w", err)
	}
	return nil
}
