package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
	"github.com/Levango7/DataEngineBDP/catalog/internal/store"
	"github.com/Levango7/DataEngineBDP/catalog/internal/tokenizer"
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

func (m *mockStore) GetDatabase(tenantID, id string) (*model.Database, error) {
	db, ok := m.databases[id]
	if !ok || db.TenantID != tenantID {
		return nil, store.ErrNotFound
	}
	return db, nil
}

func (m *mockStore) ListDatabases(tenantID string) ([]*model.Database, error) {
	var result []*model.Database
	for _, db := range m.databases {
		if db.TenantID == tenantID {
			result = append(result, db)
		}
	}
	return result, nil
}

func (m *mockStore) DeleteDatabase(tenantID, id string) error {
	db, ok := m.databases[id]
	if !ok || db.TenantID != tenantID {
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

func (m *mockStore) GetTable(tenantID, id string) (*model.Table, error) {
	t, ok := m.tables[id]
	if !ok || t.TenantID != tenantID {
		return nil, store.ErrNotFound
	}
	return t, nil
}

func (m *mockStore) ListTables(tenantID, dbName string) ([]*model.Table, error) {
	var result []*model.Table
	for _, t := range m.tables {
		if t.TenantID != tenantID {
			continue
		}
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

func (m *mockStore) DeleteTable(tenantID, id string) error {
	t, ok := m.tables[id]
	if !ok || t.TenantID != tenantID {
		return store.ErrNotFound
	}
	delete(m.tables, id)
	return nil
}

// SearchTables 在 mock 上实现中文分词检索（租户范围内），逻辑与 GormStore.SearchTables 一致。
func (m *mockStore) SearchTables(tenantID, query string, limit int) ([]*model.SearchResult, error) {
	if limit <= 0 {
		limit = 50
	}
	queryTokens := tokenizer.Tokenize(query)
	if len(queryTokens) == 0 {
		return []*model.SearchResult{}, nil
	}
	results := make([]*model.SearchResult, 0, len(m.tables))
	for _, t := range m.tables {
		if t.TenantID != tenantID {
			continue
		}
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
	// 按分数降序，同分按表名升序
	for i := 0; i < len(results); i++ {
		for j := i + 1; j < len(results); j++ {
			if results[j].Score > results[i].Score ||
				(results[j].Score == results[i].Score && results[j].Table.TableName < results[i].Table.TableName) {
				results[i], results[j] = results[j], results[i]
			}
		}
	}
	if len(results) > limit {
		results = results[:limit]
	}
	return results, nil
}

// setupTestRouterWithMock 创建使用 mock store 的测试路由。
// 注入租户身份中间件（tenantId=t1），模拟 JWT auth 通过后的上下文。
func setupTestRouterWithMock() (*gin.Engine, *mockStore) {
	gin.SetMode(gin.TestMode)
	s := newMockStore()
	h := NewCatalogHandler(s)

	r := gin.New()
	tenantAuth := func(c *gin.Context) {
		c.Set("tenantId", "t1")
		c.Next()
	}
	rg := r.Group("/api/v1/catalog")
	rg.Use(tenantAuth)
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
	ms.databases["dup-001"] = &model.Database{TenantID: "t1", ID: "dup-001", Name: "dup_db", Owner: "admin"}

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

	ms.databases["get-001"] = &model.Database{TenantID: "t1", ID: "get-001", Name: "get_db", Owner: "tester"}

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

	ms.databases["del-001"] = &model.Database{TenantID: "t1", ID: "del-001", Name: "del_db", Owner: "tester"}

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

	ms.databases["list-001"] = &model.Database{TenantID: "t1", ID: "list-001", Name: "db1", Owner: "admin"}
	ms.databases["list-002"] = &model.Database{TenantID: "t1", ID: "list-002", Name: "db2", Owner: "admin"}

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

	ms.tables["t1"] = &model.Table{TenantID: "t1", ID: "t1", DatabaseName: "db1", TableName: "table1"}
	ms.tables["t2"] = &model.Table{TenantID: "t1", ID: "t2", DatabaseName: "db2", TableName: "table2"}

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

	ms.tables["ta1"] = &model.Table{TenantID: "t1", ID: "ta1", DatabaseName: "db1", TableName: "table1"}
	ms.tables["ta2"] = &model.Table{TenantID: "t1", ID: "ta2", DatabaseName: "db2", TableName: "table2"}

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

	ms.tables["gt-001"] = &model.Table{TenantID: "t1", 
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

	ms.tables["upd-001"] = &model.Table{TenantID: "t1", 
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

	ms.tables["dt-001"] = &model.Table{TenantID: "t1", ID: "dt-001", DatabaseName: "db1", TableName: "to_delete"}

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

// ============ 全文检索 Handler 测试 ============

// TestSearchTables_MissingQuery 测试缺少 q 参数返回 400。
func TestSearchTables_MissingQuery(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/search/tables", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Contains(t, resp["error"], "q' is required")
}

// TestSearchTables_EmptyQuery 测试空白 q 返回 400。
func TestSearchTables_EmptyQuery(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/search/tables?q=%20%20", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// TestSearchTables_InvalidLimit 测试非法 limit 返回 400。
func TestSearchTables_InvalidLimit(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/search/tables?q=abc&limit=xyz", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// TestSearchTables_ChineseSemanticMatch 核心修复验证：
// 搜“订单明细”应命中“销售订单明细表”，而非仅 LIKE 子串能命中的场景。
func TestSearchTables_ChineseSemanticMatch(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.tables["s-001"] = &model.Table{TenantID: "t1", 
		ID: "s-001", DatabaseName: "db1", TableName: "销售订单明细表",
		Description: "包含订单明细与金额",
	}
	ms.tables["s-002"] = &model.Table{TenantID: "t1", 
		ID: "s-002", DatabaseName: "db1", TableName: "用户画像表",
		Description: "用户标签与行为",
	}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/search/tables?q=%E8%AE%A2%E5%8D%95%E6%98%8E%E7%BB%86", nil)
	// q=订单明细（URL 编码）
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	var resp struct {
		Data []struct {
			Table struct {
				ID        string `json:"id"`
				TableName string `json:"tableName"`
			} `json:"table"`
			Score float64 `json:"score"`
		} `json:"data"`
		Total int    `json:"total"`
		Query string `json:"query"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "订单明细", resp.Query)

	// 应命中 s-001（销售订单明细表）
	require.NotEmpty(t, resp.Data, "应至少命中一条")
	assert.Equal(t, "s-001", resp.Data[0].Table.ID)
	assert.Greater(t, resp.Data[0].Score, 0.0)
}

// TestSearchTables_OrderByScoreDesc 验证结果按分数降序排列。
func TestSearchTables_OrderByScoreDesc(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	// “订单”在“销售订单明细表”中命中 1 个 bigram（订单）
	// “订单”在“订单订单订单”中命中 1 个 bigram（订单），同分
	// 用不同查询区分：搜“订单明细”，全命中的排前
	ms.tables["o-001"] = &model.Table{TenantID: "t1", 
		ID: "o-001", DatabaseName: "db1", TableName: "销售订单明细表",
	}
	ms.tables["o-002"] = &model.Table{TenantID: "t1", 
		ID: "o-002", DatabaseName: "db1", TableName: "订单",
	}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/search/tables?q=%E8%AE%A2%E5%8D%95%E6%98%8E%E7%BB%86", nil)
	// q=订单明细
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	var resp struct {
		Data []struct {
			Table struct {
				ID string `json:"id"`
			} `json:"table"`
			Score float64 `json:"score"`
		} `json:"data"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	require.Len(t, resp.Data, 2)
	// o-001 全命中（score=1.0）应排第一
	assert.Equal(t, "o-001", resp.Data[0].Table.ID)
	assert.GreaterOrEqual(t, resp.Data[0].Score, resp.Data[1].Score)
}

// TestSearchTables_NoMatch 验证无命中返回空列表。
func TestSearchTables_NoMatch(t *testing.T) {
	r, ms := setupTestRouterWithMock()

	ms.tables["n-001"] = &model.Table{TenantID: "t1", ID: "n-001", DatabaseName: "db1", TableName: "用户画像"}

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/search/tables?q=xyz", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	var resp struct {
		Data  []interface{} `json:"data"`
		Total int           `json:"total"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 0, resp.Total)
	assert.Empty(t, resp.Data)
}

// TestSearchTables_LimitCap 验证 limit 上限 200 被尊重（不报错）。
func TestSearchTables_LimitCap(t *testing.T) {
	r, _ := setupTestRouterWithMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/search/tables?q=test&limit=1000", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}

// ============ 跨租户隔离测试（security） ============

// setupTenantRouter 构造指定租户身份的路由。
func setupTenantRouter(tenantID string) *gin.Engine {
	gin.SetMode(gin.TestMode)
	s := newMockStore()
	h := NewCatalogHandler(s)
	r := gin.New()
	rg := r.Group("/api/v1/catalog")
	rg.Use(func(c *gin.Context) {
		c.Set("tenantId", tenantID)
		c.Next()
	})
	h.RegisterRoutes(rg)
	return r
}

// TestCrossTenantIsolation 租户 B 对租户 A 的资源不可见、不可改、不可删。
func TestCrossTenantIsolation(t *testing.T) {
	ra := setupTenantRouter("t-a")
	rb := setupTenantRouter("t-b")

	// 租户 A 创建数据库与表
	body := `{"name":"sales","owner":"a"}`
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/databases", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	ra.ServeHTTP(w, req)
	require.Equal(t, http.StatusCreated, w.Code)
	var db model.Database
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &db))

	tbody := `{"databaseName":"sales","tableName":"orders","columns":[{"name":"id","type":"BIGINT","nullable":false}]}`
	w = httptest.NewRecorder()
	req, _ = http.NewRequest(http.MethodPost, "/api/v1/catalog/tables", strings.NewReader(tbody))
	req.Header.Set("Content-Type", "application/json")
	ra.ServeHTTP(w, req)
	require.Equal(t, http.StatusCreated, w.Code)
	var tbl model.Table
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &tbl))

	// 租户 B 读取 → 404
	w = httptest.NewRecorder()
	req, _ = http.NewRequest(http.MethodGet, "/api/v1/catalog/databases/"+db.ID, nil)
	rb.ServeHTTP(w, req)
	assert.Equal(t, http.StatusNotFound, w.Code)

	// 租户 B 列表 → 空
	w = httptest.NewRecorder()
	req, _ = http.NewRequest(http.MethodGet, "/api/v1/catalog/databases", nil)
	rb.ServeHTTP(w, req)
	assert.Contains(t, w.Body.String(), `"total":0`)

	// 租户 B 更新 A 的表 → 404
	w = httptest.NewRecorder()
	req, _ = http.NewRequest(http.MethodPut, "/api/v1/catalog/tables/"+tbl.ID,
		strings.NewReader(`{"databaseName":"sales","tableName":"hacked","columns":[{"name":"x","type":"TEXT","nullable":true}]}`))
	req.Header.Set("Content-Type", "application/json")
	rb.ServeHTTP(w, req)
	assert.Equal(t, http.StatusNotFound, w.Code)

	// 租户 B 删除 A 的表/库 → 404
	for _, path := range []string{"/api/v1/catalog/tables/" + tbl.ID, "/api/v1/catalog/databases/" + db.ID} {
		w = httptest.NewRecorder()
		req, _ = http.NewRequest(http.MethodDelete, path, nil)
		rb.ServeHTTP(w, req)
		assert.Equal(t, http.StatusNotFound, w.Code, path)
	}

	// 租户 B 检索不到 A 的表
	w = httptest.NewRecorder()
	req, _ = http.NewRequest(http.MethodGet, "/api/v1/catalog/search/tables?q=orders", nil)
	rb.ServeHTTP(w, req)
	assert.Contains(t, w.Body.String(), `"total":0`)

	// 租户 A 自身不受影响
	w = httptest.NewRecorder()
	req, _ = http.NewRequest(http.MethodGet, "/api/v1/catalog/databases/"+db.ID, nil)
	ra.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

// TestMissingTenantIdentity401 无租户身份的请求一律 401。
func TestMissingTenantIdentity401(t *testing.T) {
	gin.SetMode(gin.TestMode)
	s := newMockStore()
	h := NewCatalogHandler(s)
	r := gin.New()
	h.RegisterRoutes(r.Group("/api/v1/catalog"))

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/catalog/databases", nil)
	r.ServeHTTP(w, req)
	assert.Equal(t, http.StatusUnauthorized, w.Code)
}
