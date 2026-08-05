package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/shuqing/bigdata/vector-engine/internal/service"
	"github.com/shuqing/bigdata/vector-engine/internal/store"
	"github.com/shuqing/bigdata/vector-engine/internal/store/mock"
)

// setupRouter 创建一个用于测试的 Gin 引擎。
func setupRouter() (*gin.Engine, *service.VectorService) {
	gin.SetMode(gin.TestMode)
	svc := service.NewVectorService(mock.NewMockVectorStore())
	r := gin.New()
	v1 := r.Group("/api/v1")
	h := NewVectorHandler(svc)
	h.RegisterRoutes(v1)
	return r, svc
}

// setupRouterWithCollection 创建测试路由并预置一个集合。
func setupRouterWithCollection(t *testing.T, name string, dim int) (*gin.Engine, *service.VectorService) {
	t.Helper()
	r, svc := setupRouter()
	require.NoError(t, svc.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: name, Dimension: dim, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	return r, svc
}

// doRequest 发送 JSON 请求并返回响应。
func doRequest(t *testing.T, r *gin.Engine, method, path string, body interface{}) *httptest.ResponseRecorder {
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

// ============ Health ============

func TestHealth(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	h := NewHealthHandler("0.1.0", "vector-engine")
	r.GET("/health", h.Health)

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	var resp map[string]string
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "UP", resp["status"])
	assert.Equal(t, "vector-engine", resp["component"])
	assert.Equal(t, "0.1.0", resp["version"])
}

// ============ CreateCollection ============

func TestCreateCollection_Success(t *testing.T) {
	r, _ := setupRouter()

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name":        "test_col",
		"dimension":   128,
		"metric_type": "L2",
		"index_type":  "HNSW",
	})
	assert.Equal(t, http.StatusCreated, w.Code)

	var resp map[string]interface{}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "test_col", resp["name"])
	assert.EqualValues(t, 128, resp["dimension"])
}

func TestCreateCollection_AlreadyExists(t *testing.T) {
	r, _ := setupRouterWithCollection(t, "col", 4)

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name":        "col",
		"dimension":   4,
		"metric_type": "L2",
		"index_type":  "FLAT",
	})
	assert.Equal(t, http.StatusConflict, w.Code)
}

func TestCreateCollection_InvalidMetric(t *testing.T) {
	r, _ := setupRouter()

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name":        "col",
		"dimension":   4,
		"metric_type": "BAD",
		"index_type":  "FLAT",
	})
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestCreateCollection_BadJSON(t *testing.T) {
	r, _ := setupRouter()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/collections", bytes.NewBufferString("not json"))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// ============ DropCollection ============

func TestDropCollection_Success(t *testing.T) {
	r, _ := setupRouterWithCollection(t, "col", 4)

	w := doRequest(t, r, http.MethodDelete, "/api/v1/collections/col", nil)
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestDropCollection_NotFound(t *testing.T) {
	r, _ := setupRouter()

	w := doRequest(t, r, http.MethodDelete, "/api/v1/collections/nope", nil)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

// ============ InsertVectors ============

func TestInsertVectors_Success(t *testing.T) {
	r, _ := setupRouterWithCollection(t, "col", 3)

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections/col/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{
			{"id": "v1", "vector": []float32{1, 2, 3}},
			{"id": "v2", "vector": []float32{4, 5, 6}},
		},
	})
	assert.Equal(t, http.StatusCreated, w.Code)

	var resp map[string]int
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 2, resp["inserted"])
}

func TestInsertVectors_InvalidDimension(t *testing.T) {
	r, _ := setupRouterWithCollection(t, "col", 3)

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections/col/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{
			{"id": "v1", "vector": []float32{1, 2}},
		},
	})
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestInsertVectors_CollectionNotFound(t *testing.T) {
	r, _ := setupRouter()

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections/nope/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{
			{"id": "v1", "vector": []float32{1, 2, 3}},
		},
	})
	assert.Equal(t, http.StatusNotFound, w.Code)
}

// ============ Search ============

func TestSearch_Success(t *testing.T) {
	r, svc := setupRouterWithCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}},
			{ID: "v2", Vector: []float32{1, 1}},
		},
	}))

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections/col/search", map[string]interface{}{
		"vector": []float32{1, 1},
		"top_k":  10,
	})
	assert.Equal(t, http.StatusOK, w.Code)

	var resp struct {
		Results []store.SearchResult `json:"results"`
		Total   int                  `json:"total"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 2, resp.Total)
	assert.Equal(t, "v2", resp.Results[0].ID)
}

func TestSearch_WithFilter(t *testing.T) {
	r, svc := setupRouterWithCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}, Metadata: map[string]interface{}{"label": "a"}},
			{ID: "v2", Vector: []float32{1, 1}, Metadata: map[string]interface{}{"label": "b"}},
		},
	}))

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections/col/search", map[string]interface{}{
		"vector": []float32{1, 1},
		"top_k":  10,
		"filter": "label=a",
	})
	assert.Equal(t, http.StatusOK, w.Code)

	var resp struct {
		Results []store.SearchResult `json:"results"`
		Total   int                  `json:"total"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 1, resp.Total)
	assert.Equal(t, "v1", resp.Results[0].ID)
}

// ============ HybridSearch ============

func TestHybridSearch_Success(t *testing.T) {
	r, svc := setupRouterWithCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}, Metadata: map[string]interface{}{"label": "a"}},
			{ID: "v2", Vector: []float32{1, 1}, Metadata: map[string]interface{}{"label": "b"}},
		},
	}))

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections/col/hybrid-search", map[string]interface{}{
		"vector":    []float32{0, 0},
		"top_k":     10,
		"filter":    "label=a",
		"min_score": 0,
	})
	assert.Equal(t, http.StatusOK, w.Code)

	var resp struct {
		Results []store.SearchResult `json:"results"`
		Total   int                  `json:"total"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 1, resp.Total)
	assert.Equal(t, "v1", resp.Results[0].ID)
}

func TestHybridSearch_MissingFilter(t *testing.T) {
	r, svc := setupRouterWithCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "v1", Vector: []float32{0, 0}}},
	}))

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections/col/hybrid-search", map[string]interface{}{
		"vector": []float32{0, 0},
		"top_k":  10,
	})
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// ============ DeleteVectors ============

func TestDeleteVectors_Success(t *testing.T) {
	r, svc := setupRouterWithCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{1, 1}},
			{ID: "v2", Vector: []float32{2, 2}},
		},
	}))

	w := doRequest(t, r, http.MethodDelete, "/api/v1/collections/col/vectors", map[string]interface{}{
		"ids": []string{"v1", "v2"},
	})
	assert.Equal(t, http.StatusOK, w.Code)

	var resp map[string]int
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, 2, resp["deleted"])
}

// ============ GetStats ============

func TestGetStats_Success(t *testing.T) {
	r, svc := setupRouterWithCollection(t, "col", 64)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "v1", Vector: make([]float32, 64)}},
	}))

	w := doRequest(t, r, http.MethodGet, "/api/v1/collections/col/stats", nil)
	assert.Equal(t, http.StatusOK, w.Code)

	var stats store.CollectionStats
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &stats))
	assert.Equal(t, "col", stats.Name)
	assert.Equal(t, 64, stats.Dimension)
	assert.Equal(t, int64(1), stats.VectorCount)
}

func TestGetStats_NotFound(t *testing.T) {
	r, _ := setupRouter()

	w := doRequest(t, r, http.MethodGet, "/api/v1/collections/nope/stats", nil)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

// ============ 端到端流程 ============

func TestEndToEnd_FullFlow(t *testing.T) {
	r, _ := setupRouter()

	// 1. 创建集合
	w := doRequest(t, r, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name":        "e2e_col",
		"dimension":   3,
		"metric_type": "COSINE",
		"index_type":  "HNSW",
	})
	require.Equal(t, http.StatusCreated, w.Code)

	// 2. 插入向量
	w = doRequest(t, r, http.MethodPost, "/api/v1/collections/e2e_col/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{
			{"id": "doc1", "vector": []float32{1, 0, 0}, "metadata": map[string]interface{}{"source": "doc1.txt"}},
			{"id": "doc2", "vector": []float32{0, 1, 0}, "metadata": map[string]interface{}{"source": "doc2.txt"}},
			{"id": "doc3", "vector": []float32{0, 0, 1}, "metadata": map[string]interface{}{"source": "doc3.txt"}},
		},
	})
	require.Equal(t, http.StatusCreated, w.Code)

	// 3. 检索
	w = doRequest(t, r, http.MethodPost, "/api/v1/collections/e2e_col/search", map[string]interface{}{
		"vector": []float32{1, 0, 0},
		"top_k":  3,
	})
	require.Equal(t, http.StatusOK, w.Code)
	var searchResp struct {
		Results []store.SearchResult `json:"results"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &searchResp))
	require.Len(t, searchResp.Results, 3)
	assert.Equal(t, "doc1", searchResp.Results[0].ID)
	assert.InDelta(t, 1.0, searchResp.Results[0].Score, 1e-6)

	// 4. 统计
	w = doRequest(t, r, http.MethodGet, "/api/v1/collections/e2e_col/stats", nil)
	require.Equal(t, http.StatusOK, w.Code)

	// 5. 删除向量
	w = doRequest(t, r, http.MethodDelete, "/api/v1/collections/e2e_col/vectors", map[string]interface{}{
		"ids": []string{"doc1"},
	})
	require.Equal(t, http.StatusOK, w.Code)

	// 6. 删除集合
	w = doRequest(t, r, http.MethodDelete, "/api/v1/collections/e2e_col", nil)
	require.Equal(t, http.StatusNoContent, w.Code)
}
