package store

import (
	"sort"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
	"github.com/Levango7/DataEngineBDP/catalog/internal/tokenizer"
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

// SearchTables 在 mockDB 上实现中文分词检索，逻辑与 GormStore.SearchTables 一致。
func (m *mockDB) SearchTables(query string, limit int) ([]*model.SearchResult, error) {
	if limit <= 0 {
		limit = defaultSearchLimit
	}
	queryTokens := tokenizer.Tokenize(query)
	if len(queryTokens) == 0 {
		return []*model.SearchResult{}, nil
	}
	results := make([]*model.SearchResult, 0, len(m.tables))
	for _, t := range m.tables {
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

// ============ SearchTables 测试（mock） ============

// TestStore_SearchTables_EmptyQuery 测试空查询返回空列表。
func TestStore_SearchTables_EmptyQuery(t *testing.T) {
	s := newMockDB()
	results, err := s.SearchTables("", 10)
	require.NoError(t, err)
	assert.Empty(t, results)
}

// TestStore_SearchTables_NoMatch 测试无命中返回空。
func TestStore_SearchTables_NoMatch(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateTable(&model.Table{
		ID: "sn-001", DatabaseName: "db1", TableName: "用户画像",
		Columns: []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
	}))

	results, err := s.SearchTables("xyz", 10)
	require.NoError(t, err)
	assert.Empty(t, results)
}

// TestStore_SearchTables_ChineseSemanticMatch 核心修复验证：
// 搜“订单明细”应命中“销售订单明细表”。
func TestStore_SearchTables_ChineseSemanticMatch(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateTable(&model.Table{
		ID: "sc-001", DatabaseName: "db1", TableName: "销售订单明细表",
		Description: "包含订单明细与金额",
		Columns:     []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt:   fixedTime(), UpdatedAt: fixedTime(),
	}))
	require.NoError(t, s.CreateTable(&model.Table{
		ID: "sc-002", DatabaseName: "db1", TableName: "用户画像表",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
	}))

	results, err := s.SearchTables("订单明细", 10)
	require.NoError(t, err)
	require.Len(t, results, 1)
	assert.Equal(t, "sc-001", results[0].Table.ID)
	assert.Greater(t, results[0].Score, 0.0)
}

// TestStore_SearchTables_OrderByScoreDesc 验证按分数降序。
func TestStore_SearchTables_OrderByScoreDesc(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateTable(&model.Table{
		ID: "so-001", DatabaseName: "db1", TableName: "销售订单明细表",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
	}))
	require.NoError(t, s.CreateTable(&model.Table{
		ID: "so-002", DatabaseName: "db1", TableName: "订单",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
	}))

	results, err := s.SearchTables("订单明细", 10)
	require.NoError(t, err)
	require.Len(t, results, 2)
	// 全命中的排第一
	assert.Equal(t, "so-001", results[0].Table.ID)
	assert.GreaterOrEqual(t, results[0].Score, results[1].Score)
}

// TestStore_SearchTables_Limit 验证 limit 截断。
func TestStore_SearchTables_Limit(t *testing.T) {
	s := newMockDB()
	for i := 0; i < 5; i++ {
		require.NoError(t, s.CreateTable(&model.Table{
			ID: "sl-" + string(rune('0'+i)), DatabaseName: "db1", TableName: "订单明细",
			Columns:   []model.Column{{Name: "id", Type: "BIGINT"}},
			CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
		}))
	}

	results, err := s.SearchTables("订单明细", 3)
	require.NoError(t, err)
	assert.Len(t, results, 3)
}

// TestStore_SearchTables_DefaultLimit 验证 limit<=0 用默认值。
func TestStore_SearchTables_DefaultLimit(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateTable(&model.Table{
		ID: "sd-001", DatabaseName: "db1", TableName: "订单",
		Columns:   []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt: fixedTime(), UpdatedAt: fixedTime(),
	}))

	// limit=0 应使用默认值，不 panic 且返回结果
	results, err := s.SearchTables("订单", 0)
	require.NoError(t, err)
	assert.Len(t, results, 1)
}

// TestStore_SearchTables_DescriptionMatch 验证描述字段也参与匹配。
func TestStore_SearchTables_DescriptionMatch(t *testing.T) {
	s := newMockDB()
	require.NoError(t, s.CreateTable(&model.Table{
		ID: "sd-002", DatabaseName: "db1", TableName: "t1",
		Description: "订单明细记录",
		Columns:     []model.Column{{Name: "id", Type: "BIGINT"}},
		CreatedAt:   fixedTime(), UpdatedAt: fixedTime(),
	}))

	results, err := s.SearchTables("订单明细", 10)
	require.NoError(t, err)
	require.Len(t, results, 1)
	assert.Equal(t, "sd-002", results[0].Table.ID)
}
