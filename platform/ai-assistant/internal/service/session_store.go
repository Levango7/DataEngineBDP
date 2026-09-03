package service

import (
	"time"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

// SessionStore 会话持久化（SQLite + GORM）。
type SessionStore struct {
	db *gorm.DB
}

// NewSessionStore 创建会话存储，自动建表。
func NewSessionStore(dbPath string) (*SessionStore, error) {
	db, err := gorm.Open(sqlite.Open(dbPath), &gorm.Config{})
	if err != nil {
		return nil, err
	}
	if err := db.AutoMigrate(&Session{}, &Message{}); err != nil {
		return nil, err
	}
	return &SessionStore{db: db}, nil
}

// CreateSession 新建会话。
func (s *SessionStore) CreateSession(locale string) (*Session, error) {
	now := time.Now()
	sess := &Session{
		ID:        uuid.NewString(),
		Title:     "新会话",
		Locale:    locale,
		CreatedAt: now,
		UpdatedAt: now,
	}
	if err := s.db.Create(sess).Error; err != nil {
		return nil, err
	}
	return sess, nil
}

// ListSessions 会话列表（按更新时间倒序）。
func (s *SessionStore) ListSessions(limit int) ([]Session, error) {
	if limit <= 0 {
		limit = 50
	}
	var out []Session
	if err := s.db.Order("updated_at DESC").Limit(limit).Find(&out).Error; err != nil {
		return nil, err
	}
	return out, nil
}

// GetSession 会话详情（含消息）。
func (s *SessionStore) GetSession(id string) (*Session, []Message, error) {
	var sess Session
	if err := s.db.First(&sess, "id = ?", id).Error; err != nil {
		return nil, nil, err
	}
	var msgs []Message
	if err := s.db.Where("session_id = ?", id).Order("created_at ASC").Find(&msgs).Error; err != nil {
		return nil, nil, err
	}
	return &sess, msgs, nil
}

// AddMessage 追加消息。
func (s *SessionStore) AddMessage(sessionID string, role ChatRole, status MessageStatus, text string) (*Message, error) {
	msg := &Message{
		ID:        uuid.NewString(),
		SessionID: sessionID,
		Role:      role,
		Status:    status,
		Text:      text,
		CreatedAt: time.Now(),
	}
	if err := s.db.Create(msg).Error; err != nil {
		return nil, err
	}
	// 触碰会话更新时间
	_ = s.db.Model(&Session{}).Where("id = ?", sessionID).
		Update("updated_at", time.Now()).Error
	return msg, nil
}

// PinSession 置顶/取消置顶会话（Sprint 2.2）。
func (s *SessionStore) PinSession(id string, pinned bool) error {
	return s.db.Model(&Session{}).Where("id = ?", id).
		Updates(map[string]interface{}{"pinned": pinned, "updated_at": time.Now()}).Error
}

// RenameSession 重命名会话（Sprint 2.2）。
func (s *SessionStore) RenameSession(id, title string) error {
	return s.db.Model(&Session{}).Where("id = ?", id).
		Updates(map[string]interface{}{"title": title, "updated_at": time.Now()}).Error
}

// SetMessageFeedback 设置消息反馈（like/dislike/清除，Sprint 2.2）。
func (s *SessionStore) SetMessageFeedback(messageID, feedback string) error {
	return s.db.Model(&Message{}).Where("id = ?", messageID).
		Update("feedback", feedback).Error
}

// DeleteSession 删除会话及其消息。
func (s *SessionStore) DeleteSession(id string) error {
	_ = s.db.Where("session_id = ?", id).Delete(&Message{}).Error
	return s.db.Delete(&Session{}, "id = ?", id).Error
}
