package store

import (
	"errors"
	"fmt"
	"sort"
	"strings"

	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
	"github.com/Levango7/DataEngineBDP/catalog/internal/tokenizer"
)

// ErrNotFound 在资源不存在时返回。
// 跨租户访问同样返回 ErrNotFound（对调用方而言资源"不存在"，防枚举）。
var ErrNotFound = errors.New("resource not found")

// ErrAlreadyExists 在资源已存在时返回。
var ErrAlreadyExists = errors.New("resource already exists")

// Store 定义 Catalog 元数据的存储抽象。
//
// 租户隔离约定（security: 多租户强制过滤）：
//   - Create/Update：租户取自实体字段 TenantID（由 handler 从 JWT 强制注入，
//     客户端传入值被覆盖），TenantID 为空返回错误
//   - Get/List/Delete/Search：第一个参数为 tenantID，查询恒定追加 tenant 过滤；
//     跨租户资源一律按不存在处理（ErrNotFound）
type Store interface {
	// Database CRUD
	CreateDatabase(db *model.Database) error
	GetDatabase(tenantID, id string) (*model.Database, error)
	ListDatabases(tenantID string) ([]*model.Database, error)
	DeleteDatabase(tenantID, id string) error

	// Table CRUD
	CreateTable(t *model.Table) error
	GetTable(tenantID, id string) (*model.Table, error)
	ListTables(tenantID, dbName string) ([]*model.Table, error)
	UpdateTable(t *model.Table) error
	DeleteTable(tenantID, id string) error

	// Table 全文检索
	// SearchTables 在指定租户范围内根据查询关键字对表名 + 描述进行中文分词匹配，
	// 返回按相关性分数降序排列的命中结果。
	// query 为空时返回空列表；limit <= 0 时使用默认上限 50。
	SearchTables(tenantID, query string, limit int) ([]*model.SearchResult, error)
}

// GormStore 使用 GORM 实现的 Store 接口。
//
// 持久化到关系型数据库：
//   - 开发环境默认 SQLite（文件 catalog.db，零配置启动，重启不丢数据）
//   - 生产环境可通过替换 *gorm.DB 实例切换到 PostgreSQL
type GormStore struct {
	db *gorm.DB
}

// NewGormStore 创建一个新的 GORM 存储实例。
//
// 调用方需先通过 gorm.Open 初始化 *gorm.DB 并完成 AutoMigrate，
// 再传入本构造器。
func NewGormStore(db *gorm.DB) *GormStore {
	return &GormStore{db: db}
}

// DB 暴露底层 *gorm.DB，便于外部执行 AutoMigrate 等操作。
func (s *GormStore) DB() *gorm.DB {
	return s.db
}

// ============ Database CRUD ============

// checkTenant 校验租户键非空。
func checkTenant(tenantID string) error {
	if tenantID == "" {
		return errors.New("tenantId is required")
	}
	return nil
}

func isUniqueConstraintError(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "unique constraint") ||
		strings.Contains(msg, "duplicate key")
}

// CreateDatabase 创建一个数据库。
// 若同租户下 ID 或 Name 已存在则返回 ErrAlreadyExists。
func (s *GormStore) CreateDatabase(db *model.Database) error {
	if db == nil {
		return errors.New("database is nil")
	}
	if db.ID == "" {
		return errors.New("database id is required")
	}
	if err := checkTenant(db.TenantID); err != nil {
		return err
	}

	// 先检查是否已存在（避免依赖驱动唯一约束报错的差异性）
	var existing model.Database
	result := s.db.First(&existing, "id = ?", db.ID)
	if result.Error == nil {
		return fmt.Errorf("%w: database %s", ErrAlreadyExists, db.ID)
	}
	if !errors.Is(result.Error, gorm.ErrRecordNotFound) {
		return result.Error
	}

	// Name 唯一性按租户收敛：不同租户可各自拥有同名库
	if db.Name != "" {
		var byName model.Database
		r2 := s.db.First(&byName, "tenant_id = ? AND name = ?", db.TenantID, db.Name)
		if r2.Error == nil {
			return fmt.Errorf("%w: database name %s", ErrAlreadyExists, db.Name)
		}
		if !errors.Is(r2.Error, gorm.ErrRecordNotFound) {
			return r2.Error
		}
	}

	if err := s.db.Create(db).Error; err != nil {
		if isUniqueConstraintError(err) {
			return fmt.Errorf("%w: database %s", ErrAlreadyExists, db.ID)
		}
		return err
	}
	return nil
}

// GetDatabase 根据 ID 获取数据库。跨租户访问返回 ErrNotFound。
func (s *GormStore) GetDatabase(tenantID, id string) (*model.Database, error) {
	var db model.Database
	result := s.db.First(&db, "tenant_id = ? AND id = ?", tenantID, id)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return nil, fmt.Errorf("%w: database %s", ErrNotFound, id)
		}
		return nil, result.Error
	}
	return &db, nil
}

// ListDatabases 列出租户下的所有数据库。
func (s *GormStore) ListDatabases(tenantID string) ([]*model.Database, error) {
	var dbs []*model.Database
	if err := s.db.Where("tenant_id = ?", tenantID).Find(&dbs).Error; err != nil {
		return nil, err
	}
	return dbs, nil
}

// DeleteDatabase 删除一个数据库。跨租户删除按 ErrNotFound 处理。
// 注意：当前实现不级联删除该库下的表，调用方需自行处理。
func (s *GormStore) DeleteDatabase(tenantID, id string) error {
	result := s.db.Delete(&model.Database{}, "tenant_id = ? AND id = ?", tenantID, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("%w: database %s", ErrNotFound, id)
	}
	return nil
}

// ============ Table CRUD ============

// CreateTable 创建一张表。
// 若同租户下 ID 已存在则返回 ErrAlreadyExists。
func (s *GormStore) CreateTable(t *model.Table) error {
	if t == nil {
		return errors.New("table is nil")
	}
	if t.ID == "" {
		return errors.New("table id is required")
	}
	if err := checkTenant(t.TenantID); err != nil {
		return err
	}

	// ID 唯一性按租户收敛：不同租户可各自拥有同 ID 的表（与 CreateDatabase 的 Name 收敛策略一致）。
	var existing model.Table
	result := s.db.First(&existing, "tenant_id = ? AND id = ?", t.TenantID, t.ID)
	if result.Error == nil {
		return fmt.Errorf("%w: table %s", ErrAlreadyExists, t.ID)
	}
	if !errors.Is(result.Error, gorm.ErrRecordNotFound) {
		return result.Error
	}

	if err := s.db.Create(t).Error; err != nil {
		if isUniqueConstraintError(err) {
			return fmt.Errorf("%w: table %s", ErrAlreadyExists, t.ID)
		}
		return err
	}
	return nil
}

// GetTable 根据 ID 获取表。跨租户访问返回 ErrNotFound。
func (s *GormStore) GetTable(tenantID, id string) (*model.Table, error) {
	var t model.Table
	result := s.db.First(&t, "tenant_id = ? AND id = ?", tenantID, id)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return nil, fmt.Errorf("%w: table %s", ErrNotFound, id)
		}
		return nil, result.Error
	}
	return &t, nil
}

// ListTables 列出租户内指定数据库下的所有表。
// 当 dbName 为空时，列出租户内全部表。
func (s *GormStore) ListTables(tenantID, dbName string) ([]*model.Table, error) {
	var tables []*model.Table
	var err error
	if dbName == "" {
		err = s.db.Where("tenant_id = ?", tenantID).Find(&tables).Error
	} else {
		err = s.db.Where("tenant_id = ? AND database_name = ?", tenantID, dbName).Find(&tables).Error
	}
	if err != nil {
		return nil, err
	}
	return tables, nil
}

// UpdateTable 更新一张表。
// 不存在或属于其他租户的表均返回 ErrNotFound；实体 TenantID 必须非空。
func (s *GormStore) UpdateTable(t *model.Table) error {
	if t == nil {
		return errors.New("table is nil")
	}
	if t.ID == "" {
		return errors.New("table id is required")
	}
	if err := checkTenant(t.TenantID); err != nil {
		return err
	}

	var existing model.Table
	result := s.db.First(&existing, "tenant_id = ? AND id = ?", t.TenantID, t.ID)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return fmt.Errorf("%w: table %s", ErrNotFound, t.ID)
		}
		return result.Error
	}

	// 使用 Save 整体覆盖（保留 ID 与租户归属）
	t.CreatedAt = existing.CreatedAt
	t.TenantID = existing.TenantID
	if err := s.db.Save(t).Error; err != nil {
		return err
	}
	return nil
}

// DeleteTable 删除一张表。跨租户删除按 ErrNotFound 处理。
func (s *GormStore) DeleteTable(tenantID, id string) error {
	result := s.db.Delete(&model.Table{}, "tenant_id = ? AND id = ?", tenantID, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("%w: table %s", ErrNotFound, id)
	}
	return nil
}

// ============ Table 全文检索 ============

// defaultSearchLimit 是 SearchTables 在 limit <= 0 时使用的默认上限。
const defaultSearchLimit = 50

// SearchTables 在租户范围内对表名 + 描述进行中文分词全文检索。
//
// 实现策略（无 ES 依赖的轻量倒排匹配）：
//  1. 用 tokenizer.Tokenize 对 query 切分为 bigram tokens
//  2. 拉取该租户的全部表（生产环境可按 limit * 倍率 + 过滤下推优化）
//  3. 对每张表，将其 TableName + Description 拼接后切分 tokens，计算与 query tokens 的 Score
//  4. 过滤 score > 0 的命中项，按 score 降序排序，截断至 limit 返回
func (s *GormStore) SearchTables(tenantID, query string, limit int) ([]*model.SearchResult, error) {
	if limit <= 0 {
		limit = defaultSearchLimit
	}
	queryTokens := tokenizer.Tokenize(query)
	if len(queryTokens) == 0 {
		return []*model.SearchResult{}, nil
	}

	var tables []*model.Table
	if err := s.db.Where("tenant_id = ?", tenantID).Find(&tables).Error; err != nil {
		return nil, err
	}

	results := make([]*model.SearchResult, 0, len(tables))
	for _, t := range tables {
		// 文档文本 = 表名 + 空格 + 描述（描述可能为空）
		docText := t.TableName
		if t.Description != "" {
			docText += " " + t.Description
		}
		docTokens := tokenizer.Tokenize(docText)
		score := tokenizer.Score(queryTokens, docTokens)
		if score > 0 {
			results = append(results, &model.SearchResult{Table: t, Score: score})
		}
	}

	// 按分数降序排序；同分按表名升序保证稳定输出。
	sort.Slice(results, func(i, j int) bool {
		if results[i].Score != results[j].Score {
			return results[i].Score > results[j].Score
		}
		return results[i].Table.TableName < results[j].Table.TableName
	})

	if len(results) > limit {
		results = results[:limit]
	}
	return results, nil
}
