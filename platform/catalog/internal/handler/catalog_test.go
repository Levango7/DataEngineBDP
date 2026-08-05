package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/shuqing/bigdata/catalog/internal/model"
	"github.com/shuqing/bigdata/catalog/internal/store"
)

// mockStore 是 store.Store 接口的 mock 实现，用于 handler 测试。
type mockStore struct {
	databases map[string]*model.Database
	tables    map[string]*model.Table
}

func newMockStore() *mockStore {
	return &mockStore{
		databases: make(map[string]*model.Database),
		tables:    make(map[string]*model.Table),
	}
}

func (m *mockStore) CreateDatabase(db *model.Database) error {
	if _, ok := m.databases[db.ID]; ok {
		return store.ErrAlreadyExists
	}
	for _, existing := range m.databases {
		if existing.Name == db.Name {
			return store.ErrAlreadyExists
		}
	}
	m.databases[db.ID] = db
	return nil
}

func (m *mockStore) GetDatabase(id string) (*model.Database, error) {
	db, ok := m.databases[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	return db, nil
}

func (m *mockStore) ListDatabases() ([]*model.Database, error) {
	var result []*model.Database
	for _, db := range m.databases {
		result = append(result, db)
	}
	return result, nil
}

func (m *mockStore) DeleteDatabase(id string) error {
	if _, ok := m.databases[id]; !ok {
		return store.ErrNotFound
	}
	delete(m.databases, id)
	return nil
}

func (m *mockStore) CreateTable(t *model.Table) error {
	if _, ok := m.tables[t.ID]; ok {
		return store.ErrAlreadyExists
	}
	m.tables[t.ID] = t
	return nil
}

func (m *mockStore) GetTable(id string) (*model.Table, error) {
	t, ok := m.tables[id]
	if !ok {
		return nil, store.ErrNotFound
	}
	return t, nil
}

func (m *mockStore) ListTables(dbName string) ([]*model.Table, error) {
	var result []*model.Table
	for _, t := range m.tables {
		if dbName == "" || t.DatabaseName == dbName {
			result = append(result, t)
		}
	}
	return result, nil
}

func (m *mockStore) UpdateTable(t *model.Table) error {
	if _, ok := m.tables[t.ID]; !ok {
		return store.ErrNotFound
	}
	m.tables[t.ID] = t
	return nil
}

func (m *mockStore) DeleteTable(id string) error {
	if _, ok := m.tables[id]; !ok {
		return store.ErrNotFound
	}
	delete(m.tables, id)
	return nil
}

// setupTestRouterWithMock 创建使用 mock store 的测试路由。
func setupTestRouterWithMock() (*gin.Engine, *mockStore) {
	gin.SetMode(gin.TestMode)
	s := newMockStore()
	h := NewCatalogHandler(s)

	r := gin.New()
	rg := r.Group("/api/v1/catalog")
	h.RegisterRoutes(rg)

	return r, s
}

// ============ Database Handler 测试 ============

// TestListDatabases_Empty 测试空数据库列表返回。
func TestListDatabases_Empty(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/databases", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]interface{}
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)

	// 空 slice 序列化后可能是 nil 或 []interface{}，统一检查
	data := resp["data"]
	if data != nil {
		arr, ok := data.([]interface{})
		assert.True(t, ok)
		assert.Empty(t, arr)
	}
	assert.Equal(t, float64(0), resp["total"])
}

// TestCreateDatabase_Success 测试成功创建数据库。
func TestCreateDatabase_Success(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	body := map[string]string{
		"name":        "test_db",
		"description": "测试数据库",
		"owner":       "admin",
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/databases", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusCreated, w.Code)

	var created model.Database
	err := json.Unmarshal(w.Body.Bytes(), &created)
	require.NoError(t, err)
	assert.NotEmpty(t, created.ID)
	assert.Equal(t, "test_db", created.Name)
	assert.Equal(t, "admin", created.Owner)
	assert.False(t, created.CreatedAt.IsZero())
}

// TestCreateDatabase_MissingName 测试创建数据库时缺少名称。
func TestCreateDatabase_MissingName(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	body := map[string]string{
		"description": "无名称数据库",
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/databases", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)

	var resp map[string]string
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Contains(t, resp["error"], "database name is required")
}

// TestCreateDatabase_Duplicate 测试创建重复名称的数据库。
func TestCreateDatabase_Duplicate(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	// 先通过 store 创建一个数据库
	ms.databases["dup-001"] = &model.Database{ID: "dup-001", Name: "dup_db", Owner: "admin"}

	body := map[string]string{
		"id":   "dup-002",
		"name": "dup_db",
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/databases", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusConflict, w.Code)
}

// TestGetDatabase_Success 测试成功获取数据库。
func TestGetDatabase_Success(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.databases["get-001"] = &model.Database{ID: "get-001", Name: "get_db", Owner: "tester"}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/databases/get-001", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var got model.Database
	err := json.Unmarshal(w.Body.Bytes(), &got)
	require.NoError(t, err)
	assert.Equal(t, "get-001", got.ID)
	assert.Equal(t, "get_db", got.Name)
}

// TestGetDatabase_NotFound 测试获取不存在的数据库。
func TestGetDatabase_NotFound(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/databases/nonexistent", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNotFound, w.Code)
}

// TestDeleteDatabase_Success 测试成功删除数据库。
func TestDeleteDatabase_Success(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.databases["del-001"] = &model.Database{ID: "del-001", Name: "del_db", Owner: "tester"}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodDelete, "/api/v1/catalog/databases/del-001", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNoContent, w.Code)
}

// TestDeleteDatabase_NotFound 测试删除不存在的数据库。
func TestDeleteDatabase_NotFound(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodDelete, "/api/v1/catalog/databases/nonexistent", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNotFound, w.Code)
}

// TestListDatabases_AfterCreate 测试创建后列表包含数据。
func TestListDatabases_AfterCreate(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.databases["list-001"] = &model.Database{ID: "list-001", Name: "db1", Owner: "admin"}
	ms.databases["list-002"] = &model.Database{ID: "list-002", Name: "db2", Owner: "admin"}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/databases", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]interface{}
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, float64(2), resp["total"])
}

// TestCreateDatabase_InvalidJSON 测试无效 JSON 请求体。
func TestCreateDatabase_InvalidJSON(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/databases", bytes.NewReader([]byte("invalid json")))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// ============ Table Handler 测试 ============

// TestCreateTable_Success 测试成功创建表。
func TestCreateTable_Success(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	body := map[string]interface{}{
		"databaseName": "test_db",
		"tableName":    "users",
		"description":  "用户表",
		"columns": []map[string]interface{}{
			{"name": "id", "type": "BIGINT", "nullable": false},
			{"name": "name", "type": "VARCHAR(255)", "nullable": true},
		},
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/tables", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusCreated, w.Code)

	var created model.Table
	err := json.Unmarshal(w.Body.Bytes(), &created)
	require.NoError(t, err)
	assert.NotEmpty(t, created.ID)
	assert.Equal(t, "test_db", created.DatabaseName)
	assert.Equal(t, "users", created.TableName)
	assert.Len(t, created.Columns, 2)
}

// TestCreateTable_MissingDatabaseName 测试创建表时缺少数据库名。
func TestCreateTable_MissingDatabaseName(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	body := map[string]interface{}{
		"tableName": "users",
		"columns": []map[string]interface{}{
			{"name": "id", "type": "BIGINT"},
		},
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/tables", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)

	var resp map[string]string
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Contains(t, resp["error"], "databaseName is required")
}

// TestCreateTable_MissingTableName 测试创建表时缺少表名。
func TestCreateTable_MissingTableName(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	body := map[string]interface{}{
		"databaseName": "test_db",
		"columns": []map[string]interface{}{
			{"name": "id", "type": "BIGINT"},
		},
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/tables", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)

	var resp map[string]string
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Contains(t, resp["error"], "tableName is required")
}

// TestCreateTable_EmptyColumns 测试创建表时列为空。
func TestCreateTable_EmptyColumns(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	body := map[string]interface{}{
		"databaseName": "test_db",
		"tableName":    "empty_cols",
		"columns":      []map[string]interface{}{},
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/tables", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)

	var resp map[string]string
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Contains(t, resp["error"], "columns must not be empty")
}

// TestListTables_WithFilter 测试按数据库名过滤表列表。
func TestListTables_WithFilter(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.tables["t1"] = &model.Table{ID: "t1", DatabaseName: "db1", TableName: "table1"}
	ms.tables["t2"] = &model.Table{ID: "t2", DatabaseName: "db2", TableName: "table2"}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/tables?database=db1", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]interface{}
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	data, ok := resp["data"].([]interface{})
	assert.True(t, ok)
	assert.Len(t, data, 1)
}

// TestListTables_All 测试列出所有表（不传 database 参数）。
func TestListTables_All(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.tables["ta1"] = &model.Table{ID: "ta1", DatabaseName: "db1", TableName: "table1"}
	ms.tables["ta2"] = &model.Table{ID: "ta2", DatabaseName: "db2", TableName: "table2"}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/tables", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]interface{}
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, float64(2), resp["total"])
}

// TestGetTable_Success 测试成功获取表。
func TestGetTable_Success(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.tables["gt-001"] = &model.Table{
		ID: "gt-001", DatabaseName: "db1", TableName: "users",
		Columns: []model.Column{{Name: "id", Type: "BIGINT"}, {Name: "name", Type: "VARCHAR(255)"}},
	}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/tables/gt-001", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var got model.Table
	err := json.Unmarshal(w.Body.Bytes(), &got)
	require.NoError(t, err)
	assert.Equal(t, "gt-001", got.ID)
	assert.Equal(t, "users", got.TableName)
}

// TestGetTable_NotFound 测试获取不存在的表。
func TestGetTable_NotFound(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/tables/nonexistent", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNotFound, w.Code)
}

// TestUpdateTable_Success 测试成功更新表。
func TestUpdateTable_Success(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.tables["upd-001"] = &model.Table{
		ID: "upd-001", DatabaseName: "db1", TableName: "users",
		Columns: []model.Column{{Name: "id", Type: "BIGINT"}},
	}

	body := map[string]interface{}{
		"databaseName": "db1",
		"tableName":    "users_updated",
		"columns": []map[string]interface{}{
			{"name": "id", "type": "BIGINT"},
			{"name": "email", "type": "VARCHAR(255)"},
		},
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPut, "/api/v1/catalog/tables/upd-001", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var updated model.Table
	err := json.Unmarshal(w.Body.Bytes(), &updated)
	require.NoError(t, err)
	assert.Equal(t, "upd-001", updated.ID)
	assert.Equal(t, "users_updated", updated.TableName)
}

// TestUpdateTable_NotFound 测试更新不存在的表。
func TestUpdateTable_NotFound(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	body := map[string]interface{}{
		"databaseName": "db1",
		"tableName":    "ghost",
		"columns": []map[string]interface{}{
			{"name": "id", "type": "BIGINT"},
		},
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPut, "/api/v1/catalog/tables/nonexistent", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNotFound, w.Code)
}

// TestDeleteTable_Success 测试成功删除表。
func TestDeleteTable_Success(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.tables["dt-001"] = &model.Table{ID: "dt-001", DatabaseName: "db1", TableName: "to_delete"}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodDelete, "/api/v1/catalog/tables/dt-001", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNoContent, w.Code)
}

// TestDeleteTable_NotFound 测试删除不存在的表。
func TestDeleteTable_NotFound(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodDelete, "/api/v1/catalog/tables/nonexistent", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNotFound, w.Code)
}
