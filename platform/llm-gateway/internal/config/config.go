// Package config 提供 llm-gateway 的配置加载与 Provider 构造。
//
// 支持从 YAML 文件或环境变量加载配置，并据此构造各 Provider 实例。
// 设计原则：真实大模型 API 凭据通过配置注入，不硬编码。
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
)

// ============ 顶层配置 ============

// Config 网关顶层配置。
type Config struct {
	Server    ServerConfig     `yaml:"server" json:"server"`
	Providers []ProviderConfig `yaml:"providers" json:"providers"`
	Routes    []RouteRule      `yaml:"routes" json:"routes"`
	RateLimit RateLimitConfig  `yaml:"rateLimit" json:"rateLimit"`
	Audit     AuditConfig      `yaml:"audit" json:"audit"`
}

// ServerConfig HTTP 服务器配置。
type ServerConfig struct {
	Port    string `yaml:"port" json:"port"`
	Version string `yaml:"version" json:"version"`
}

// ProviderConfig 单个 Provider 配置。
//
// Type 取值：openai / wenxin / qianwen / zhipu / mock。
// 同一 Type 可配置多个实例（不同 name），由 LoadBalancer 在多实例间负载均衡。
type ProviderConfig struct {
	Name     string               `yaml:"name" json:"name"`
	Type     string               `yaml:"type" json:"type"`
	Endpoint string               `yaml:"endpoint" json:"endpoint"`
	APIKey   string               `yaml:"apiKey" json:"apiKey"`
	Models   []provider.ModelInfo `yaml:"models" json:"models"`
	Weight   int                  `yaml:"weight" json:"weight"` // 负载均衡权重
}

// RouteRule 模型路由规则。
//
// 将逻辑模型名（如 "gpt-4"）路由到指定 Provider 实例。
// 若指定 TenantID，则仅对该租户生效（租户级路由）。
type RouteRule struct {
	Model    string `yaml:"model" json:"model"`
	Provider string `yaml:"provider" json:"provider"`
	TenantID string `yaml:"tenantId,omitempty" json:"tenantId,omitempty"`
	Priority int    `yaml:"priority,omitempty" json:"priority,omitempty"`
}

// RateLimitConfig 限流配置。
type RateLimitConfig struct {
	Enabled bool `yaml:"enabled" json:"enabled"`
	// RPM 全局每分钟请求数上限。
	RPM int `yaml:"rpm" json:"rpm"`
	// TPM 全局每分钟 Token 数上限。
	TPM int `yaml:"tpm" json:"tpm"`
}

// AuditConfig 审计配置。
type AuditConfig struct {
	Enabled        bool     `yaml:"enabled" json:"enabled"`
	SensitiveWords []string `yaml:"sensitiveWords" json:"sensitiveWords"`
	LogPath        string   `yaml:"logPath" json:"logPath"`
}

// ============ 加载 ============

// LoadFromFile 从 YAML 文件加载配置。
func LoadFromFile(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config file %s: %w", path, err)
	}
	var cfg Config
	if err := yaml.Unmarshal(raw, &cfg); err != nil {
		return nil, fmt.Errorf("unmarshal yaml: %w", err)
	}
	cfg.applyDefaults()
	return &cfg, nil
}

// LoadFromJSON 从 JSON 字符串加载配置（便于测试）。
func LoadFromJSON(s string) (*Config, error) {
	var cfg Config
	if err := json.Unmarshal([]byte(s), &cfg); err != nil {
		return nil, fmt.Errorf("unmarshal json: %w", err)
	}
	cfg.applyDefaults()
	return &cfg, nil
}

// LoadFromEnv 从环境变量加载最小配置（开发环境）。
//
// 环境变量：
//   - LLM_GATEWAY_PORT: 服务端口（默认 8084）
//   - LLM_GATEWAY_VERSION: 版本（默认 0.2.0，与 main.go defaultVersion 保持一致）
//   - LLM_GATEWAY_PROVIDERS: 逗号分隔的 Provider 类型列表（默认 "openai,qianwen"）
//   - LLM_GATEWAY_MOCK_MODE: "true" 时强制启用 Mock Provider（默认 false，
//     与评估报告 6.3 对齐：开箱即用不再静默 mock；未配置任何 provider 时仍兜底 mock）
func LoadFromEnv() *Config {
	cfg := &Config{
		Server: ServerConfig{
			Port: envOr("LLM_GATEWAY_PORT", "8084"),
			// 修复：原默认 "0.1.0" 与 main.go 中 defaultVersion="0.2.0" 不一致，
			// 导致环境变量未显式设置时，/health 返回的版本与日志中的版本不匹配。
			Version: envOr("LLM_GATEWAY_VERSION", "0.2.0"),
		},
		Providers: nil,
		RateLimit: RateLimitConfig{Enabled: false},
		Audit:     AuditConfig{Enabled: true, SensitiveWords: []string{}},
	}

	mockMode := envOr("LLM_GATEWAY_MOCK_MODE", "false")
	providersList := envOr("LLM_GATEWAY_PROVIDERS", "openai,qianwen")

	if strings.EqualFold(mockMode, "true") {
		// 强制 Mock 模式
		cfg.Providers = append(cfg.Providers, ProviderConfig{
			Name:   "mock",
			Type:   "mock",
			Weight: 1,
			Models: []provider.ModelInfo{
				{ID: "mock-gpt-4", Object: "model", OwnedBy: "mock"},
				{ID: "mock-embedding", Object: "model", OwnedBy: "mock"},
			},
		})
		return cfg
	}

	for _, t := range strings.Split(providersList, ",") {
		t = strings.TrimSpace(t)
		if t == "" {
			continue
		}
		pc := ProviderConfig{Name: t, Type: t, Weight: 1}
		// 从环境变量读取该 Provider 的凭据
		pc.Endpoint = os.Getenv(strings.ToUpper(t) + "_ENDPOINT")
		pc.APIKey = os.Getenv(strings.ToUpper(t) + "_API_KEY")
		cfg.Providers = append(cfg.Providers, pc)
	}
	if len(cfg.Providers) == 0 {
		// 兜底
		cfg.Providers = append(cfg.Providers, ProviderConfig{Name: "mock", Type: "mock", Weight: 1})
	}
	return cfg
}

// applyDefaults 填充默认值。
func (c *Config) applyDefaults() {
	if c.Server.Port == "" {
		c.Server.Port = "8084"
	}
	if c.Server.Version == "" {
		// 修复：与 main.go defaultVersion 保持一致，避免 /health 返回版本与日志不匹配。
		c.Server.Version = "0.2.0"
	}
	for i := range c.Providers {
		if c.Providers[i].Weight <= 0 {
			c.Providers[i].Weight = 1
		}
	}
}

// ============ 构造 Provider ============

// BuildProviders 根据配置构造 Provider 实例列表。
//
// 返回的 Provider 已按配置实例化，未识别的 Type 跳过并返回 warning。
func BuildProviders(cfgs []ProviderConfig) ([]provider.LLMProvider, error) {
	providers := make([]provider.LLMProvider, 0, len(cfgs))
	for _, pc := range cfgs {
		p, err := buildOne(pc)
		if err != nil {
			return nil, fmt.Errorf("build provider %s: %w", pc.Name, err)
		}
		providers = append(providers, p)
	}
	if len(providers) == 0 {
		return nil, fmt.Errorf("no provider configured")
	}
	return providers, nil
}

// buildOne 构造单个 Provider。
func buildOne(pc ProviderConfig) (provider.LLMProvider, error) {
	switch strings.ToLower(pc.Type) {
	case "openai":
		return provider.NewOpenAIProvider(provider.OpenAIConfig{
			Endpoint: pc.Endpoint, APIKey: pc.APIKey, Models: pc.Models,
		}), nil
	case "wenxin":
		return provider.NewWenxinProvider(provider.WenxinConfig{
			Endpoint: pc.Endpoint, APIKey: pc.APIKey, Models: pc.Models,
		}), nil
	case "qianwen":
		return provider.NewQianwenProvider(provider.QianwenConfig{
			Endpoint: pc.Endpoint, APIKey: pc.APIKey, Models: pc.Models,
		}), nil
	case "zhipu":
		return provider.NewZhipuProvider(provider.ZhipuConfig{
			Endpoint: pc.Endpoint, APIKey: pc.APIKey, Models: pc.Models,
		}), nil
	case "mock":
		return provider.NewMockProvider(provider.MockConfig{
			Name: pc.Name, Models: pc.Models,
		}), nil
	default:
		return nil, fmt.Errorf("unknown provider type: %s", pc.Type)
	}
}

// envOr 读环境变量，缺省返回默认值。
func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
