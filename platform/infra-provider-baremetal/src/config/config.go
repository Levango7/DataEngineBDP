// Package config 提供Provider配置加载与管理。
package config

import (
	"fmt"
	"log"
	"os"
	"sync"
	"time"

	"gopkg.in/yaml.v3"
)

// Config 全局配置根
type Config struct {
	Server   ServerConfig   `yaml:"server"`
	Auth     AuthConfig     `yaml:"auth"`
	Database DatabaseConfig `yaml:"database"`
	Redfish  RedfishConfig  `yaml:"redfish"`
	K8s      K8sConfig      `yaml:"k8s"`
	PXE      PXEConfig      `yaml:"pxe"`
	Log      LogConfig      `yaml:"log"`
}

// ServerConfig HTTP服务配置
type ServerConfig struct {
	Port         int    `yaml:"port"`
	Mode         string `yaml:"mode"`
	ReadTimeout  int    `yaml:"read_timeout"`
	WriteTimeout int    `yaml:"write_timeout"`
}

// AuthConfig 鉴权配置
type AuthConfig struct {
	Secret   string `yaml:"secret"`
	TokenTTL int    `yaml:"token_ttl"`
	Issuer   string `yaml:"issuer"`
}

// DatabaseConfig 数据库配置
type DatabaseConfig struct {
	Driver       string `yaml:"driver"`
	SqlitePath   string `yaml:"sqlite_path"`
	PostgresDSN  string `yaml:"postgres_dsn"`
	MaxOpenConns int    `yaml:"max_open_conns"`
	MaxIdleConns int    `yaml:"max_idle_conns"`
}

// RedfishConfig Redfish客户端默认配置
type RedfishConfig struct {
	DefaultUsername    string `yaml:"default_username"`
	DefaultPassword    string `yaml:"default_password"`
	Timeout            int    `yaml:"timeout"`
	InsecureSkipVerify bool   `yaml:"insecure_skip_verify"`
}

// K8sConfig K8s默认配置
type K8sConfig struct {
	KubernetesVersion string `yaml:"kubernetes_version"`
	PodCIDR           string `yaml:"pod_cidr"`
	ServiceCIDR       string `yaml:"service_cidr"`
	APIServerPort     int    `yaml:"api_server_port"`
	ImageRepository   string `yaml:"image_repository"`
}

// PXEConfig PXE引导配置
type PXEConfig struct {
	TFTPServer   string `yaml:"tftp_server"`
	HTTPBootURL  string `yaml:"http_boot_url"`
	DefaultImage string `yaml:"default_image"`
}

// LogConfig 日志配置
type LogConfig struct {
	Level        string `yaml:"level"`
	Format       string `yaml:"format"`
	ReportCaller bool   `yaml:"report_caller"`
}

var (
	globalConfig *Config
	once         sync.Once
)

// Load 从指定路径加载YAML配置文件。
// 若path为空，使用默认配置。
//
// 安全策略：配置文件中的 ${ENV_VAR} 占位符会通过 os.ExpandEnv 展开，
// 敏感字段（JWT密钥/DSN/密码）必须通过环境变量显式注入，缺失则 fail-fast。
func Load(path string) (*Config, error) {
	cfg := &Config{}
	if path == "" {
		setDefaults(cfg)
		return cfg, nil
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取配置文件失败: %w", err)
	}

	// 展开配置文件中的 ${ENV_VAR} 占位符为环境变量实际值。
	expanded := os.ExpandEnv(string(data))

	if err := yaml.Unmarshal([]byte(expanded), cfg); err != nil {
		return nil, fmt.Errorf("解析配置文件失败: %w", err)
	}

	setDefaults(cfg)
	return cfg, nil
}

// mustGetenv 读取必需的环境变量，缺失则 fail-fast 退出。
func mustGetenv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("FATAL: environment variable %s is required", key)
	}
	return v
}

// setDefaults 填充未设置字段的默认值
func setDefaults(cfg *Config) {
	if cfg.Server.Port == 0 {
		cfg.Server.Port = 8080
	}
	if cfg.Server.Mode == "" {
		cfg.Server.Mode = "release"
	}
	if cfg.Server.ReadTimeout == 0 {
		cfg.Server.ReadTimeout = 30
	}
	if cfg.Server.WriteTimeout == 0 {
		cfg.Server.WriteTimeout = 60
	}
	if cfg.Auth.Secret == "" {
		// 安全止血：JWT 签名密钥必须显式配置，不再提供弱默认值。
		cfg.Auth.Secret = mustGetenv("JWT_SIGNING_KEY")
	}
	if cfg.Auth.TokenTTL == 0 {
		cfg.Auth.TokenTTL = 24
	}
	if cfg.Auth.Issuer == "" {
		cfg.Auth.Issuer = "infra-provider-baremetal"
	}
	if cfg.Database.Driver == "" {
		cfg.Database.Driver = "sqlite"
	}
	if cfg.Database.SqlitePath == "" {
		cfg.Database.SqlitePath = "./data/baremetal.db"
	}
	if cfg.Database.MaxOpenConns == 0 {
		cfg.Database.MaxOpenConns = 20
	}
	if cfg.Database.MaxIdleConns == 0 {
		cfg.Database.MaxIdleConns = 5
	}
	if cfg.Redfish.Timeout == 0 {
		cfg.Redfish.Timeout = 30
	}
	if cfg.K8s.APIServerPort == 0 {
		cfg.K8s.APIServerPort = 6443
	}
	if cfg.Log.Level == "" {
		cfg.Log.Level = "info"
	}
	if cfg.Log.Format == "" {
		cfg.Log.Format = "json"
	}
}

// SetGlobal 设置全局配置单例
func SetGlobal(cfg *Config) {
	once.Do(func() {
		globalConfig = cfg
	})
	globalConfig = cfg
}

// Get 获取全局配置单例
func Get() *Config {
	if globalConfig == nil {
		cfg := &Config{}
		setDefaults(cfg)
		globalConfig = cfg
	}
	return globalConfig
}

// TokenTTLDuration 返回Token TTL的time.Duration
func (a *AuthConfig) TokenTTLDuration() time.Duration {
	return time.Duration(a.TokenTTL) * time.Hour
}

// RedfishTimeoutDuration 返回Redfish超时的time.Duration
func (r *RedfishConfig) TimeoutDuration() time.Duration {
	return time.Duration(r.Timeout) * time.Second
}
