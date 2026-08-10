package gateway

import (
	"context"
	"log/slog"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
)

// ============ 安全审计 ============
//
// 记录请求日志、敏感词过滤、安全审计。
// 敏感词命中时返回 ErrSensitiveContent，阻止请求继续。

// AuditRecord 单条审计记录。
type AuditRecord struct {
	Timestamp time.Time     `json:"timestamp"`
	TenantID  string        `json:"tenantId"`
	UserID    string        `json:"userId"`
	Model     string        `json:"model"`
	Operation string        `json:"operation"` // "chat" / "embedding"
	Success   bool          `json:"success"`
	Tokens    int           `json:"tokens"`
	Latency   time.Duration `json:"latency"`
	Error     string        `json:"error,omitempty"`
}

// Auditor 安全审计器。
type Auditor struct {
	mu             sync.RWMutex
	sensitiveWords []string
	logger         *slog.Logger
	records        []AuditRecord // 内存审计日志（生产环境可改为落盘/投递）
	maxRecords     int
}

// NewAuditor 构造审计器。
//
// sensitiveWords 敏感词列表，命中任一即拦截。
// logPath 审计日志路径（当前实现：仅输出到 slog，logPath 保留扩展位）。
func NewAuditor(sensitiveWords []string, logPath string) *Auditor {
	_ = logPath // 预留：生产环境可在此打开审计日志文件
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	return &Auditor{
		sensitiveWords: append([]string{}, sensitiveWords...),
		logger:         logger,
		maxRecords:     1024,
	}
}

// SetSensitiveWords 替换敏感词列表。
func (a *Auditor) SetSensitiveWords(words []string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.sensitiveWords = append([]string{}, words...)
}

// SensitiveWords 返回当前敏感词列表快照。
func (a *Auditor) SensitiveWords() []string {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return append([]string{}, a.sensitiveWords...)
}

// containsSensitive 检查文本是否命中敏感词。
func (a *Auditor) containsSensitive(text string) (string, bool) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	lower := strings.ToLower(text)
	for _, w := range a.sensitiveWords {
		if w == "" {
			continue
		}
		if strings.Contains(lower, strings.ToLower(w)) {
			return w, true
		}
	}
	return "", false
}

// CheckRequest 检查对话请求是否命中敏感词。
func (a *Auditor) CheckRequest(tenantID, userID, model string, messages []provider.Message) error {
	for _, m := range messages {
		if word, hit := a.containsSensitive(m.Content); hit {
			a.logger.Warn("sensitive content blocked",
				slog.String("tenantId", tenantID),
				slog.String("userId", userID),
				slog.String("model", model),
				slog.String("word", word),
			)
			return provider.ErrSensitiveContent
		}
	}
	return nil
}

// CheckEmbedding 检查嵌入请求是否命中敏感词。
func (a *Auditor) CheckEmbedding(tenantID, userID, model string, input []string) error {
	for _, s := range input {
		if word, hit := a.containsSensitive(s); hit {
			a.logger.Warn("sensitive content blocked",
				slog.String("tenantId", tenantID),
				slog.String("userId", userID),
				slog.String("model", model),
				slog.String("word", word),
			)
			return provider.ErrSensitiveContent
		}
	}
	return nil
}

// RecordChat 记录一次对话调用审计。
func (a *Auditor) RecordChat(tenantID, userID, model string, resp *provider.ChatResponse, err error, latency time.Duration) {
	rec := AuditRecord{
		Timestamp: time.Now(),
		TenantID:  tenantID,
		UserID:    userID,
		Model:     model,
		Operation: "chat",
		Success:   err == nil,
		Latency:   latency,
	}
	if resp != nil {
		rec.Tokens = resp.Usage.TotalTokens
	}
	if err != nil {
		rec.Error = err.Error()
	}
	a.append(rec)
}

// RecordEmbedding 记录一次嵌入调用审计。
func (a *Auditor) RecordEmbedding(tenantID, userID, model string, resp *provider.EmbeddingResponse, err error, latency time.Duration) {
	rec := AuditRecord{
		Timestamp: time.Now(),
		TenantID:  tenantID,
		UserID:    userID,
		Model:     model,
		Operation: "embedding",
		Success:   err == nil,
		Latency:   latency,
	}
	if resp != nil {
		rec.Tokens = resp.Usage.TotalTokens
	}
	if err != nil {
		rec.Error = err.Error()
	}
	a.append(rec)
}

// append 追加审计记录，超出上限时丢弃最旧的。
func (a *Auditor) append(rec AuditRecord) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.records = append(a.records, rec)
	if len(a.records) > a.maxRecords {
		// 丢弃超出上限的最旧记录，避免审计日志无限增长。
		// 注：原注释"丢弃最旧的 1/4"与实现不符，此处更正为按实际超出的数量丢弃。
		drop := len(a.records) - a.maxRecords
		a.records = a.records[drop:]
	}
	a.logger.Info("audit",
		slog.String("tenantId", rec.TenantID),
		slog.String("userId", rec.UserID),
		slog.String("model", rec.Model),
		slog.String("op", rec.Operation),
		slog.Bool("success", rec.Success),
		slog.Int("tokens", rec.Tokens),
		slog.Duration("latency", rec.Latency),
	)
}

// Records 返回审计记录快照。
func (a *Auditor) Records() []AuditRecord {
	a.mu.RLock()
	defer a.mu.RUnlock()
	out := make([]AuditRecord, len(a.records))
	copy(out, a.records)
	return out
}

// HealthCheck 审计器自身健康检查（始终健康）。
func (a *Auditor) HealthCheck(_ context.Context) error { return nil }
