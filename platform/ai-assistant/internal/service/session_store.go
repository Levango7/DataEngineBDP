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

// CreateSession 新建会话（租户隔离：绑定 tenantID）。
func (s *SessionStore) CreateSession(tenantID, locale string) (*Session, error) {
	now := time.Now()
	sess := &Session{
		ID:        uuid.NewString(),
		Title:     "新会话",
		Locale:    locale,
		TenantID:  tenantID,
		CreatedAt: now,
		UpdatedAt: now,
	}
	if err := s.db.Create(sess).Error; err != nil {
		return nil, err
	}
	return sess, nil
}

// ListSessions 会话列表（租户隔离：仅返回 tenantID 的会话，按更新时间倒序）。
func (s *SessionStore) ListSessions(tenantID string, limit int) ([]Session, error) {
	if limit <= 0 {
		limit = 50
	}
	var out []Session
	if err := s.db.Where("tenant_id = ?", tenantID).
		Order("updated_at DESC").Limit(limit).Find(&out).Error; err != nil {
		return nil, err
	}
	return out, nil
}

// GetSession 会话详情（含消息，租户隔离：仅允许 tenantID 访问）。
func (s *SessionStore) GetSession(tenantID, id string) (*Session, []Message, error) {
	var sess Session
	if err := s.db.Where("id = ? AND tenant_id = ?", id, tenantID).First(&sess).Error; err != nil {
		return nil, nil, err
	}
	var msgs []Message
	if err := s.db.Where("session_id = ?", id).Order("created_at ASC").Find(&msgs).Error; err != nil {
		return nil, nil, err
	}
	return &sess, msgs, nil
}

// AddMessage 追加消息（租户隔离：校验会话归属 tenantID）。
func (s *SessionStore) AddMessage(tenantID, sessionID string, role ChatRole, status MessageStatus, text string) (*Message, error) {
	// 校验会话归属当前租户
	var count int64
	if err := s.db.Model(&Session{}).Where("id = ? AND tenant_id = ?", sessionID, tenantID).Count(&count).Error; err != nil {
		return nil, err
	}
	if count == 0 {
		return nil, gorm.ErrRecordNotFound
	}
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

// PinSession 置顶/取消置顶会话（租户隔离，Sprint 2.2）。
func (s *SessionStore) PinSession(tenantID, id string, pinned bool) error {
	return s.db.Model(&Session{}).Where("id = ? AND tenant_id = ?", id, tenantID).
		Updates(map[string]interface{}{"pinned": pinned, "updated_at": time.Now()}).Error
}

// RenameSession 重命名会话（租户隔离，Sprint 2.2）。
func (s *SessionStore) RenameSession(tenantID, id, title string) error {
	return s.db.Model(&Session{}).Where("id = ? AND tenant_id = ?", id, tenantID).
		Updates(map[string]interface{}{"title": title, "updated_at": time.Now()}).Error
}

// SetMessageFeedback 设置消息反馈（租户隔离：校验消息所属会话归属 tenantID）。
func (s *SessionStore) SetMessageFeedback(tenantID, messageID, feedback string) error {
	// 校验消息归属当前租户的会话
	var count int64
	if err := s.db.Model(&Message{}).
		Joins("JOIN sessions ON sessions.id = messages.session_id").
		Where("messages.id = ? AND sessions.tenant_id = ?", messageID, tenantID).
		Count(&count).Error; err != nil {
		return err
	}
	if count == 0 {
		return gorm.ErrRecordNotFound
	}
	return s.db.Model(&Message{}).Where("id = ?", messageID).
		Update("feedback", feedback).Error
}

// DeleteSession 删除会话及其消息（租户隔离：仅允许 tenantID 删除自己的会话）。
func (s *SessionStore) DeleteSession(tenantID, id string) error {
	// 校验会话归属当前租户
	var count int64
	if err := s.db.Model(&Session{}).Where("id = ? AND tenant_id = ?", id, tenantID).Count(&count).Error; err != nil {
		return err
	}
	if count == 0 {
		return gorm.ErrRecordNotFound
	}
	_ = s.db.Where("session_id = ?", id).Delete(&Message{}).Error
	return s.db.Delete(&Session{}, "id = ?", id).Error
}
