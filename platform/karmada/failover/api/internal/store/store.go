package store

// GORM 存储实现：OverridePolicy + FailoverEvent + ClusterHealthRecord + ReplicaWeightPlan + FailoverPolicy。
//
// 封装 gorm.DB，提供多集群故障迁移相关实体的 CRUD 操作。
// 多租户隔离：所有查询按 tenantID 过滤。

import (
	"errors"
	"fmt"

	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/failover-api/internal/model"
)

// Store 存储接口。
type Store interface {
	// OverridePolicy CRUD
	CreateOverridePolicy(op *model.OverridePolicy) error
	GetOverridePolicy(tenantID, namespace, name string) (*model.OverridePolicy, error)
	ListOverridePolicies(tenantID, namespace string, limit, offset int) ([]model.OverridePolicy, int64, error)
	UpdateOverridePolicy(op *model.OverridePolicy) error
	DeleteOverridePolicy(tenantID, namespace, name string) error

	// FailoverEvent CRUD
	CreateFailoverEvent(e *model.FailoverEvent) error
	GetFailoverEvent(tenantID, eventID string) (*model.FailoverEvent, error)
	ListFailoverEvents(tenantID string, limit, offset int) ([]model.FailoverEvent, int64, error)
	UpdateFailoverEvent(e *model.FailoverEvent) error

	// ClusterHealthRecord CRUD
	CreateClusterHealthRecord(r *model.ClusterHealthRecord) error
	LatestClusterHealth(tenantID string) ([]model.ClusterHealthRecord, error)
	ListClusterHealth(tenantID, clusterName string, limit int) ([]model.ClusterHealthRecord, error)

	// ReplicaWeightPlan CRUD
	CreateReplicaWeightPlan(p *model.ReplicaWeightPlan) error
	GetReplicaWeightPlan(tenantID, policyName string) (*model.ReplicaWeightPlan, error)
	ListReplicaWeightPlans(tenantID string, limit, offset int) ([]model.ReplicaWeightPlan, int64, error)
	UpdateReplicaWeightPlan(p *model.ReplicaWeightPlan) error

	// FailoverPolicy CRUD
	CreateFailoverPolicy(p *model.FailoverPolicy) error
	GetFailoverPolicy(tenantID, namespace, name string) (*model.FailoverPolicy, error)
	ListFailoverPolicies(tenantID string, limit, offset int) ([]model.FailoverPolicy, int64, error)
	UpdateFailoverPolicy(p *model.FailoverPolicy) error
	DeleteFailoverPolicy(tenantID, namespace, name string) error
}

// gormStore GORM 存储实现。
type gormStore struct {
	db *gorm.DB
}

// NewGormStore 创建 GORM 存储实例。
func NewGormStore(db *gorm.DB) Store {
	return &gormStore{db: db}
}

// ErrNotFound 记录未找到错误。
var ErrNotFound = errors.New("record not found")

// ErrAlreadyExists 记录已存在（唯一性冲突）错误。
var ErrAlreadyExists = errors.New("record already exists")

// ---------------------------------------------------------------------------
// OverridePolicy CRUD
// ---------------------------------------------------------------------------

// CreateOverridePolicy 创建覆盖策略。
// 若同租户下同名同命名空间策略已存在则返回 ErrAlreadyExists。
func (s *gormStore) CreateOverridePolicy(op *model.OverridePolicy) error {
	_, err := s.GetOverridePolicy(op.TenantID, op.Namespace, op.Name)
	if err == nil {
		return fmt.Errorf("%w: override policy %s/%s", ErrAlreadyExists, op.Namespace, op.Name)
	}
	if !errors.Is(err, ErrNotFound) {
		return err
	}
	return s.db.Create(op).Error
}

// GetOverridePolicy 按名获取覆盖策略（多租户隔离）。
func (s *gormStore) GetOverridePolicy(tenantID, namespace, name string) (*model.OverridePolicy, error) {
	var op model.OverridePolicy
	err := s.db.Where(
		"tenant_id = ? AND namespace = ? AND name = ?",
		tenantID, namespace, name,
	).First(&op).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &op, nil
}

// ListOverridePolicies 列出覆盖策略（多租户隔离，分页）。
func (s *gormStore) ListOverridePolicies(tenantID, namespace string, limit, offset int) ([]model.OverridePolicy, int64, error) {
	var ops []model.OverridePolicy
	var total int64

	query := s.db.Where("tenant_id = ?", tenantID)
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	if err := query.Model(&model.OverridePolicy{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("updated_at DESC").Limit(limit).Offset(offset).Find(&ops).Error; err != nil {
		return nil, 0, err
	}
	return ops, total, nil
}

// UpdateOverridePolicy 更新覆盖策略。
func (s *gormStore) UpdateOverridePolicy(op *model.OverridePolicy) error {
	return s.db.Save(op).Error
}

// DeleteOverridePolicy 删除覆盖策略（多租户隔离）。
func (s *gormStore) DeleteOverridePolicy(tenantID, namespace, name string) error {
	result := s.db.Where(
		"tenant_id = ? AND namespace = ? AND name = ?",
		tenantID, namespace, name,
	).Delete(&model.OverridePolicy{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

// ---------------------------------------------------------------------------
// FailoverEvent CRUD
// ---------------------------------------------------------------------------

// CreateFailoverEvent 创建故障迁移事件。
func (s *gormStore) CreateFailoverEvent(e *model.FailoverEvent) error {
	return s.db.Create(e).Error
}

// GetFailoverEvent 按事件 ID 获取故障迁移事件。
func (s *gormStore) GetFailoverEvent(tenantID, eventID string) (*model.FailoverEvent, error) {
	var e model.FailoverEvent
	err := s.db.Where(
		"tenant_id = ? AND event_id = ?",
		tenantID, eventID,
	).First(&e).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &e, nil
}

// ListFailoverEvents 列出故障迁移事件（按时间倒序）。
func (s *gormStore) ListFailoverEvents(tenantID string, limit, offset int) ([]model.FailoverEvent, int64, error) {
	var events []model.FailoverEvent
	var total int64

	query := s.db.Where("tenant_id = ?", tenantID)

	if err := query.Model(&model.FailoverEvent{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("started_at DESC").Limit(limit).Offset(offset).Find(&events).Error; err != nil {
		return nil, 0, err
	}
	return events, total, nil
}

// UpdateFailoverEvent 更新故障迁移事件。
func (s *gormStore) UpdateFailoverEvent(e *model.FailoverEvent) error {
	return s.db.Save(e).Error
}

// ---------------------------------------------------------------------------
// ClusterHealthRecord CRUD
// ---------------------------------------------------------------------------

// CreateClusterHealthRecord 创建集群健康检查记录。
func (s *gormStore) CreateClusterHealthRecord(r *model.ClusterHealthRecord) error {
	return s.db.Create(r).Error
}

// LatestClusterHealth 获取每个集群的最新健康记录。
func (s *gormStore) LatestClusterHealth(tenantID string) ([]model.ClusterHealthRecord, error) {
	var records []model.ClusterHealthRecord
	// 子查询：每个集群的最新 checked_at。
	subQuery := s.db.Model(&model.ClusterHealthRecord{}).
		Select("MAX(id)").
		Where("tenant_id = ?", tenantID).
		Group("cluster_name")
	err := s.db.Where("id IN (?)", subQuery).Find(&records).Error
	if err != nil {
		return nil, err
	}
	return records, nil
}

// ListClusterHealth 列出指定集群的健康历史。
func (s *gormStore) ListClusterHealth(tenantID, clusterName string, limit int) ([]model.ClusterHealthRecord, error) {
	var records []model.ClusterHealthRecord
	if limit <= 0 || limit > 1000 {
		limit = 100
	}
	query := s.db.Where("tenant_id = ?", tenantID)
	if clusterName != "" {
		query = query.Where("cluster_name = ?", clusterName)
	}
	err := query.Order("checked_at DESC").Limit(limit).Find(&records).Error
	if err != nil {
		return nil, err
	}
	return records, nil
}

// ---------------------------------------------------------------------------
// ReplicaWeightPlan CRUD
// ---------------------------------------------------------------------------

// CreateReplicaWeightPlan 创建副本权重分配方案。
func (s *gormStore) CreateReplicaWeightPlan(p *model.ReplicaWeightPlan) error {
	return s.db.Create(p).Error
}

// GetReplicaWeightPlan 按策略名获取副本权重分配方案。
func (s *gormStore) GetReplicaWeightPlan(tenantID, policyName string) (*model.ReplicaWeightPlan, error) {
	var p model.ReplicaWeightPlan
	err := s.db.Where(
		"tenant_id = ? AND policy_name = ?",
		tenantID, policyName,
	).First(&p).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &p, nil
}

// ListReplicaWeightPlans 列出副本权重分配方案。
func (s *gormStore) ListReplicaWeightPlans(tenantID string, limit, offset int) ([]model.ReplicaWeightPlan, int64, error) {
	var plans []model.ReplicaWeightPlan
	var total int64

	query := s.db.Where("tenant_id = ?", tenantID)

	if err := query.Model(&model.ReplicaWeightPlan{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("updated_at DESC").Limit(limit).Offset(offset).Find(&plans).Error; err != nil {
		return nil, 0, err
	}
	return plans, total, nil
}

// UpdateReplicaWeightPlan 更新副本权重分配方案。
func (s *gormStore) UpdateReplicaWeightPlan(p *model.ReplicaWeightPlan) error {
	return s.db.Save(p).Error
}

// ---------------------------------------------------------------------------
// FailoverPolicy CRUD
// ---------------------------------------------------------------------------

// CreateFailoverPolicy 创建故障迁移策略。
// 若同租户下同名同命名空间策略已存在则返回 ErrAlreadyExists。
func (s *gormStore) CreateFailoverPolicy(p *model.FailoverPolicy) error {
	_, err := s.GetFailoverPolicy(p.TenantID, p.Namespace, p.Name)
	if err == nil {
		return fmt.Errorf("%w: failover policy %s/%s", ErrAlreadyExists, p.Namespace, p.Name)
	}
	if !errors.Is(err, ErrNotFound) {
		return err
	}
	return s.db.Create(p).Error
}

// GetFailoverPolicy 按名获取故障迁移策略。
func (s *gormStore) GetFailoverPolicy(tenantID, namespace, name string) (*model.FailoverPolicy, error) {
	var p model.FailoverPolicy
	err := s.db.Where(
		"tenant_id = ? AND namespace = ? AND name = ?",
		tenantID, namespace, name,
	).First(&p).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &p, nil
}

// ListFailoverPolicies 列出故障迁移策略。
func (s *gormStore) ListFailoverPolicies(tenantID string, limit, offset int) ([]model.FailoverPolicy, int64, error) {
	var policies []model.FailoverPolicy
	var total int64

	query := s.db.Where("tenant_id = ?", tenantID)

	if err := query.Model(&model.FailoverPolicy{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("updated_at DESC").Limit(limit).Offset(offset).Find(&policies).Error; err != nil {
		return nil, 0, err
	}
	return policies, total, nil
}

// UpdateFailoverPolicy 更新故障迁移策略。
func (s *gormStore) UpdateFailoverPolicy(p *model.FailoverPolicy) error {
	return s.db.Save(p).Error
}

// DeleteFailoverPolicy 删除故障迁移策略。
func (s *gormStore) DeleteFailoverPolicy(tenantID, namespace, name string) error {
	result := s.db.Where(
		"tenant_id = ? AND namespace = ? AND name = ?",
		tenantID, namespace, name,
	).Delete(&model.FailoverPolicy{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}
