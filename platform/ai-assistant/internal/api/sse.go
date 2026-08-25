package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/service"

	"github.com/gin-gonic/gin"
)

// sseEvent SSE 事件块（对齐前端 ChatStreamChunk）。
type sseEvent map[string]interface{}

// writeSSE 写一个 SSE 事件（data: JSON + 空行分隔）。
func writeSSE(c *gin.Context, ev sseEvent) error {
	payload, err := json.Marshal(ev)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(c.Writer, "data: %s\n\n", payload)
	c.Writer.Flush()
	return err
}

// chatStream POST /chat/stream
//
// SSE 事件序：message(开始) → [sql] → [execution] → message(回复) → final(完整响应)。
// 前端解析器按 \n\n 分隔 + data: JSON 读取，final 事件携带完整 ChatResponse。
func (h *AssistantHandler) chatStream(c *gin.Context) {
	var req service.ChatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误: " + err.Error()})
		return
	}
	// SSE 流式端点同样强制租户：校验/回填后再进入链路
	if tenantID, ok := resolveTenant(c, req.TenantID); ok {
		req.TenantID = tenantID
	} else {
		return
	}

	// SSE 响应头
	c.Header("Content-Type", "text/event-stream")
	c.Header("Cache-Control", "no-cache")
	c.Header("Connection", "keep-alive")
	c.Header("X-Accel-Buffering", "no")

	// 事件 1：开始
	if err := writeSSE(c, sseEvent{"type": "message", "message": map[string]interface{}{
		"delta": "正在分析查询…",
	}}); err != nil {
		return
	}
	time.Sleep(50 * time.Millisecond)

	// 完整链路：复用 Chat 编排，但按事件分发
	// 简化：先跑完整 Chat 得到结果，再按序发事件（生产可改为逐步流式）
	result, err := h.svc.Chat(c.Request.Context(), &req)
	if err != nil {
		_ = writeSSE(c, sseEvent{"type": "error", "error": err.Error()})
		return
	}

	// 事件 2：SQL（如有）
	if result.SQL != "" {
		if err := writeSSE(c, sseEvent{"type": "sql", "sql": map[string]interface{}{
			"sql":        result.SQL,
			"dialect":    "ANSI",
			"tables":     []string{},
			"confidence": 0.9,
		}}); err != nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}

	// 事件 3：执行（如有）
	if result.Executed {
		if err := writeSSE(c, sseEvent{"type": "execution", "execution": map[string]interface{}{
			"status":  "SUCCESS",
			"columns": []string{},
			"rows":    []interface{}{},
		}}); err != nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}

	// 事件 4：回复增量
	if err := writeSSE(c, sseEvent{"type": "message", "message": map[string]interface{}{
		"delta": result.Reply,
	}}); err != nil {
		return
	}

	// 事件 5：final（完整响应）
	finalResp := map[string]interface{}{
		"sessionId": result.SessionID,
		"message": map[string]interface{}{
			"role":    "assistant",
			"content": result.Reply,
		},
	}
	if result.SQL != "" {
		finalResp["sql"] = map[string]interface{}{"sql": result.SQL, "dialect": "ANSI"}
	}
	if result.Executed {
		finalResp["execution"] = map[string]interface{}{"status": "SUCCESS"}
	}
	if err := writeSSE(c, sseEvent{"type": "final", "final": finalResp}); err != nil {
		return
	}
}
