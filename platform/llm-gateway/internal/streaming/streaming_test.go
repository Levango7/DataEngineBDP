package streaming

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/shuqing/bigdata/llm-gateway/internal/provider"
	"github.com/shuqing/bigdata/llm-gateway/internal/token"
	"github.com/stretchr/testify/assert"
)

// mockChatFunc 测试用 mock 调用函数。
func mockChatFunc(_ context.Context, req provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error) {
	return &provider.MultimodalChatResponse{
		ID:     "test-id",
		Object: "chat.completion",
		Model:  req.Model,
		Choices: []provider.MultimodalChoice{
			{
				Index:        0,
				Message:      provider.MultimodalMessage{Role: "assistant", Content: "Hello, world!"},
				FinishReason: "stop",
			},
		},
		Usage: provider.MultimodalUsage{
			PromptTokens:     5,
			CompletionTokens: 4,
			TotalTokens:      9,
		},
	}, nil
}

// TestBatchJobSubmitAndGet 测试批处理任务提交与查询。
func TestBatchJobSubmitAndGet(t *testing.T) {
	counter := token.NewCounter()
	mgr := NewBatchJobManager(mockChatFunc, counter, BatchConfig{WorkerCount: 4, QueueSize: 10, JobTimeoutMs: 5000})
	defer mgr.Stop()

	req := provider.MultimodalChatRequest{
		Model:    "gpt-4",
		Messages: []provider.MultimodalMessage{{Role: "user", Content: "hi"}},
	}

	jobID := mgr.Submit(req)
	assert.NotEmpty(t, jobID)

	// 轮询等待任务完成
	deadline := time.Now().Add(3 * time.Second)
	var job *BatchJob
	for time.Now().Before(deadline) {
		j, ok := mgr.Get(jobID)
		if ok && (j.Status == StatusSucceeded || j.Status == StatusFailed) {
			job = j
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	assert.NotNil(t, job)
	assert.Equal(t, StatusSucceeded, job.Status)
	assert.NotNil(t, job.Response)
	assert.Equal(t, "Hello, world!", job.Response.Choices[0].Message.Content)
}

// TestBatchJobNotFound 测试查询不存在的任务。
func TestBatchJobNotFound(t *testing.T) {
	counter := token.NewCounter()
	mgr := NewBatchJobManager(mockChatFunc, counter, DefaultBatchConfig())
	defer mgr.Stop()

	_, ok := mgr.Get("nonexistent")
	assert.False(t, ok)
}

// TestBatchJobConcurrent 测试并发提交（≥100 并发）。
func TestBatchJobConcurrent(t *testing.T) {
	counter := token.NewCounter()
	mgr := NewBatchJobManager(mockChatFunc, counter, BatchConfig{WorkerCount: 100, QueueSize: 200, JobTimeoutMs: 10000})
	defer mgr.Stop()

	const n = 100
	var wg sync.WaitGroup
	jobIDs := make([]string, n)
	wg.Add(n)

	for i := 0; i < n; i++ {
		go func(idx int) {
			defer wg.Done()
			req := provider.MultimodalChatRequest{
				Model:    "gpt-4",
				Messages: []provider.MultimodalMessage{{Role: "user", Content: "test"}},
			}
			jobIDs[idx] = mgr.Submit(req)
		}(i)
	}
	wg.Wait()

	// 等待所有任务完成
	deadline := time.Now().Add(10 * time.Second)
	allDone := false
	for time.Now().Before(deadline) && !allDone {
		allDone = true
		for _, id := range jobIDs {
			j, ok := mgr.Get(id)
			if !ok || (j.Status != StatusSucceeded && j.Status != StatusFailed) {
				allDone = false
				break
			}
		}
		if !allDone {
			time.Sleep(50 * time.Millisecond)
		}
	}

	assert.True(t, allDone, "all jobs should complete within timeout")

	submitted, succeeded, failed := mgr.Stats()
	assert.Equal(t, int64(n), submitted)
	assert.Equal(t, int64(n), succeeded+failed)
	assert.Equal(t, int64(0), failed, "no jobs should fail")
}

// TestBatchJobList 测试列出所有任务。
func TestBatchJobList(t *testing.T) {
	counter := token.NewCounter()
	mgr := NewBatchJobManager(mockChatFunc, counter, BatchConfig{WorkerCount: 2, QueueSize: 10, JobTimeoutMs: 5000})
	defer mgr.Stop()

	for i := 0; i < 5; i++ {
		req := provider.MultimodalChatRequest{
			Model:    "gpt-4",
			Messages: []provider.MultimodalMessage{{Role: "user", Content: "test"}},
		}
		mgr.Submit(req)
	}

	// 等待任务完成
	time.Sleep(500 * time.Millisecond)

	jobs := mgr.List()
	assert.Len(t, jobs, 5)

	// 按创建时间降序
	for i := 1; i < len(jobs); i++ {
		assert.True(t, jobs[i-1].CreatedAt.After(jobs[i].CreatedAt) || jobs[i-1].CreatedAt.Equal(jobs[i].CreatedAt))
	}
}

// TestSSEStreamer 测试 SSE 流式响应器构造。
func TestSSEStreamer(t *testing.T) {
	counter := token.NewCounter()
	streamer := NewSSEStreamer(counter)
	assert.NotNil(t, streamer)
	assert.NotNil(t, streamer.counter)
}

// TestBatchJobError 测试任务失败场景。
func TestBatchJobError(t *testing.T) {
	counter := token.NewCounter()
	// 注入总是失败的 chatFunc
	failFunc := func(_ context.Context, _ provider.MultimodalChatRequest) (*provider.MultimodalChatResponse, error) {
		return nil, provider.ErrUpstreamUnavailable
	}
	mgr := NewBatchJobManager(failFunc, counter, BatchConfig{WorkerCount: 2, QueueSize: 10, JobTimeoutMs: 5000})
	defer mgr.Stop()

	req := provider.MultimodalChatRequest{
		Model:    "gpt-4",
		Messages: []provider.MultimodalMessage{{Role: "user", Content: "hi"}},
	}
	jobID := mgr.Submit(req)

	deadline := time.Now().Add(3 * time.Second)
	var job *BatchJob
	for time.Now().Before(deadline) {
		j, ok := mgr.Get(jobID)
		if ok && j.Status == StatusFailed {
			job = j
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	assert.NotNil(t, job)
	assert.Equal(t, StatusFailed, job.Status)
	assert.NotEmpty(t, job.Error)
}