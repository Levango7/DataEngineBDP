package store

import (
	"errors"
	"fmt"

	"gorm.io/gorm"

	"github.com/shuqing/bigdata/catalog/internal/model"
)

// ErrNotFound 在资源不存在时返回。
var ErrNotFound = errors.New("resource not found")

// ErrAlreadyExists 在资源已存在时返回。
var ErrAlreadyExists = errors.New("resource already exists")

// Store 定义 Catalog 元数据的存储抽象。
// 当前实现为基于 GORM 的关系型数据库存储（开发环境 SQLite，生产环境可切换 PostgreSQL）。
type Store interface {
	// Database CRUD
	CreateDatabase(db *model.Database) error
	GetDatabase(id string) (*model.Database, error)
	ListDatabases() ([]*model.Database, error)
	DeleteDatabase(id string) error

	// Table CRUD
	CreateTable(t *model.Table) error
	GetTable(id string) (*model.Table, error)
	ListTables(dbName string) ([]*model.Table, error)
	UpdateTable(t *model.Table) error
	DeleteTable(id string) error
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

// CreateDatabase 创建一个数据库。
// 若 ID 已存在则返回 ErrAlreadyExists。
func (s *GormStore) CreateDatabase(db *model.Database) error {
	if db == nil {
		return errors.New("database is nil")
	}
	if db.ID == "" {
		return errors.New("database id is required")
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

	// 检查 Name 唯一
	if db.Name != "" {
		var byName model.Database
		r2 := s.db.First(&byName, "name = ?", db.Name)
		if r2.Error == nil {
			return fmt.Errorf("%w: database name %s", ErrAlreadyExists, db.Name)
		}
		if !errors.Is(r2.Error, gorm.ErrRecordNotFound) {
			return r2.Error
		}
	}

	if err := s.db.Create(db).Error; err != nil {
		return err
	}
	return nil
}

// GetDatabase 根据 ID 获取数据库。
func (s *GormStore) GetDatabase(id string) (*model.Database, error) {
	var db model.Database
	result := s.db.First(&db, "id = ?", id)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return nil, fmt.Errorf("%w: database %s", ErrNotFound, id)
		}
		return nil, result.Error
	}
	return &db, nil
}

// ListDatabases 列出所有数据库。
func (s *GormStore) ListDatabases() ([]*model.Database, error) {
	var dbs []*model.Database
	if err := s.db.Find(&dbs).Error; err != nil {
		return nil, err
	}
	return dbs, nil
}

// DeleteDatabase 删除一个数据库。
// 注意：当前实现不级联删除该库下的表，调用方需自行处理。
func (s *GormStore) DeleteDatabase(id string) error {
	result := s.db.Delete(&model.Database{}, "id = ?", id)
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
// 若 ID 已存在则返回 ErrAlreadyExists。
func (s *GormStore) CreateTable(t *model.Table) error {
	if t == nil {
		return errors.New("table is nil")
	}
	if t.ID == "" {
		return errors.New("table id is required")
	}

	var existing model.Table
	result := s.db.First(&existing, "id = ?", t.ID)
	if result.Error == nil {
		return fmt.Errorf("%w: table %s", ErrAlreadyExists, t.ID)
	}
	if !errors.Is(result.Error, gorm.ErrRecordNotFound) {
		return result.Error
	}

	if err := s.db.Create(t).Error; err != nil {
		return err
	}
	return nil
}

// GetTable 根据 ID 获取表。
func (s *GormStore) GetTable(id string) (*model.Table, error) {
	var t model.Table
	result := s.db.First(&t, "id = ?", id)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return nil, fmt.Errorf("%w: table %s", ErrNotFound, id)
		}
		return nil, result.Error
	}
	return &t, nil
}

// ListTables 列出指定数据库下的所有表。
// 当 dbName 为空时，列出所有表。
func (s *GormStore) ListTables(dbName string) ([]*model.Table, error) {
	var tables []*model.Table
	var err error
	if dbName == "" {
		err = s.db.Find(&tables).Error
	} else {
		err = s.db.Where("database_name = ?", dbName).Find(&tables).Error
	}
	if err != nil {
		return nil, err
	}
	return tables, nil
}

// UpdateTable 更新一张表。
// 不存在的表将返回 ErrNotFound。
func (s *GormStore) UpdateTable(t *model.Table) error {
	if t == nil {
		return errors.New("table is nil")
	}
	if t.ID == "" {
		return errors.New("table id is required")
	}

	var existing model.Table
	result := s.db.First(&existing, "id = ?", t.ID)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return fmt.Errorf("%w: table %s", ErrNotFound, t.ID)
		}
		return result.Error
	}

	// 使用 Save 整体覆盖（保留 ID）
	if err := s.db.Save(t).Error; err != nil {
		return err
	}
	return nil
}

// DeleteTable 删除一张表。
func (s *GormStore) DeleteTable(id string) error {
	result := s.db.Delete(&model.Table{}, "id = ?", id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("%w: table %s", ErrNotFound, id)
	}
	return nil
}

// ============ 兼容性：保留 MemoryStore 类型别名 ============
//
// 为兼容可能存在的旧调用方，保留 MemoryStore 类型与 NewMemoryStore 函数，
// 但内部实现改为基于 GORM。如果调用方未传入 *gorm.DB，将 panic 提示。
// 推荐使用 NewGormStore 显式传入 *gorm.DB。

// MemoryStore 是 GormStore 的兼容别名。
type MemoryStore = GormStore

// NewMemoryStore 已废弃，请使用 NewGormStore。
//
// Deprecated: 使用 NewGormStore(db) 替代。
func NewMemoryStore() *GormStore {
	panic("NewMemoryStore is deprecated, use NewGormStore(db *gorm.DB) instead")
}
