package service

import "time"

// ChatRole 消息角色。
type ChatRole string

const (
	RoleUser      ChatRole = "user"
	RoleAssistant ChatRole = "assistant"
	RoleSystem    ChatRole = "system"
)

// MessageStatus 消息状态。
type MessageStatus string

const (
	StatusPending   MessageStatus = "pending"
	StatusStreaming MessageStatus = "streaming"
	StatusDone      MessageStatus = "done"
	StatusError     MessageStatus = "error"
)

// Session 会话（对应前端 ChatSession）。
type Session struct {
	ID        string    `json:"id" gorm:"primaryKey"`
	Title     string    `json:"title"`
	Locale    string    `json:"locale"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// Message 会话消息（对应前端 ChatMessage）。
type Message struct {
	ID        string        `json:"id" gorm:"primaryKey"`
	SessionID string        `json:"sessionId" gorm:"index"`
	Role      ChatRole      `json:"role"`
	Status    MessageStatus `json:"status"`
	Text      string        `json:"text"`
	CreatedAt time.Time     `json:"createdAt"`
}
