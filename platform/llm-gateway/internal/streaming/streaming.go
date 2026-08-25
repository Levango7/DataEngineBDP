package streaming

// Package streaming 实现 SSE 流式响应与异步批处理。
//
// SSE 流式响应：
//   - 遵循 OpenAI Chat Completions 流式协议（data: {chunk}\n\n）
//   - 首 Token 延迟目标 ≤ 1s（通过立即发送 role chunk 实现）
//   - 支持 stream:true 触发，客户端用 EventSource 接收
//   - 结束标记：data: [DONE]\n\n
//
// 异步批处理：
//   - 提交批处理任务返回 job_id
//   - 客户端轮询 GET /v1/batch/jobs/:job_id 查询结果
//   - 支持并发 ≥100（通过 worker pool + 队列实现）
//   - 任务状态：queued / running / succeeded / failed
//
// 设计原则：
//   - SSE 与异步批处理共享同一底层调用链
//   - 任务状态线程安全
//   - worker pool 大小可配置

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/token"
)

// ============ SSE 常量 ============

const (
	// sseContentType SSE 响应 Content-Type。
	sseContentType = "text/event-stream; charset=utf-8"
	// sseCacheControl 禁用缓存，确保实时推送。
	sseCacheControl = "no-cache"
	// sseConnection 保持长连接。
	sseConnection = "keep-alive"
	// sseDoneMarker 流式结束标记。
	sseDoneMarker = "[DONE]"
	// sseFlushInterval SSE flush 间隔（毫秒）。
	sseFlushInterval = 20 * time.Millisecond
)

// ============ SSE 流式响应 ============

// SSEStreamer SSE 流式响应器。
//
// 将多模态对话补全响应按 token 流式推送给客户端。
// 当前实现：调用底层 Provider 获取完整响应后，按字符切片模拟流式；
// 生产环境可对接真正支持流式的 Provider（如 OpenAI stream:true）。
type SSEStreamer struct {
	counter *token.Counter
	logger  *slog.Logger
}

// NewSSEStreamer 构造 SSE 流式响应器。
func NewSSEStreamer(counter *token.Counter) *SSEStreamer {
	return &SSEStreamer{
		counter: counter,
		logger:  slog.Default(),
	}
}

// StreamChat 流式推送多模态对话补全。
//
// 调用 chatFunc 获取完整响应，然后按字符切片模拟流式推送。
// 写入 http.ResponseWriter，遵循 SSE 协议（data: {chunk}\n\n）。
func (s *SSEStreamer) StreamChat(
	c *gin.Context,
	req provider.MultimodalChatRequest,
	chatFunc func(context.Context, provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error),
) error {
	start := time.Now()

	// 1. 设置 SSE 响应头
	c.Writer.Header().Set("Content-Type", sseContentType)
	c.Writer.Header().Set("Cache-Control", sseCacheControl)
	c.Writer.Header().Set("Connection", sseConnection)
	c.Writer.Header().Set("X-Accel-Buffering", "no") // 禁用 nginx 缓冲
	c.Status(http.StatusOK)

	// 2. 立即发送 role chunk（首 Token 延迟 ≤ 1s）
	firstChunk := s.buildChunk("", "assistant", "", 0)
	if err := s.writeChunk(c.Writer, firstChunk); err != nil {
		return fmt.Errorf("write first chunk: %w", err)
	}
	c.Writer.Flush()

	// 3. 调用底层获取完整响应
	resp, err := chatFunc(c.Request.Context(), req)
	if err != nil {
		// 推送错误事件。此处写入失败只能记录日志，不再向上传递二次错误。
		errChunk := s.buildErrorChunk(err.Error())
		if writeErr := s.writeChunk(c.Writer, errChunk); writeErr != nil {
			s.logger.Warn("sse write error chunk failed",
				slog.String("originalError", err.Error()),
				slog.String("writeError", writeErr.Error()),
			)
		}
		c.Writer.Flush()
		return err
	}

	// 4. 按字符切片流式推送
	if len(resp.Choices) == 0 {
		return s.writeDone(c.Writer)
	}

	fullContent := resp.Choices[0].Message.Content
	chunkSize := s.optimalChunkSize(fullContent)
	totalTokens := resp.Usage.TotalTokens
	sentTokens := 0

	for i := 0; i < len(fullContent); i += chunkSize {
		// 检查客户端是否断开
		select {
		case <-c.Request.Context().Done():
			s.logger.Info("sse client disconnected",
				slog.Duration("elapsed", time.Since(start)),
				slog.Int("sentTokens", sentTokens),
			)
			return nil
		default:
		}

		end := i + chunkSize
		if end > len(fullContent) {
			end = len(fullContent)
		}
		partial := fullContent[i:end]
		sentTokens += s.counter.CountText(partial)

		var finishReason string
		if end >= len(fullContent) {
			finishReason = "stop"
		}
		chunk := s.buildChunk(resp.ID, "assistant", partial, sentTokens)
		chunk.Choices[0].FinishReason = finishReason
		if err := s.writeChunk(c.Writer, chunk); err != nil {
			return fmt.Errorf("write chunk: %w", err)
		}
		c.Writer.Flush()
		time.Sleep(sseFlushInterval)
	}

	// 5. 推送 usage chunk（最终用量）
	if totalTokens > 0 {
		usageChunk := s.buildUsageChunk(resp.ID, resp.Usage)
		// 写入失败时仅记录日志：usage chunk 是辅助信息，不应中断已完成的主流式响应。
		if writeErr := s.writeChunk(c.Writer, usageChunk); writeErr != nil {
			s.logger.Warn("sse write usage chunk failed",
				slog.String("model", req.Model),
				slog.String("writeError", writeErr.Error()),
			)
		}
		c.Writer.Flush()
	}

	// 6. 推送 [DONE] 标记
	if err := s.writeDone(c.Writer); err != nil {
		return fmt.Errorf("write done: %w", err)
	}

	s.logger.Info("sse stream completed",
		slog.Duration("latency", time.Since(start)),
		slog.Int("totalTokens", totalTokens),
		slog.String("model", req.Model),
	)
	return nil
}

// optimalChunkSize 根据内容长度选择最优切片大小。
//
// 短内容用小切片（更细粒度流式），长内容用大切片（减少 flush 开销）。
func (s *SSEStreamer) optimalChunkSize(content string) int {
	n := len(content)
	switch {
	case n <= 50:
		return 2
	case n <= 200:
		return 4
	case n <= 1000:
		return 8
	default:
		return 16
	}
}

// writeChunk 写入一个 SSE chunk。
func (s *SSEStreamer) writeChunk(w io.Writer, chunk any) error {
	data, err := json.Marshal(chunk)
	if err != nil {
		return fmt.Errorf("marshal chunk: %w", err)
	}
	if _, err := fmt.Fprintf(w, "data: %s\n\n", data); err != nil {
		return fmt.Errorf("write data: %w", err)
	}
	return nil
}

// writeDone 写入 [DONE] 标记。
func (s *SSEStreamer) writeDone(w io.Writer) error {
	_, err := fmt.Fprintf(w, "data: %s\n\n", sseDoneMarker)
	return err
}

// ============ SSE chunk 结构 ============

// SSEChunk SSE 流式 chunk（OpenAI 兼容）。
type SSEChunk struct {
	ID      string      `json:"id"`
	Object  string      `json:"object"`
	Created int64       `json:"created"`
	Model   string      `json:"model"`
	Choices []SSEChoice `json:"choices"`
	Usage   *SSEUsage   `json:"usage,omitempty"`
}

// SSEChoice SSE chunk 中的 choice。
type SSEChoice struct {
	Index        int            `json:"index"`
	Delta        map[string]any `json:"delta"`
	FinishReason string         `json:"finish_reason,omitempty"`
}

// SSEUsage SSE chunk 中的 usage（仅在最终 chunk 携带）。
type SSEUsage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}

// buildChunk 构造一个 SSE chunk。
func (s *SSEStreamer) buildChunk(id, role, content string, sentTokens int) SSEChunk {
	if id == "" {
		id = "chatcmpl-" + randomHex(12)
	}
	delta := make(map[string]any)
	if role != "" {
		delta["role"] = role
	}
	if content != "" {
		delta["content"] = content
	}
	return SSEChunk{
		ID:      id,
		Object:  "chat.completion.chunk",
		Created: time.Now().Unix(),
		Model:   "",
		Choices: []SSEChoice{
			{
				Index: 0,
				Delta: delta,
			},
		},
	}
}

// buildErrorChunk 构造错误 chunk。
func (s *SSEStreamer) buildErrorChunk(message string) SSEChunk {
	return SSEChunk{
		ID:      "chatcmpl-error",
		Object:  "chat.completion.chunk",
		Created: time.Now().Unix(),
		Choices: []SSEChoice{
			{
				Index:        0,
				Delta:        map[string]any{"error": message},
				FinishReason: "error",
			},
		},
	}
}

// buildUsageChunk 构造 usage chunk（最终用量）。
func (s *SSEStreamer) buildUsageChunk(id string, usage provider.MultimodalUsage) SSEChunk {
	return SSEChunk{
		ID:      id,
		Object:  "chat.completion.chunk",
		Created: time.Now().Unix(),
		Choices: []SSEChoice{
			{
				Index:        0,
				Delta:        map[string]any{},
				FinishReason: "stop",
			},
		},
		Usage: &SSEUsage{
			PromptTokens:     usage.PromptTokens,
			CompletionTokens: usage.CompletionTokens,
			TotalTokens:      usage.TotalTokens,
		},
	}
}

// randomHex 生成 n 字节的十六进制字符串。
func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return fmt.Sprintf("%x", b)
}

// ============ 异步批处理 ============

// BatchJobStatus 任务状态。
type BatchJobStatus string

const (
	StatusQueued    BatchJobStatus = "queued"
	StatusRunning   BatchJobStatus = "running"
	StatusSucceeded BatchJobStatus = "succeeded"
	StatusFailed    BatchJobStatus = "failed"
)

// BatchJob 批处理任务。
type BatchJob struct {
	ID        string                           `json:"id"`
	Status    BatchJobStatus                   `json:"status"`
	Request   provider.MultimodalChatRequest   `json:"request"`
	Response  *provider.MultimodalChatResponse `json:"response,omitempty"`
	Error     string                           `json:"error,omitempty"`
	CreatedAt time.Time                        `json:"createdAt"`
	StartedAt time.Time                        `json:"startedAt,omitempty"`
	EndedAt   time.Time                        `json:"endedAt,omitempty"`
	// Progress 0~100。
	Progress int `json:"progress"`
}

// BatchJobManager 批处理任务管理器。
//
// 维护任务队列与 worker pool，支持并发 ≥100。
type BatchJobManager struct {
	mu     sync.RWMutex
	jobs   map[string]*BatchJob // jobID -> job
	queues chan *BatchJob       // 任务队列
	worker int                  // worker 数
	wg     sync.WaitGroup
	stopCh chan struct{}
	// chatFunc 实际调用函数（由外部注入）。
	chatFunc func(context.Context, provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error)
	// counter Token 计量器。
	counter *token.Counter
	logger  *slog.Logger
	// stats 统计。
	stats batchStats
	// terminalTTL 终态任务保留时长；janitorEvery 回收扫描间隔。
	terminalTTL  time.Duration
	janitorEvery time.Duration
}

type batchStats struct {
	totalSubmitted atomic.Int64
	totalSucceeded atomic.Int64
	totalFailed    atomic.Int64
}

// BatchConfig 批处理配置。
type BatchConfig struct {
	WorkerCount  int // worker 数（默认 100）
	QueueSize    int // 队列容量（默认 1000）
	JobTimeoutMs int // 单任务超时（默认 60s）
	// TerminalTTL 终态任务在内存中保留时长（默认 24h），超时由 janitor 回收，
	// 防止 jobs map 无限增长（泄漏治理）。
	TerminalTTL time.Duration
	// JanitorInterval 回收扫描间隔（默认 1h）。
	JanitorInterval time.Duration
}

// DefaultBatchConfig 默认批处理配置。
func DefaultBatchConfig() BatchConfig {
	return BatchConfig{
		WorkerCount:     100,
		QueueSize:       1000,
		JobTimeoutMs:    60000,
		TerminalTTL:     24 * time.Hour,
		JanitorInterval: time.Hour,
	}
}

// NewBatchJobManager 构造批处理任务管理器。
//
// chatFunc 为实际调用函数（由网关注入）。
func NewBatchJobManager(chatFunc func(context.Context, provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error), counter *token.Counter, cfg BatchConfig) *BatchJobManager {
	if cfg.WorkerCount <= 0 {
		cfg.WorkerCount = 100
	}
	if cfg.QueueSize <= 0 {
		cfg.QueueSize = 1000
	}
	if cfg.JobTimeoutMs <= 0 {
		cfg.JobTimeoutMs = 60000
	}
	if cfg.TerminalTTL <= 0 {
		cfg.TerminalTTL = 24 * time.Hour
	}
	if cfg.JanitorInterval <= 0 {
		cfg.JanitorInterval = time.Hour
	}
	m := &BatchJobManager{
		jobs:         make(map[string]*BatchJob),
		queues:       make(chan *BatchJob, cfg.QueueSize),
		worker:       cfg.WorkerCount,
		stopCh:       make(chan struct{}),
		chatFunc:     chatFunc,
		counter:      counter,
		logger:       slog.Default(),
		terminalTTL:  cfg.TerminalTTL,
		janitorEvery: cfg.JanitorInterval,
	}
	// 启动 worker pool
	for i := 0; i < cfg.WorkerCount; i++ {
		m.wg.Add(1)
		go m.workerLoop(cfg.JobTimeoutMs)
	}
	// 启动终态任务回收 janitor（泄漏治理）
	m.wg.Add(1)
	go m.janitorLoop()
	return m
}

// Submit 提交一个批处理任务，返回 job_id。
func (m *BatchJobManager) Submit(req provider.MultimodalChatRequest) string {
	jobID := "batch-" + randomHex(12)
	job := &BatchJob{
		ID:        jobID,
		Status:    StatusQueued,
		Request:   req,
		CreatedAt: time.Now(),
	}

	m.mu.Lock()
	m.jobs[jobID] = job
	m.mu.Unlock()

	m.stats.totalSubmitted.Add(1)

	// 入队（非阻塞，队列满时返回错误标记）
	select {
	case m.queues <- job:
	default:
		// 队列满，直接标记失败
		m.mu.Lock()
		job.Status = StatusFailed
		job.Error = "queue full"
		job.EndedAt = time.Now()
		m.mu.Unlock()
		m.stats.totalFailed.Add(1)
	}
	return jobID
}

// Get 查询任务状态与结果。
func (m *BatchJobManager) Get(jobID string) (*BatchJob, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	job, ok := m.jobs[jobID]
	if !ok {
		return nil, false
	}
	// 返回拷贝避免外部修改
	cp := *job
	return &cp, true
}

// GetForTenant 在指定租户范围内查询任务。
// 跨租户访问按不存在处理（防资源枚举）。
func (m *BatchJobManager) GetForTenant(tenantID, jobID string) (*BatchJob, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	job, ok := m.jobs[jobID]
	if !ok || job.Request.TenantID != tenantID {
		return nil, false
	}
	cp := *job
	return &cp, true
}

// List 列出所有任务（按创建时间降序）。
func (m *BatchJobManager) List() []*BatchJob {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]*BatchJob, 0, len(m.jobs))
	for _, j := range m.jobs {
		cp := *j
		out = append(out, &cp)
	}
	// 按创建时间降序
	sortJobsByCreatedDesc(out)
	return out
}

// ListForTenant 列出指定租户的任务（按创建时间降序）。
func (m *BatchJobManager) ListForTenant(tenantID string) []*BatchJob {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]*BatchJob, 0)
	for _, j := range m.jobs {
		if j.Request.TenantID == tenantID {
			cp := *j
			out = append(out, &cp)
		}
	}
	sortJobsByCreatedDesc(out)
	return out
}

// janitorLoop 周期回收超过 TTL 的终态任务，防止 jobs map 无限增长。
func (m *BatchJobManager) janitorLoop() {
	defer m.wg.Done()
	ticker := time.NewTicker(m.janitorEvery)
	defer ticker.Stop()
	for {
		select {
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.sweepTerminalJobs(time.Now())
		}
	}
}

// sweepTerminalJobs 删除 EndedAt 超过 terminalTTL 的终态任务。
func (m *BatchJobManager) sweepTerminalJobs(now time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	cutoff := now.Add(-m.terminalTTL)
	var swept int
	for id, j := range m.jobs {
		if isTerminalStatus(j.Status) && !j.EndedAt.IsZero() && j.EndedAt.Before(cutoff) {
			delete(m.jobs, id)
			swept++
		}
	}
	if swept > 0 {
		m.logger.Info("batch job janitor swept terminal jobs", slog.Int("count", swept))
	}
}

// isTerminalStatus 报告任务是否处于终态（不可再变更、可安全回收）。
func isTerminalStatus(s BatchJobStatus) bool {
	return s == StatusSucceeded || s == StatusFailed
}

// Stats 返回批处理统计。
func (m *BatchJobManager) Stats() (submitted, succeeded, failed int64) {
	return m.stats.totalSubmitted.Load(), m.stats.totalSucceeded.Load(), m.stats.totalFailed.Load()
}

// Stop 停止 worker pool（优雅关闭）。
func (m *BatchJobManager) Stop() {
	close(m.stopCh)
	m.wg.Wait()
}

// workerLoop worker 主循环。
func (m *BatchJobManager) workerLoop(timeoutMs int) {
	defer m.wg.Done()
	for {
		select {
		case <-m.stopCh:
			return
		case job := <-m.queues:
			m.executeJob(job, timeoutMs)
		}
	}
}

// executeJob 执行单个批处理任务。
func (m *BatchJobManager) executeJob(job *BatchJob, timeoutMs int) {
	m.mu.Lock()
	job.Status = StatusRunning
	job.StartedAt = time.Now()
	m.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	resp, err := m.chatFunc(ctx, job.Request)

	m.mu.Lock()
	defer m.mu.Unlock()
	job.EndedAt = time.Now()
	if err != nil {
		job.Status = StatusFailed
		job.Error = err.Error()
		m.stats.totalFailed.Add(1)
		m.logger.Warn("batch job failed",
			slog.String("jobId", job.ID),
			slog.String("error", err.Error()),
		)
		return
	}
	job.Status = StatusSucceeded
	job.Response = resp
	job.Progress = 100
	m.stats.totalSucceeded.Add(1)
	m.logger.Info("batch job succeeded",
		slog.String("jobId", job.ID),
		slog.Duration("latency", job.EndedAt.Sub(job.StartedAt)),
	)
}

// ============ 辅助 ============

// sortJobsByCreatedDesc 按创建时间降序排序。
func sortJobsByCreatedDesc(jobs []*BatchJob) {
	// 简单插入排序（任务数通常不大）
	for i := 1; i < len(jobs); i++ {
		for j := i; j > 0 && jobs[j].CreatedAt.After(jobs[j-1].CreatedAt); j-- {
			jobs[j], jobs[j-1] = jobs[j-1], jobs[j]
		}
	}
}

// ============ 错误定义 ============

// ErrJobNotFound 任务不存在。
var ErrJobNotFound = errors.New("job not found")

// ErrQueueFull 队列已满。
var ErrQueueFull = errors.New("queue full")
