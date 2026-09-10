package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/service"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store/mock"
)

// setupRouterForTenant 创建一个注入指定租户身份中间件的测试路由。
// 多个路由共享同一个 service 实例，以验证跨租户隔离。
func setupRouterForTenant(t *testing.T, tenantID string, svc *service.VectorService) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	v1 := r.Group("/api/v1")
	v1.Use(func(c *gin.Context) { c.Set("tenantId", tenantID); c.Next() })
	h := NewVectorHandler(svc)
	h.RegisterRoutes(v1)
	return r
}

// setupSharedService 创建一个共享的 service 实例，供多租户隔离测试使用。
func setupSharedService(t *testing.T) *service.VectorService {
	t.Helper()
	return service.NewVectorService(mock.NewMockVectorStore())
}

// doRequestFor 发送 JSON 请求到指定路由并返回响应。
func doRequestFor(t *testing.T, r *gin.Engine, method, path string, body interface{}) *httptest.ResponseRecorder {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		require.NoError(t, json.NewEncoder(&buf).Encode(body))
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// ============ 缺失租户身份返回 401 ============

// TestNoTenantIdentity_Returns401 验证无租户身份中间件时所有业务端点返回 401。
func TestNoTenantIdentity_Returns401(t *testing.T) {
	gin.SetMode(gin.TestMode)
	svc := service.NewVectorService(mock.NewMockVectorStore())
	r := gin.New()
	v1 := r.Group("/api/v1")
	// 无租户中间件
	h := NewVectorHandler(svc)
	h.RegisterRoutes(v1)

	t.Run("ListCollections", func(t *testing.T) {
		w := doRequestFor(t, r, http.MethodGet, "/api/v1/vector", nil)
		assert.Equal(t, http.StatusUnauthorized, w.Code)
	})

	t.Run("CreateCollection", func(t *testing.T) {
		w := doRequestFor(t, r, http.MethodPost, "/api/v1/collections", map[string]interface{}{
			"name": "col", "dimension": 4, "metricType": "L2", "indexType": "FLAT",
		})
		assert.Equal(t, http.StatusUnauthorized, w.Code)
	})

	t.Run("Search", func(t *testing.T) {
		w := doRequestFor(t, r, http.MethodPost, "/api/v1/collections/col/search", map[string]interface{}{
			"vector": []float32{1, 2}, "topK": 5,
		})
		assert.Equal(t, http.StatusUnauthorized, w.Code)
	})

	t.Run("GetStats", func(t *testing.T) {
		w := doRequestFor(t, r, http.MethodGet, "/api/v1/collections/col/stats", nil)
		assert.Equal(t, http.StatusUnauthorized, w.Code)
	})
}

// ============ 跨租户 API 隔离 ============

// TestCrossTenantIsolation_ListCollections 验证租户 B 的 ListCollections 看不到租户 A 的集合。
func TestCrossTenantIsolation_ListCollections(t *testing.T) {
	svc := setupSharedService(t)
	rA := setupRouterForTenant(t, "tenantA", svc)
	rB := setupRouterForTenant(t, "tenantB", svc)

	// 租户 A 创建集合
	w := doRequestFor(t, rA, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name": "my_collection", "dimension": 128, "metricType": "L2", "indexType": "HNSW",
	})
	require.Equal(t, http.StatusCreated, w.Code)

	// 租户 B 列出集合——应为空
	w = doRequestFor(t, rB, http.MethodGet, "/api/v1/vector", nil)
	require.Equal(t, http.StatusOK, w.Code)
	var collections []store.Collection
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &collections))
	assert.Empty(t, collections, "租户 B 不应看到租户 A 的集合")

	// 租户 A 列出集合——应有一个
	w = doRequestFor(t, rA, http.MethodGet, "/api/v1/vector", nil)
	require.Equal(t, http.StatusOK, w.Code)
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &collections))
	assert.Len(t, collections, 1)
	assert.Equal(t, "my_collection", collections[0].Name)
}

// TestCrossTenantIsolation_SameCollectionName 验证两个租户可通过各自路由创建同名集合。
func TestCrossTenantIsolation_SameCollectionName(t *testing.T) {
	svc := setupSharedService(t)
	rA := setupRouterForTenant(t, "tenantA", svc)
	rB := setupRouterForTenant(t, "tenantB", svc)

	colBody := map[string]interface{}{
		"name": "shared_name", "dimension": 4, "metricType": "L2", "indexType": "FLAT",
	}

	// 租户 A 创建
	w := doRequestFor(t, rA, http.MethodPost, "/api/v1/collections", colBody)
	require.Equal(t, http.StatusCreated, w.Code, "租户 A 创建集合应成功")

	// 租户 B 创建同名集合——不应冲突
	w = doRequestFor(t, rB, http.MethodPost, "/api/v1/collections", colBody)
	require.Equal(t, http.StatusCreated, w.Code, "租户 B 创建同名集合应成功（命名空间隔离）")
}

// TestCrossTenantIsolation_SearchInvisible 验证租户 B 搜索同名集合看不到租户 A 的向量。
func TestCrossTenantIsolation_SearchInvisible(t *testing.T) {
	svc := setupSharedService(t)
	rA := setupRouterForTenant(t, "tenantA", svc)
	rB := setupRouterForTenant(t, "tenantB", svc)

	// 两个租户各自创建同名集合并插入向量
	colBody := map[string]interface{}{
		"name": "docs", "dimension": 2, "metricType": "L2", "indexType": "FLAT",
	}
	require.Equal(t, http.StatusCreated, doRequestFor(t, rA, http.MethodPost, "/api/v1/collections", colBody).Code)
	require.Equal(t, http.StatusCreated, doRequestFor(t, rB, http.MethodPost, "/api/v1/collections", colBody).Code)

	// 租户 A 插入向量
	w := doRequestFor(t, rA, http.MethodPost, "/api/v1/collections/docs/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{
			{"id": "a1", "vector": []float32{1, 0}},
			{"id": "a2", "vector": []float32{0, 1}},
		},
	})
	require.Equal(t, http.StatusCreated, w.Code)

	// 租户 B 插入向量
	w = doRequestFor(t, rB, http.MethodPost, "/api/v1/collections/docs/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{
			{"id": "b1", "vector": []float32{1, 1}},
		},
	})
	require.Equal(t, http.StatusCreated, w.Code)

	// 租户 B 搜索——只看到自己的 1 条向量
	w = doRequestFor(t, rB, http.MethodPost, "/api/v1/collections/docs/search", map[string]interface{}{
		"vector": []float32{1, 1}, "topK": 10,
	})
	require.Equal(t, http.StatusOK, w.Code)
	var resp struct {
		Results []store.SearchResult `json:"results"`
		Total   int                  `json:"total"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 1, resp.Total, "租户 B 只应看到自己的向量")
	assert.Equal(t, "b1", resp.Results[0].ID)

	// 租户 A 搜索——只看到自己的 2 条向量
	w = doRequestFor(t, rA, http.MethodPost, "/api/v1/collections/docs/search", map[string]interface{}{
		"vector": []float32{1, 0}, "topK": 10,
	})
	require.Equal(t, http.StatusOK, w.Code)
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 2, resp.Total, "租户 A 只应看到自己的向量")
	for _, r := range resp.Results {
		assert.NotEqual(t, "b1", r.ID, "租户 A 不应看到租户 B 的向量")
	}
}

// TestCrossTenantIsolation_DropCollectionNotFound 验证租户 B 删除租户 A 的集合名得到 404。
func TestCrossTenantIsolation_DropCollectionNotFound(t *testing.T) {
	svc := setupSharedService(t)
	rA := setupRouterForTenant(t, "tenantA", svc)
	rB := setupRouterForTenant(t, "tenantB", svc)

	// 租户 A 创建集合
	w := doRequestFor(t, rA, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name": "private", "dimension": 4, "metricType": "L2", "indexType": "FLAT",
	})
	require.Equal(t, http.StatusCreated, w.Code)

	// 租户 B 尝试删除——应得到 404（隔离保护）
	w = doRequestFor(t, rB, http.MethodDelete, "/api/v1/collections/private", nil)
	assert.Equal(t, http.StatusNotFound, w.Code, "租户 B 不应能删除租户 A 的集合")

	// 验证租户 A 的集合仍然存在
	w = doRequestFor(t, rA, http.MethodGet, "/api/v1/collections/private/stats", nil)
	assert.Equal(t, http.StatusOK, w.Code, "租户 A 的集合应仍然存在")
}

// TestCrossTenantIsolation_DeleteVectorsNotFound 验证租户 B 无法删除租户 A 的向量。
func TestCrossTenantIsolation_DeleteVectorsNotFound(t *testing.T) {
	svc := setupSharedService(t)
	rA := setupRouterForTenant(t, "tenantA", svc)
	rB := setupRouterForTenant(t, "tenantB", svc)

	// 租户 A 创建集合并插入向量
	require.Equal(t, http.StatusCreated, doRequestFor(t, rA, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name": "data", "dimension": 2, "metricType": "L2", "indexType": "FLAT",
	}).Code)
	require.Equal(t, http.StatusCreated, doRequestFor(t, rA, http.MethodPost, "/api/v1/collections/data/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{{"id": "v1", "vector": []float32{1, 2}}},
	}).Code)

	// 租户 B 尝试删除向量——应得到 404
	w := doRequestFor(t, rB, http.MethodDelete, "/api/v1/collections/data/vectors", map[string]interface{}{
		"ids": []string{"v1"},
	})
	assert.Equal(t, http.StatusNotFound, w.Code, "租户 B 不应能删除租户 A 的向量")

	// 验证租户 A 的向量仍然存在
	w = doRequestFor(t, rA, http.MethodPost, "/api/v1/collections/data/search", map[string]interface{}{
		"vector": []float32{1, 2}, "topK": 10,
	})
	require.Equal(t, http.StatusOK, w.Code)
	var resp struct {
		Total int `json:"total"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 1, resp.Total, "租户 A 的向量应仍然存在")
}

// TestCrossTenantIsolation_GetStatsNotFound 验证租户 B 无法获取租户 A 的集合统计。
func TestCrossTenantIsolation_GetStatsNotFound(t *testing.T) {
	svc := setupSharedService(t)
	rA := setupRouterForTenant(t, "tenantA", svc)
	rB := setupRouterForTenant(t, "tenantB", svc)

	// 租户 A 创建集合
	require.Equal(t, http.StatusCreated, doRequestFor(t, rA, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name": "stats_col", "dimension": 8, "metricType": "IP", "indexType": "HNSW",
	}).Code)

	// 租户 B 尝试获取统计——应得到 404
	w := doRequestFor(t, rB, http.MethodGet, "/api/v1/collections/stats_col/stats", nil)
	assert.Equal(t, http.StatusNotFound, w.Code, "租户 B 不应能获取租户 A 的集合统计")
}

// TestCrossTenantIsolation_GlobalSearchScopedToTenant 验证全局检索只搜索当前租户的集合。
func TestCrossTenantIsolation_GlobalSearchScopedToTenant(t *testing.T) {
	svc := setupSharedService(t)
	rA := setupRouterForTenant(t, "tenantA", svc)
	rB := setupRouterForTenant(t, "tenantB", svc)

	// 租户 A 创建集合并插入向量
	require.Equal(t, http.StatusCreated, doRequestFor(t, rA, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name": "knowledge", "dimension": 256, "metricType": "L2", "indexType": "FLAT",
	}).Code)
	vec := make([]float32, 256)
	vec[0] = 1.0
	require.Equal(t, http.StatusCreated, doRequestFor(t, rA, http.MethodPost, "/api/v1/collections/knowledge/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{{"id": "a_doc", "vector": vec}},
	}).Code)

	// 租户 B 全局检索——不应看到租户 A 的数据
	w := doRequestFor(t, rB, http.MethodPost, "/api/v1/vector/search", map[string]interface{}{
		"query": "test", "topK": 5,
	})
	require.Equal(t, http.StatusOK, w.Code)
	var resp struct {
		Results []map[string]interface{} `json:"results"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Empty(t, resp.Results, "租户 B 的全局检索不应返回租户 A 的数据")
}

// ============ 越权防护 ============

// TestPrivilegeEscalation_TenantIdNotOverridable 验证客户端无法通过请求体覆盖租户身份。
// 租户身份来自 JWT 中间件（c.Set("tenantId", ...)），handler 从 gin context 提取，
// 不从请求体读取，因此客户端在请求体中传入 tenantId 字段不会生效。
func TestPrivilegeEscalation_TenantIdNotOverridable(t *testing.T) {
	svc := setupSharedService(t)
	rA := setupRouterForTenant(t, "tenantA", svc)
	rB := setupRouterForTenant(t, "tenantB", svc)

	// 租户 A 创建集合并插入向量
	require.Equal(t, http.StatusCreated, doRequestFor(t, rA, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name": "secure", "dimension": 2, "metricType": "L2", "indexType": "FLAT",
	}).Code)
	require.Equal(t, http.StatusCreated, doRequestFor(t, rA, http.MethodPost, "/api/v1/collections/secure/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{{"id": "secret_vec", "vector": []float32{1, 1}}},
	}).Code)

	// 租户 B 尝试在请求体中注入 tenantId=tenantA 来越权访问——不应生效
	// Search 请求体中加 "tenantId": "tenantA" 试图欺骗系统
	w := doRequestFor(t, rB, http.MethodPost, "/api/v1/collections/secure/search", map[string]interface{}{
		"vector":   []float32{1, 1},
		"topK":     10,
		"tenantId": "tenantA", // 越权尝试：试图冒充租户 A
	})
	// 应返回 404——租户 B 的命名空间下不存在 "secure" 集合
	assert.Equal(t, http.StatusNotFound, w.Code, "请求体中的 tenantId 不应覆盖 JWT 中的租户身份")

	// 同样验证 CreateCollection：租户 B 试图在请求体中注入 tenantId
	w = doRequestFor(t, rB, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name":       "secure", // 试图创建与租户 A 同名的集合来覆盖
		"dimension":  2,
		"metricType": "L2",
		"indexType":  "FLAT",
		"tenantId":   "tenantA", // 越权尝试
	})
	// 应成功创建——但在租户 B 的命名空间下，不影响租户 A 的集合
	require.Equal(t, http.StatusCreated, w.Code)

	// 验证租户 A 的向量仍然安全
	w = doRequestFor(t, rA, http.MethodPost, "/api/v1/collections/secure/search", map[string]interface{}{
		"vector": []float32{1, 1}, "topK": 10,
	})
	require.Equal(t, http.StatusOK, w.Code)
	var resp struct {
		Results []store.SearchResult `json:"results"`
		Total   int                  `json:"total"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 1, resp.Total)
	assert.Equal(t, "secret_vec", resp.Results[0].ID, "租户 A 的向量应未被影响")
}
