package config

import "os"

// Config AI 助手服务配置（环境变量注入）。
type Config struct {
	Port string

	// 会话 SQLite 持久化路径
	SessionDBPath string

	// 下游服务地址
	LlmGatewayURL string // llm-gateway（对话/图表/解读）
	Nl2SqlURL     string // nl2sql（NL→SQL）
	SqlGatewayURL string // sql-gateway（SQL 执行）
}

// Load 从环境变量加载配置（带默认值，本地可直跑）。
func Load() *Config {
	return &Config{
		Port:          getenv("AI_ASSISTANT_PORT", "18110"),
		SessionDBPath: getenv("AI_ASSISTANT_DB", "./data/ai-assistant.db"),
		LlmGatewayURL: getenv("LLM_GATEWAY_URL", "http://localhost:18090"),
		Nl2SqlURL:     getenv("NL2SQL_URL", "http://localhost:18095"),
		SqlGatewayURL: getenv("SQL_GATEWAY_URL", "http://localhost:18081"),
	}
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
