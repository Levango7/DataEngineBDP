
package store

// GORM 存储实现。
//
// 封装 gorm.DB，提供 PropagationPolicy 的 CRUD 操作。
// 多租户隔离：所有查询按 tenantID 过滤。

import (
	"errors"

	"gorm.io/gorm"

	"github.com/shuqing/bigdata/karmada-api/internal/model"
)

// Store 存储接口。
type Store interface {
	// CreatePropagationPolicy 创建传播策略。
	CreatePropagationPolicy(pp *model.PropagationPolicy) error
	// GetPropagationPolicy 按名获取传播策略。
	GetPropagationPolicy(tenantID, namespace, name string) (*model.PropagationPolicy, error)
	// ListPropagationPolicies 列出传播策略。
	ListPropagationPolicies(tenantID, namespace string, limit, offset int) ([]model.PropagationPolicy, int64, error)
	// UpdatePropagationPolicy 更新传播策略。
	UpdatePropagationPolicy(pp *model.PropagationPolicy) error
	// DeletePropagationPolicy 删除传播策略。
	DeletePropagationPolicy(tenantID, namespace, name string) error
}

// gormStore GORM 存储实现。
type gormStore struct {
	db *gorm.DB
}

// NewGormStore 创建 GORM 存储实例。
func NewGormStore(db *gorm.DB) Store {
	return &gormStore{db: db}
}

// CreatePropagationPolicy 创建传播策略。
func (s *gormStore) CreatePropagationPolicy(pp *model.PropagationPolicy) error {
	return s.db.Create(pp).Error
}

// GetPropagationPolicy 按名获取传播策略（多租户隔离）。
func (s *gormStore) GetPropagationPolicy(tenantID, namespace, name string) (*model.PropagationPolicy, error) {
	var pp model.PropagationPolicy
	err := s.db.Where(
		"tenant_id = ? AND namespace = ? AND name = ?",
		tenantID, namespace, name,
	).First(&pp).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &pp, nil
}

// ListPropagationPolicies 列出传播策略（多租户隔离，分页）。
func (s *gormStore) ListPropagationPolicies(tenantID, namespace string, limit, offset int) ([]model.PropagationPolicy, int64, error) {
	var pps []model.PropagationPolicy
	var total int64

	query := s.db.Where("tenant_id = ?", tenantID)
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	if err := query.Model(&model.PropagationPolicy{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("updated_at DESC").Limit(limit).Offset(offset).Find(&pps).Error; err != nil {
		return nil, 0, err
	}
	return pps, total, nil
}

// UpdatePropagationPolicy 更新传播策略。
func (s *gormStore) UpdatePropagationPolicy(pp *model.PropagationPolicy) error {
	return s.db.Save(pp).Error
}

// DeletePropagationPolicy 删除传播策略（多租户隔离）。
func (s *gormStore) DeletePropagationPolicy(tenantID, namespace, name string) error {
	result := s.db.Where(
		"tenant_id = ? AND namespace = ? AND name = ?",
		tenantID, namespace, name,
	).Delete(&model.PropagationPolicy{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

// ErrNotFound 记录未找到错误。
var ErrNotFound = errors.New("propagation policy not found")