package store

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/shuqing/bigdata/catalog/internal/model"
)

// mockDB 是一个内存 map 实现的 Store，用于纯 Go 测试（无需 CGO）。
type mockDB struct {
	databases map[string]*model.Database
	tables    map[string]*model.Table
}

func newMockDB() *mockDB {
	return &mockDB{
		databases: make(map[string]*model.Database),
		tables:    make(map[string]*model.Table),
	}
}

func (m *mockDB) CreateDatabase(db *model.Database) error {
	if db == nil {
		return ErrNotFound // 模拟 nil 检查
	}
	if db.ID == "" {
		return ErrNotFound
	}
	if _, ok := m.databases[db.ID]; ok {
		return ErrAlreadyExists
	}
	for _, existing := range m.databases {
		if existing.Name == db.Name {
			return ErrAlreadyExists
		}
	}
	m.databases[db.ID] = db
	return nil
}

func (m *mockDB) GetDatabase(id string) (*model.Database, error) {
	db, ok := m.databases[id]
	if !ok {
		return nil, ErrNotFound
	}
	return db, nil
}

func (m *mockDB) ListDatabases() ([]*model.Database, error) {
	var result []*model.Database
	for _, db := range m.databases {
		result = append(result, db)
	}
	return result, nil
}

func (m *mockDB) DeleteDatabase(id string) error {
	if _, ok := m.databases[id]; !ok {
		return ErrNotFound
	}
	delete(m.databases, id)
	return nil
}

func (m *mockDB) CreateTable(t *model.Table) error {
	if t == nil {
		return ErrNotFound
	}
	if t.ID == "" {
		return ErrNotFound
	}
	if _, ok := m.tables[t.ID]; ok {
		return ErrAlreadyExists
	}
	m.tables[t.ID] = t
	return nil
}

func (m *mockDB) GetTable(id string) (*model.Table, error) {
	t, ok := m.tables[id]
	if !ok {
		return nil, ErrNotFound
	}
	return t, nil
}

func (m *mockDB) ListTables(dbName string) ([]*model.Table, error) {
	var result []*model.Table
	for _, t := range m.tables {
		if dbName == "" || t.DatabaseName == dbName {
			result = append(result, t)
		}
	}
	return result, nil
}

func (m *mockDB) UpdateTable(t *model.Table) error {
	if t == nil {
		return ErrNotFound
	}
	if t.ID == "" {
		return ErrNotFound
	}
	if _, ok := m.tables[t.ID]; !ok {
		return ErrNotFound
	}
	m.tables[t.ID] = t
	return nil
}

func (m *mockDB) DeleteTable(id string) error {
	if _, ok := m.tables[id]; !ok {
		return ErrNotFound
	}
	delete(m.tables, id)
	return nil
}

// fixedTime 返回一个固定的测试时间。
func fixedTime() time.Time {
	t, _ := time.Parse(time.RFC3339, "2024-01-01T00:00:00Z")
	return t
}

// ============ Store 接口行为测试（使用 mock） ============

// TestStore_CreateDatabase_Success 测试成功创建数据库。
func TestStore_CreateDatabase_Success(t *testing.T) {
	s := newMockDB()
	db := &model.Database{ID: "db-001", Name: "test_db", Owner: "admin", CreatedAt: fixedTime()}
	err := s.CreateDatabase(db)
	require.NoError(t, err)
}

// TestStore_CreateDatabase_Duplicate 测试创建重复 ID 的数据库。
func TestStore_CreateDatabase_Duplicate(t *testing.T) {
	s := newMockDB()
	db1 := &model.Database{ID: "dup-001", Name: "dup1", Owner: "admin", CreatedAt: fixedTime()}
	require.NoError(t, s.CreateDatabase(db1))

	db2 := &model.Database{ID: "dup-001", Name: "dup2", Owner: "admin", CreatedAt: fixedTime()}
	err := s.CreateDatabase(db2)
	assert.Error(t, err)
	assert.ErrorIs(t, err, ErrAlreadyExists)
}

// TestStore_CreateDatabase_DuplicateName 测试创建重复名称的数据库。
func TestStore_CreateDatabase_DuplicateName(t *testing.T) {
	s := newMockDB()
	db1 := &model.Database{ID: "dn-001", Name: "same_name", Owner: "admin", CreatedAt: fixedTime()}
	require.NoError(t, s.CreateDatabase(db1))

	db2 := &model.Database{ID: "dn-002", Name: "same_name", Owner: "admin", CreatedAt: fixedTime()}
	err := s.CreateDatabase(db2)
	assert.Error(t, err)
	assert.ErrorIs(t, err, ErrAlreadyExists)
}

// TestStore_GetDatabase_Success 测试成功获取数据库。
func TestStore_GetDatabase_Success(t *testing.T) {
	s := newMockDB()
	db := &model.Database{ID: "get-001", Name: "get_db", Owner: "tester", CreatedAt: fixedTime()}
	require.NoError(t, s.CreateDatabase(db))

	got, err := s.GetDatabase("get-001")
	require.NoError(t, err)
	assert.Equal(t, "get-001", got.ID)
	assert.Equal(t, "get_db", got.Name)
	assert.Equal(t, "tester", got.Owner)
}

// TestStore_GetDatabase_NotFound 测试获取不存在的数据库。
func TestStore_GetDatabase_NotFound(t *testing.T) {
	s := newMockDB()
	_, err := s.GetDatabase("nonexistent")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestStore_ListDatabases_Empty 测试空列表。
func TestStore_ListDatabases_Empty(t *testing.T) {
	s := newMockDB()
	dbs, err := s.ListDatabases()
	require.NoError(t, err)
	assert.Empty(t, dbs)
}

// TestStore_ListDatabases_Multiple 测试多个数据库列表。
func TestStore_ListDatabases_Multiple(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateDatabase(&model.Database{ID: "l-001", Name: "db1", Owner: "admin", CreatedAt: fixedTime()}))
	require.NoError(t, s.CreateDatabase(&model.Database{ID: "l-002", Name: "db2", Owner: "admin", CreatedAt: fixedTime()}))
	require.NoError(t, s.CreateDatabase(&model.Database{ID: "l-003", Name: "db3", Owner: "admin", CreatedAt: fixedTime()}))

	dbs, err := s.ListDatabases()
	require.NoError(t, err)
	assert.Len(t, dbs, 3)
}

// TestStore_DeleteDatabase_Success 测试成功删除数据库。
func TestStore_DeleteDatabase_Success(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateDatabase(&model.Database{ID: "del-001", Name: "del_db", Owner: "admin", CreatedAt: fixedTime()}))

	err := s.DeleteDatabase("del-001")
	require.NoError(t, err)

	_, err = s.GetDatabase("del-001")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestStore_DeleteDatabase_NotFound 测试删除不存在的数据库。
func TestStore_DeleteDatabase_NotFound(t *testing.T) {
	s := newMockDB()
	err := s.DeleteDatabase("nonexistent")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestStore_CreateTable_Success 测试成功创建表。
func TestStore_CreateTable_Success(t *testing.T) {
	s := newMockDB()
	tbl := &model.Table{
		ID: "t-001", DatabaseName: "db1", TableName: "users",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}, {Name: "name", Type: "VARCHAR(255)"}},
		CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
	}
	err := s.CreateTable(tbl)
	require.NoError(t, err)
}

// TestStore_CreateTable_Duplicate 测试创建重复 ID 的表。
func TestStore_CreateTable_Duplicate(t *testing.T) {
	s := newMockDB()
	t1 := &model.Table{ID: "tdup-001", DatabaseName: "db1", TableName: "dup", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: fixedTime(), UpdatedAt: fixedTime()}
	require.NoError(t, s.CreateTable(t1))

	t2 := &model.Table{ID: "tdup-001", DatabaseName: "db1", TableName: "dup2", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: fixedTime(), UpdatedAt: fixedTime()}
	err := s.CreateTable(t2)
	assert.ErrorIs(t, err, ErrAlreadyExists)
}

// TestStore_GetTable_Success 测试成功获取表。
func TestStore_GetTable_Success(t *testing.T) {
	s := newMockDB()
	tbl := &model.Table{
		ID: "gt-001", DatabaseName: "db1", TableName: "users",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}, {Name: "name", Type: "VARCHAR(255)"}},
		CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
	}
	require.NoError(t, s.CreateTable(tbl))

	got, err := s.GetTable("gt-001")
	require.NoError(t, err)
	assert.Equal(t, "gt-001", got.ID)
	assert.Equal(t, "users", got.TableName)
	assert.Len(t, got.Columns, 2)
}

// TestStore_GetTable_NotFound 测试获取不存在的表。
func TestStore_GetTable_NotFound(t *testing.T) {
	s := newMockDB()
	_, err := s.GetTable("nonexistent")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestStore_ListTables_All 测试列出所有表。
func TestStore_ListTables_All(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateTable(&model.Table{ID: "la-001", DatabaseName: "db1", TableName: "t1", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: fixedTime(), UpdatedAt: fixedTime()}))
	require.NoError(t, s.CreateTable(&model.Table{ID: "la-002", DatabaseName: "db2", TableName: "t2", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: fixedTime(), UpdatedAt: fixedTime()}))

	tables, err := s.ListTables("")
	require.NoError(t, err)
	assert.Len(t, tables, 2)
}

// TestStore_ListTables_FilterByDB 测试按数据库名过滤表。
func TestStore_ListTables_FilterByDB(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateTable(&model.Table{ID: "lf-001", DatabaseName: "db1", TableName: "t1", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: fixedTime(), UpdatedAt: fixedTime()}))
	require.NoError(t, s.CreateTable(&model.Table{ID: "lf-002", DatabaseName: "db2", TableName: "t2", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: fixedTime(), UpdatedAt: fixedTime()}))

	tables, err := s.ListTables("db1")
	require.NoError(t, err)
	assert.Len(t, tables, 1)
	assert.Equal(t, "db1", tables[0].DatabaseName)
}

// TestStore_UpdateTable_Success 测试成功更新表。
func TestStore_UpdateTable_Success(t *testing.T) {
	s := newMockDB()
	tbl := &model.Table{
		ID: "upd-001", DatabaseName: "db1", TableName: "users",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
	}
	require.NoError(t, s.CreateTable(tbl))

	tbl.TableName = "users_v2"
	tbl.Columns = append(tbl.Columns, model.Column{Name: "email", Type: "VARCHAR(255)"})
	err := s.UpdateTable(tbl)
	require.NoError(t, err)

	got, err := s.GetTable("upd-001")
	require.NoError(t, err)
	assert.Equal(t, "users_v2", got.TableName)
	assert.Len(t, got.Columns, 2)
}

// TestStore_UpdateTable_NotFound 测试更新不存在的表。
func TestStore_UpdateTable_NotFound(t *testing.T) {
	s := newMockDB()
	tbl := &model.Table{ID: "ghost", DatabaseName: "db1", TableName: "ghost", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: fixedTime(), UpdatedAt: fixedTime()}
	err := s.UpdateTable(tbl)
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestStore_DeleteTable_Success 测试成功删除表。
func TestStore_DeleteTable_Success(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateTable(&model.Table{ID: "dt-001", DatabaseName: "db1", TableName: "to_del", Columns: []model.Column{{Name: "id", Type: "BIGINT"}}, CreatedAt: fixedTime(), UpdatedAt: fixedTime()}))

	err := s.DeleteTable("dt-001")
	require.NoError(t, err)

	_, err = s.GetTable("dt-001")
	assert.ErrorIs(t, err, ErrNotFound)
}

// TestStore_DeleteTable_NotFound 测试删除不存在的表。
func TestStore_DeleteTable_NotFound(t *testing.T) {
	s := newMockDB()
	err := s.DeleteTable("nonexistent")
	assert.ErrorIs(t, err, ErrNotFound)
}

// ============ GormStore 单元测试（使用 mock 验证接口一致性） ============

// TestErrNotFound 测试 ErrNotFound 错误变量。
func TestErrNotFound(t *testing.T) {
	assert.Error(t, ErrNotFound)
	assert.Equal(t, "resource not found", ErrNotFound.Error())
}

// TestErrAlreadyExists 测试 ErrAlreadyExists 错误变量。
func TestErrAlreadyExists(t *testing.T) {
	assert.Error(t, ErrAlreadyExists)
	assert.Equal(t, "resource already exists", ErrAlreadyExists.Error())
}

// TestStoreInterface 测试 mockDB 实现了 Store 接口。
func TestStoreInterface(t *testing.T) {
	var _ Store = newMockDB()
}
