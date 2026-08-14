package service

import (
	"context"
	"strings"
	"testing"

	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/config"
)

// 用内存 SQLite 验证会话 CRUD。
func TestSessionStore_Crud(t *testing.T) {
	store, err := NewSessionStore("file::memory:?cache=shared")
	if err != nil {
		t.Fatalf("创建存储失败: %v", err)
	}

	sess, err := store.CreateSession("zh")
	if err != nil {
		t.Fatalf("创建会话失败: %v", err)
	}
	if sess.ID == "" {
		t.Fatal("会话 ID 不应为空")
	}

	if _, err := store.AddMessage(sess.ID, RoleUser, StatusDone, "查询今天的订单量"); err != nil {
		t.Fatalf("添加用户消息失败: %v", err)
	}
	if _, err := store.AddMessage(sess.ID, RoleAssistant, StatusDone, "已生成 SQL"); err != nil {
		t.Fatalf("添加助手消息失败: %v", err)
	}

	got, msgs, err := store.GetSession(sess.ID)
	if err != nil {
		t.Fatalf("获取会话失败: %v", err)
	}
	if got.Title != "新会话" {
		t.Errorf("标题不符: %s", got.Title)
	}
	if len(msgs) != 2 {
		t.Errorf("消息数不符: %d, 期望 2", len(msgs))
	}

	list, err := store.ListSessions(10)
	if err != nil || len(list) != 1 {
		t.Errorf("会话列表不符: %v len=%d", err, len(list))
	}

	if err := store.DeleteSession(sess.ID); err != nil {
		t.Fatalf("删除会话失败: %v", err)
	}
	if _, _, err := store.GetSession(sess.ID); err == nil {
		t.Error("删除后仍能获取到会话")
	}
}

// Chat 链路：下游不可达时不应崩溃，返回回退回复。
func TestAssistant_Chat_FallbackWhenDownstreamDown(t *testing.T) {
	store, _ := NewSessionStore("file::memory:?cache=shared")
	cfg := &config.Config{
		Port:          "18110",
		SessionDBPath: "file::memory:?cache=shared",
		LlmGatewayURL: "http://127.0.0.1:1", // 不可达端口
		Nl2SqlURL:     "http://127.0.0.1:1",
		SqlGatewayURL: "http://127.0.0.1:1",
	}
	proxy := NewDownstreamProxy(cfg)
	svc := NewAssistantService(store, proxy, cfg)

	resp, err := svc.Chat(context.Background(), &ChatRequest{
		Message:      "查询订单",
		EnableNl2Sql: true,
		EnableExec:   true,
	})
	if err != nil {
		t.Fatalf("Chat 不应返回错误(应降级): %v", err)
	}
	if resp.SessionID == "" {
		t.Error("会话 ID 为空")
	}
	if !strings.Contains(resp.Reply, "查询订单") && resp.Reply == "" {
		t.Error("回复不应为空")
	}
}

// Chat 链路：关闭 NL2SQL 时直接返回（不调下游）。
func TestAssistant_Chat_DisableChain(t *testing.T) {
	store, _ := NewSessionStore("file::memory:?cache=shared")
	cfg := &config.Config{Port: "18110", SessionDBPath: "file::memory:?cache=shared"}
	proxy := NewDownstreamProxy(cfg)
	svc := NewAssistantService(store, proxy, cfg)

	resp, err := svc.Chat(context.Background(), &ChatRequest{
		Message:      "你好",
		EnableNl2Sql: false,
		EnableExec:   false,
	})
	if err != nil {
		t.Fatalf("Chat 不应返回错误: %v", err)
	}
	_ = resp
	// 校验会话与消息已落库
	sess, msgs, err := store.GetSession(resp.SessionID)
	if err != nil {
		t.Fatalf("获取会话失败: %v", err)
	}
	if sess.ID == "" || len(msgs) < 1 {
		t.Error("会话或消息未落库")
	}
}
