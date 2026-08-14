package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/config"
)

// DownstreamProxy 下游服务 HTTP 代理。
//
// 职责：
//   - LlmChat:   调用 llm-gateway 对话（NL→SQL→执行→解读 由本服务编排）
//   - Nl2Sql:    调用 nl2sql 服务生成 SQL
//   - ExecuteSql: 调用 sql-gateway 执行 SQL
//
// 任一下游不可用时返回明确错误，不静默降级（保证链路可观测）。
type DownstreamProxy struct {
	cfg *config.Config
	hc  *http.Client
}

// NewDownstreamProxy 创建下游代理。
func NewDownstreamProxy(cfg *config.Config) *DownstreamProxy {
	return &DownstreamProxy{
		cfg: cfg,
		hc:  &http.Client{Timeout: 35 * time.Second},
	}
}

// LlmChatRequest llm-gateway 对话请求（对齐其 /v1/chat 契约）。
type LlmChatRequest struct {
	Messages []ChatMessageIn `json:"messages"`
	Model    string          `json:"model,omitempty"`
	Stream   bool            `json:"stream"`
}

// ChatMessageIn 对话消息。
type ChatMessageIn struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// ChatResult llm-gateway 对话响应。
type ChatResult struct {
	Reply string `json:"reply"`
}

// LlmChat 调用 llm-gateway 完成一次对话。
func (p *DownstreamProxy) LlmChat(ctx context.Context, messages []ChatMessageIn, model string) (string, error) {
	req := LlmChatRequest{Messages: messages, Model: model, Stream: false}
	body, _ := json.Marshal(req)

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost,
		p.cfg.LlmGatewayURL+"/v1/chat", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := p.hc.Do(httpReq)
	if err != nil {
		return "", fmt.Errorf("llm-gateway 不可达: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("llm-gateway 返回 %d", resp.StatusCode)
	}

	var out ChatResult
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", fmt.Errorf("解析 llm-gateway 响应失败: %w", err)
	}
	return out.Reply, nil
}

// Nl2SqlResult nl2sql 服务响应。
type Nl2SqlResult struct {
	SQL        string   `json:"sql"`
	Dialect    string   `json:"dialect"`
	Tables     []string `json:"tables"`
	Confidence float64  `json:"confidence"`
}

// Nl2Sql 调用 nl2sql 服务。
func (p *DownstreamProxy) Nl2Sql(ctx context.Context, query, dialect string) (*Nl2SqlResult, error) {
	payload := map[string]string{"query": query, "dialect": dialect}
	body, _ := json.Marshal(payload)

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost,
		p.cfg.Nl2SqlURL+"/api/v1/nl2sql/convert", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := p.hc.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("nl2sql 不可达: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("nl2sql 返回 %d", resp.StatusCode)
	}

	var out Nl2SqlResult
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("解析 nl2sql 响应失败: %w", err)
	}
	return &out, nil
}

// ExecuteSqlResult sql-gateway 执行结果。
type ExecuteSqlResult struct {
	Status  string          `json:"status"`
	Columns []string        `json:"columns"`
	Rows    [][]interface{} `json:"rows"`
}

// ExecuteSql 调用 sql-gateway 执行 SQL。
func (p *DownstreamProxy) ExecuteSql(ctx context.Context, sql, dialect, tenantID string) (*ExecuteSqlResult, error) {
	payload := map[string]string{
		"sql":      sql,
		"dialect":  dialect,
		"tenantId": tenantID,
	}
	body, _ := json.Marshal(payload)

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost,
		p.cfg.SqlGatewayURL+"/api/v1/sql/execute", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := p.hc.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("sql-gateway 不可达: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("sql-gateway 返回 %d", resp.StatusCode)
	}

	var out ExecuteSqlResult
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("解析 sql-gateway 响应失败: %w", err)
	}
	return &out, nil
}
