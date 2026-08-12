//go:build !nocgo

package store

import (
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
)

// setupGormStore 创建内存 SQLite 测试存储（纯 Go 驱动，无需 CGO）。
func setupGormStore() Store {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		panic("failed to open test database: " + err.Error())
	}
	if err := db.AutoMigrate(&model.Database{}, &model.Table{}); err != nil {
		panic("failed to auto migrate: " + err.Error())
	}
	return NewGormStore(db)
}

// gormFixedTime 返回一个固定的测试时间。
func gormFixedTime() time.Time {
	t, _ := time.Parse(time.RFC3339, "2024-01-01T00:00:00Z")
	return t
}

// ============ GormStore Database CRUD 测试 ============

// TestGormStore_CreateDatabase_Success 测试成功创建数据库。
func TestGormStore_CreateDatabase_Success(t *testing.T) {
	s := setupGormStore()
	db := &model.Database{ID: "gdb-001", Name: "test_db", Owner: "admin", CreatedAt: gormFixedTime()}
	err := s.CreateDatabase(db)
	require.NoError(t, err)
}

// TestGormStore_CreateDatabase_Nil 测试传入 nil 时返回错误。
func TestGormStore_CreateDatabase_Nil(t *testing.T) {
	s := setupGormStore()
	err := s.CreateDatabase(nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "database is nil")
}

// TestGormStore_CreateDatabase_EmptyID 测试空 ID 时返回错误。
func TestGormStore_CreateDatabase_EmptyID(t *testing.T) {
	s := setupGormStore()
	db := &model.Database{Name: "no_id_db", Owner: "admin", CreatedAt: gormFixedTime()}
	err := s.CreateDatabase(db)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "database id is required")
}

// TestGormStore_CreateDatabase_Duplicate 测试创建重复 ID 的数据库。
func TestGormStore_CreateDatabase_Duplicate(t *testing.T) {
	s := setupGormStore()
	db1 := &model.Database{ID: "gdup-001", Name: "dup1", Owner: "admin", CreatedAt: gormFixedTime()}
	require.NoError(t, s.CreateDatabase(db1))

	db2 := &model.Database{ID: "gdup-001", Name: "dup2", Owner: "admin", CreatedAt: gormFixedTime()}
	err := s.CreateDatabase(db2)
	assert.Error(t, err)
	assert.ErrorIs(t, err, ErrAlreadyExists)
}

// TestGormStore_CreateDatabase_DuplicateName 测试创建重复名称的数据库。
func TestGormStore_CreateDatabase_DuplicateName(t *testing.T) {
	s := setupGormStore()
	db1 := &model.Database{ID: "gdn-001", Name: "same_name", Owner: "admin", CreatedAt: gormFixedTime()}
	require.NoError(t, s.CreateDatabase(db1))

	db2 := &model.Database{ID: "gdn-002", Name: "same_name", Owner: "admin", CreatedAt: gormFixedTime()}
	err := s.CreateDatabase(db2)
	assert.Error(t, err)
	assert.ErrorIs(t, err, ErrAlreadyExists)
}

// TestGormStore_GetDatabase_Success 测试成功获取数据库。
func TestGormStore_GetDatabase_Success(t *testing.T) {
	s := setupGormStore()
	db := &model.Database{ID: "gget-001", Name: "get_db", Owner: "tester", CreatedAt: gormFixedTime()}
	require.NoError(t, s.CreateDatabase(db))

	got, err := s.GetDatabase("gget-001")
	require.NoError(t, err)
	assert.Equal(t, "gget-001", got.ID)
	assert.Equal(t, "get_db", got.Name)
	assert.Equal(t, "tester", got.Owner)
}

// TestGormStore_GetDatabase_NotFound 测试获取不存在的数据库。
func TestGormStore_GetDatabase_NotFound(t *testing.T) {
	s := setupGormStore()
	_, err := s.GetDatabase("nonexistent")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestGormStore_ListDatabases_Empty 测试空列表。
func TestGormStore_ListDatabases_Empty(t *testing.T) {
	s := setupGormStore()
	dbs, err := s.ListDatabases()
	require.NoError(t, err)
	assert.Empty(t, dbs)
}

// TestGormStore_ListDatabases_Multiple 测试多个数据库列表。
func TestGormStore_ListDatabases_Multiple(t *testing.T) {
	s := setupGormStore()
	require.NoError(t, s.CreateDatabase(&model.Database{ID: "gl-001", Name: "db1", Owner: "admin", CreatedAt: gormFixedTime()}))
	require.NoError(t, s.CreateDatabase(&model.Database{ID: "gl-002", Name: "db2", Owner: "admin", CreatedAt: gormFixedTime()}))
	require.NoError(t, s.CreateDatabase(&model.Database{ID: "gl-003", Name: "db3", Owner: "admin", CreatedAt: gormFixedTime()}))

	dbs, err := s.ListDatabases()
	require.NoError(t, err)
	assert.Len(t, dbs, 3)
}

// TestGormStore_DeleteDatabase_Success 测试成功删除数据库。
func TestGormStore_DeleteDatabase_Success(t *testing.T) {
	s := setupGormStore()
	require.NoError(t, s.CreateDatabase(&model.Database{ID: "gdel-001", Name: "del_db", Owner: "admin", CreatedAt: gormFixedTime()}))

	err := s.DeleteDatabase("gdel-001")
	require.NoError(t, err)

	_, err = s.GetDatabase("gdel-001")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestGormStore_DeleteDatabase_NotFound 测试删除不存在的数据库。
func TestGormStore_DeleteDatabase_NotFound(t *testing.T) {
	s := setupGormStore()
	err := s.DeleteDatabase("nonexistent")
	assert.ErrorIs(t, err, ErrNotFound)
}

// ============ GormStore Table CRUD 测试 ============

// TestGormStore_CreateTable_Success 测试成功创建表。
func TestGormStore_CreateTable_Success(t *testing.T) {
	s := setupGormStore()
	tbl := &model.Table{
		ID: "gt-001", DatabaseName: "db1", TableName: "users",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}, {Name: "name", Type: "VARCHAR(255)"}},
		CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime(),
	}
	err := s.CreateTable(tbl)
	require.NoError(t, err)
}

// TestGormStore_CreateTable_Nil 测试传入 nil 时返回错误。
func TestGormStore_CreateTable_Nil(t *testing.T) {
	s := setupGormStore()
	err := s.CreateTable(nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "table is nil")
}

// TestGormStore_CreateTable_EmptyID 测试空 ID 时返回错误。
func TestGormStore_CreateTable_EmptyID(t *testing.T) {
	s := setupGormStore()
	tbl := &model.Table{DatabaseName: "db1", TableName: "no_id", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}}
	err := s.CreateTable(tbl)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "table id is required")
}

// TestGormStore_CreateTable_Duplicate 测试创建重复 ID 的表。
func TestGormStore_CreateTable_Duplicate(t *testing.T) {
	s := setupGormStore()
	t1 := &model.Table{ID: "gtdup-001", DatabaseName: "db1", TableName: "dup", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime()}
	require.NoError(t, s.CreateTable(t1))

	t2 := &model.Table{ID: "gtdup-001", DatabaseName: "db1", TableName: "dup2", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime()}
	err := s.CreateTable(t2)
	assert.ErrorIs(t, err, ErrAlreadyExists)
}

// TestGormStore_GetTable_Success 测试成功获取表。
func TestGormStore_GetTable_Success(t *testing.T) {
	s := setupGormStore()
	tbl := &model.Table{
		ID: "ggt-001", DatabaseName: "db1", TableName: "users",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}, {Name: "name", Type: "VARCHAR(255)"}},
		CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime(),
	}
	require.NoError(t, s.CreateTable(tbl))

	got, err := s.GetTable("ggt-001")
	require.NoError(t, err)
	assert.Equal(t, "ggt-001", got.ID)
	assert.Equal(t, "users", got.TableName)
	assert.Len(t, got.Columns, 2)
}

// TestGormStore_GetTable_NotFound 测试获取不存在的表。
func TestGormStore_GetTable_NotFound(t *testing.T) {
	s := setupGormStore()
	_, err := s.GetTable("nonexistent")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestGormStore_ListTables_All 测试列出所有表。
func TestGormStore_ListTables_All(t *testing.T) {
	s := setupGormStore()
	require.NoError(t, s.CreateTable(&model.Table{ID: "gla-001", DatabaseName: "db1", TableName: "t1", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime()}))
	require.NoError(t, s.CreateTable(&model.Table{ID: "gla-002", DatabaseName: "db2", TableName: "t2", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime()}))

	tables, err := s.ListTables("")
	require.NoError(t, err)
	assert.Len(t, tables, 2)
}

// TestGormStore_ListTables_FilterByDB 测试按数据库名过滤表。
func TestGormStore_ListTables_FilterByDB(t *testing.T) {
	s := setupGormStore()
	require.NoError(t, s.CreateTable(&model.Table{ID: "glf-001", DatabaseName: "db1", TableName: "t1", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime()}))
	require.NoError(t, s.CreateTable(&model.Table{ID: "glf-002", DatabaseName: "db2", TableName: "t2", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime()}))

	tables, err := s.ListTables("db1")
	require.NoError(t, err)
	assert.Len(t, tables, 1)
	assert.Equal(t, "db1", tables[0].DatabaseName)
}

// TestGormStore_UpdateTable_Success 测试成功更新表。
func TestGormStore_UpdateTable_Success(t *testing.T) {
	s := setupGormStore()
	tbl := &model.Table{
		ID: "gupd-001", DatabaseName: "db1", TableName: "users",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime(),
	}
	require.NoError(t, s.CreateTable(tbl))

	tbl.TableName = "users_v2"
	tbl.Columns = append(tbl.Columns, model.Column{Name: "email", Type: "VARCHAR(255)"})
	err := s.UpdateTable(tbl)
	require.NoError(t, err)

	got, err := s.GetTable("gupd-001")
	require.NoError(t, err)
	assert.Equal(t, "users_v2", got.TableName)
	assert.Len(t, got.Columns, 2)
}

// TestGormStore_UpdateTable_NotFound 测试更新不存在的表。
func TestGormStore_UpdateTable_NotFound(t *testing.T) {
	s := setupGormStore()
	tbl := &model.Table{ID: "gghost", DatabaseName: "db1", TableName: "ghost", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime()}
	err := s.UpdateTable(tbl)
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestGormStore_UpdateTable_Nil 测试传入 nil 时返回错误。
func TestGormStore_UpdateTable_Nil(t *testing.T) {
	s := setupGormStore()
	err := s.UpdateTable(nil)
	assert.Error(t, err)
}

// TestGormStore_DeleteTable_Success 测试成功删除表。
func TestGormStore_DeleteTable_Success(t *testing.T) {
	s := setupGormStore()
	require.NoError(t, s.CreateTable(&model.Table{ID: "gdt-001", DatabaseName: "db1", TableName: "to_del", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: gormFixedTime(), UpdatedAt: gormFixedTime()}))

	err := s.DeleteTable("gdt-001")
	require.NoError(t, err)

	_, err = s.GetTable("gdt-001")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestGormStore_DeleteTable_NotFound 测试删除不存在的表。
func TestGormStore_DeleteTable_NotFound(t *testing.T) {
	s := setupGormStore()
	err := s.DeleteTable("nonexistent")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestGormStore_NewGormStore 测试构造函数。
func TestGormStore_NewGormStore(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)

	s := NewGormStore(db)
	assert.NotNil(t, s)
	assert.Equal(t, db, s.DB())
}
