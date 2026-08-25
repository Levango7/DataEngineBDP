package api

import (
	"bufio"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// 用内存 SQLite + 关闭下游(不可达端口) 构建测试环境，验证 SSE 事件序。
// 路由结构与 main.go 一致：/health 匿名，业务组挂 AuthMiddleware。
func newTestRouter(t *testing.T) *gin.Engine {
	return buildTestRouter(t, nil)
}

func TestChatStream_sseEventSequence(t *testing.T) {
	router := newTestRouter(t)

	body := `{"message":"查询本月订单量","enableNl2Sql":true,"enableExec":true}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/ai-assistant/chat/stream",
		bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+makeTestToken(t, "tenant-a"))
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("状态码: %d, body: %s", w.Code, w.Body.String())
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/event-stream") {
		t.Errorf("Content-Type 应为 SSE: %s", ct)
	}

	// 解析 SSE 事件：\n\n 分隔，data: JSON
	var types []string
	hasFinal := false
	scanner := bufio.NewScanner(bytes.NewReader(w.Body.Bytes()))
	scanner.Split(bufio.ScanLines)
	var current strings.Builder
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if current.Len() > 0 {
				ev := parseTestEvent(current.String())
				if ev != nil {
					types = append(types, ev["type"].(string))
					if ev["type"] == "final" {
						hasFinal = true
					}
				}
				current.Reset()
			}
			continue
		}
		current.WriteString(line)
		current.WriteString("\n")
	}

	if len(types) == 0 {
		t.Fatalf("未收到任何 SSE 事件: %s", w.Body.String())
	}
	if types[0] != "message" {
		t.Errorf("首事件应为 message: %v", types)
	}
	if !hasFinal {
		t.Errorf("缺少 final 事件: %v", types)
	}
	t.Logf("SSE 事件序: %v", types)
}

func parseTestEvent(raw string) map[string]interface{} {
	for _, line := range strings.Split(raw, "\n") {
		if strings.HasPrefix(line, "data:") {
			jsonStr := strings.TrimSpace(line[5:])
			var m map[string]interface{}
			if json.Unmarshal([]byte(jsonStr), &m) == nil {
				return m
			}
		}
	}
	return nil
}
