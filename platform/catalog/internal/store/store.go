package store

import (
	"errors"
	"fmt"
	"sort"

	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
	"github.com/Levango7/DataEngineBDP/catalog/internal/tokenizer"
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

	// Table 全文检索
	// SearchTables 根据查询关键字对表名 + 描述进行中文分词匹配，
	// 返回按相关性分数降序排列的命中结果。
	// query 为空时返回空列表；limit <= 0 时使用默认上限 50。
	SearchTables(query string, limit int) ([]*model.SearchResult, error)
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

// ============ Table 全文检索 ============

// defaultSearchLimit 是 SearchTables 在 limit <= 0 时使用的默认上限。
const defaultSearchLimit = 50

// SearchTables 对表名 + 描述进行中文分词全文检索。
//
// 实现策略（无 ES 依赖的轻量倒排匹配）：
//  1. 用 tokenizer.Tokenize 对 query 切分为 bigram tokens
//  2. 拉取全部表（生产环境可按 limit * 倍率 + 过滤下推优化）
//  3. 对每张表，将其 TableName + Description 拼接后切分 tokens，计算与 query tokens 的 Score
//  4. 过滤 score > 0 的命中项，按 score 降序排序，截断至 limit 返回
//
// 该实现解决了“搜中文子串命中不准”的问题：
// 例如搜“订单明细”可命中“销售订单明细表”（bigram 交集 [订单 单明 明细]）。
func (s *GormStore) SearchTables(query string, limit int) ([]*model.SearchResult, error) {
	if limit <= 0 {
		limit = defaultSearchLimit
	}
	queryTokens := tokenizer.Tokenize(query)
	if len(queryTokens) == 0 {
		return []*model.SearchResult{}, nil
	}

	var tables []*model.Table
	if err := s.db.Find(&tables).Error; err != nil {
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
