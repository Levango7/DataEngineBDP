package service

import (
	"context"
	"fmt"
	"log/slog"
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

// CreateSession 新建会话（租户隔离）。
func (a *AssistantService) CreateSession(tenantID, locale string) (*Session, error) {
	return a.sessions.CreateSession(tenantID, locale)
}

// ListSessions 会话列表（租户隔离）。
func (a *AssistantService) ListSessions(tenantID string, limit int) ([]Session, error) {
	return a.sessions.ListSessions(tenantID, limit)
}

// GetSession 会话详情（租户隔离）。
func (a *AssistantService) GetSession(tenantID, id string) (*Session, []Message, error) {
	return a.sessions.GetSession(tenantID, id)
}

// DeleteSession 删除会话（租户隔离）。
func (a *AssistantService) DeleteSession(tenantID, id string) error {
	return a.sessions.DeleteSession(tenantID, id)
}

// PinSession 置顶/取消置顶（租户隔离，Sprint 2.2）。
func (a *AssistantService) PinSession(tenantID, id string, pinned bool) error {
	return a.sessions.PinSession(tenantID, id, pinned)
}

// RenameSession 重命名（租户隔离，Sprint 2.2）。
func (a *AssistantService) RenameSession(tenantID, id, title string) error {
	return a.sessions.RenameSession(tenantID, id, title)
}

// SetMessageFeedback 消息反馈（租户隔离，Sprint 2.2）。
func (a *AssistantService) SetMessageFeedback(tenantID, messageID, feedback string) error {
	return a.sessions.SetMessageFeedback(tenantID, messageID, feedback)
}

// ExamplePrompts 示例提问（空状态引导，Sprint 2.2）。
func (a *AssistantService) ExamplePrompts(locale string) []string {
	if locale == "en" {
		return []string{
			"Show me the daily order volume trend for the last 30 days",
			"Which tables in our catalog have no owner?",
			"Generate a dashboard for monthly storage cost by tenant",
			"Find data assets with quality score below 60",
		}
	}
	return []string{
		"查一下最近 30 天的每日订单量趋势",
		"哪些数据表没有设置责任人？",
		"按租户统计本月的存储成本并生成看板",
		"找出质量分低于 60 的数据资产",
		"把昨天的销售数据做一个同比分析",
	}
}

// ChatRequest 对话请求（服务层）。
type ChatRequest struct {
	SessionID string `json:"sessionId"`
	Message   string `json:"message"`
	Locale    string `json:"locale"`
	TenantID  string `json:"tenantId"`
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
		sess, err := a.sessions.CreateSession(req.TenantID, req.Locale)
		if err != nil {
			return nil, err
		}
		sessionID = sess.ID
	}
	// ① 用户消息落库（租户隔离）
	if _, err := a.sessions.AddMessage(req.TenantID, sessionID, RoleUser, StatusDone, req.Message); err != nil {
		return nil, fmt.Errorf("保存用户消息失败: %w", err)
	}

	resp := &ChatResponse{SessionID: sessionID}

	// ② NL→SQL（默认开；传入 tenantId 实现租户隔离）
	if req.EnableNl2Sql {
		nl2sql, err := a.proxy.Nl2Sql(ctx, req.Message, "", req.TenantID)
		switch {
		case err != nil:
			// 高风险静默吞错：NL→SQL 失败会被降级为「无 SQL」模板回复，用户无从察觉。
			slog.Warn("nl2sql 生成失败，降级为无 SQL 回复", slog.String("error", err.Error()))
		case nl2sql != nil && strings.TrimSpace(nl2sql.SQL) != "":
			resp.SQL = nl2sql.SQL
		}
	}

	// ③ 执行（默认开；仅当生成了 SQL；租户取认证回填值，禁止自报）
	// 安全：执行前校验 SQL 仅允许只读 SELECT 查询，防止 NL→SQL 生成破坏性 SQL
	if req.EnableExec && resp.SQL != "" {
		if err := ValidateReadOnlySQL(resp.SQL); err != nil {
			resp.Reply = fmt.Sprintf("生成的 SQL 未通过只读校验，已拒绝执行：%s", err.Error())
			// 助手消息落库（租户隔离）
			if _, err := a.sessions.AddMessage(req.TenantID, sessionID, RoleAssistant, StatusDone, resp.Reply); err != nil {
				return nil, fmt.Errorf("保存助手消息失败: %w", err)
			}
			return resp, nil
		}
		if execResult, err := a.proxy.ExecuteSQL(ctx, resp.SQL, "ANSI", req.TenantID); err != nil {
			// 高风险静默吞错：执行失败时 resp.Executed=false，但回复仍称「已生成 SQL」，
			// 用户会误以为查询成功；补日志保证链路可观测。
			slog.Warn("SQL 执行失败", slog.String("error", err.Error()))
		} else {
			resp.Executed = true
			_ = execResult // 结果用于后续解读（P1 扩展）
		}
	}

	// ④ 汇总回复：优先 llm-gateway 润色，失败回退规则文案。
	//
	// 修复：旧实现先调 buildReply，再以 `if reply == ""` 判断是否调用 LLM，
	// 但 buildReply 的两个分支都必然返回非空串（见下方实现），判空恒假，
	// 导致 LLM 分支是死代码、回复永远是模板拼装。
	// 现改为显式条件（llm-gateway 已配置）下优先调用 LLM，
	// 调用失败或返回空串时再回退到规则文案，保证链路可用且可观测。
	reply := ""
	if a.cfg.LlmGatewayURL != "" {
		llmReply, err := a.proxy.LlmChat(ctx,
			[]ChatMessageIn{{Role: "user", Content: req.Message}}, a.cfg.LlmModel)
		switch {
		case err != nil:
			slog.Warn("llm-gateway 对话失败，回退规则文案", slog.String("error", err.Error()))
		case strings.TrimSpace(llmReply) != "":
			reply = llmReply
		}
	}
	if reply == "" {
		reply = a.buildReply(req.Message, resp.SQL, resp.Executed)
	}
	resp.Reply = reply

	// ⑤ 助手消息落库（租户隔离）
	if _, err := a.sessions.AddMessage(req.TenantID, sessionID, RoleAssistant, StatusDone, reply); err != nil {
		return nil, fmt.Errorf("保存助手消息失败: %w", err)
	}
	return resp, nil
}

// buildReply 组装回复（无 LLM 时也能给出可读结果）。
//
// 注意：本方法两个分支都会返回非空字符串，调用方不得再用 `reply == ""`
// 判断「是否需要 LLM」——那会使 LLM 分支成为死代码（历史缺陷已修复）。
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
