package service

import (
	"context"
	"fmt"
	"strings"

	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/config"
)

// AssistantService AI 助手核心编排。
//
// 链路：对话 → (可选) NL→SQL → (可选) 执行 → 解读。
// 单端点 /chat 聚合完整链路；/nl2sql、/execute 等独立端点供前端直接调用。
type AssistantService struct {
	sessions *SessionStore
	proxy    *DownstreamProxy
	cfg      *config.Config
}

// NewAssistantService 创建助手服务。
func NewAssistantService(s *SessionStore, p *DownstreamProxy, cfg *config.Config) *AssistantService {
	return &AssistantService{sessions: s, proxy: p, cfg: cfg}
}

// CreateSession 新建会话。
func (a *AssistantService) CreateSession(locale string) (*Session, error) {
	return a.sessions.CreateSession(locale)
}

// ListSessions 会话列表。
func (a *AssistantService) ListSessions(limit int) ([]Session, error) {
	return a.sessions.ListSessions(limit)
}

// GetSession 会话详情。
func (a *AssistantService) GetSession(id string) (*Session, []Message, error) {
	return a.sessions.GetSession(id)
}

// DeleteSession 删除会话。
func (a *AssistantService) DeleteSession(id string) error {
	return a.sessions.DeleteSession(id)
}

// ChatRequest 对话请求（服务层）。
type ChatRequest struct {
	SessionID string `json:"sessionId"`
	Message   string `json:"message"`
	Locale    string `json:"locale"`
	// 链路开关（默认全开）
	EnableNl2Sql bool `json:"enableNl2Sql"`
	EnableExec   bool `json:"enableExec"`
}

// ChatResponse 对话响应（聚合链路结果）。
type ChatResponse struct {
	SessionID string `json:"sessionId"`
	Reply     string `json:"reply"`
	SQL       string `json:"sql,omitempty"`
	Executed  bool   `json:"executed"`
}

// Chat 编排一次对话：
//
//	① 持久化用户消息
//	② 尝试识别查询意图 → 调 nl2sql 生成 SQL（可关闭）
//	③ 若生成 SQL 且开启执行 → 调 sql-gateway 执行
//	④ 汇总回复（调 llm-gateway 润色 / 或规则组装）
//	⑤ 持久化助手消息
func (a *AssistantService) Chat(ctx context.Context, req *ChatRequest) (*ChatResponse, error) {
	sessionID := req.SessionID
	if sessionID == "" {
		sess, err := a.sessions.CreateSession(req.Locale)
		if err != nil {
			return nil, err
		}
		sessionID = sess.ID
	}
	// ① 用户消息落库
	if _, err := a.sessions.AddMessage(sessionID, RoleUser, StatusDone, req.Message); err != nil {
		return nil, fmt.Errorf("保存用户消息失败: %w", err)
	}

	resp := &ChatResponse{SessionID: sessionID}

	// ② NL→SQL（默认开）
	if req.EnableNl2Sql {
		if nl2sql, err := a.proxy.Nl2Sql(ctx, req.Message, ""); err == nil && nl2sql != nil && strings.TrimSpace(nl2sql.SQL) != "" {
			resp.SQL = nl2sql.SQL
		}
	}

	// ③ 执行（默认开；仅当生成了 SQL）
	if req.EnableExec && resp.SQL != "" {
		if execResult, err := a.proxy.ExecuteSql(ctx, resp.SQL, "ANSI", ""); err == nil {
			resp.Executed = true
			_ = execResult // 结果用于后续解读（P1 扩展）
		}
	}

	// ④ 汇总回复：优先 llm-gateway，失败回退规则文案
	reply := a.buildReply(req.Message, resp.SQL, resp.Executed)
	if reply == "" {
		if llmReply, err := a.proxy.LlmChat(ctx,
			[]ChatMessageIn{{Role: "user", Content: req.Message}}, ""); err == nil {
			reply = llmReply
		}
	}
	resp.Reply = reply

	// ⑤ 助手消息落库
	if _, err := a.sessions.AddMessage(sessionID, RoleAssistant, StatusDone, reply); err != nil {
		return nil, fmt.Errorf("保存助手消息失败: %w", err)
	}
	return resp, nil
}

// buildReply 组装回复（无 LLM 时也能给出可读结果）。
func (a *AssistantService) buildReply(msg, sql string, executed bool) string {
	var b strings.Builder
	if sql != "" {
		b.WriteString("已生成 SQL：\n```sql\n")
		b.WriteString(sql)
		b.WriteString("\n```")
		if executed {
			b.WriteString("\n\n（查询已执行，见结果区）")
		}
		return b.String()
	}
	// 兜底：无 SQL 时给用户一个可读的默认回复（避免空响应）
	b.WriteString("收到：")
	b.WriteString(msg)
	b.WriteString("\n\n（本次对话未生成 SQL。如需数据查询，请描述具体需求，例如“查询本月订单量”。）")
	return b.String()
}
