package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/service"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store/mock"
)

// failingStore 包装 store.VectorStore，按方法注入指定错误，
// 用于验证 writeStoreError 的状态码映射。
type failingStore struct {
	store.VectorStore

	createErr error
	listErr   error
	insertErr error
}

func (f *failingStore) CreateCollection(ctx context.Context, req store.CreateCollectionRequest) error {
	if f.createErr != nil {
		return f.createErr
	}
	return f.VectorStore.CreateCollection(ctx, req)
}

func (f *failingStore) ListCollections(ctx context.Context) ([]store.Collection, error) {
	if f.listErr != nil {
		return nil, f.listErr
	}
	return f.VectorStore.ListCollections(ctx)
}

func (f *failingStore) Insert(ctx context.Context, req store.InsertRequest) error {
	if f.insertErr != nil {
		return f.insertErr
	}
	return f.VectorStore.Insert(ctx, req)
}

// newFailingRouter 基于给定 store 构建测试路由。
func newFailingRouter(s store.VectorStore) *gin.Engine {
	gin.SetMode(gin.TestMode)
	svc := service.NewVectorService(s)
	r := gin.New()
	v1 := r.Group("/api/v1")
	h := NewVectorHandler(svc)
	h.RegisterRoutes(v1)
	return r
}

func TestWriteStoreError_InternalErrorReturns500(t *testing.T) {
	s := &failingStore{
		VectorStore: mock.NewMockVectorStore(),
		createErr:   errors.New("connection refused"),
	}
	r := newFailingRouter(s)

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name":       "col",
		"dimension":  4,
		"metricType": "L2",
		"indexType":  "FLAT",
	})
	assert.Equal(t, http.StatusInternalServerError, w.Code)

	var resp map[string]string
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "internal_error", resp["error"])
}

func TestWriteStoreError_NotFoundReturns404(t *testing.T) {
	s := &failingStore{
		VectorStore: mock.NewMockVectorStore(),
		listErr:     fmt.Errorf("list: %w", store.ErrCollectionNotFound),
	}
	r := newFailingRouter(s)

	w := doRequest(t, r, http.MethodGet, "/api/v1/vector", nil)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestWriteStoreError_AlreadyExistsReturns409(t *testing.T) {
	s := &failingStore{
		VectorStore: mock.NewMockVectorStore(),
		createErr:   fmt.Errorf("create: %w", store.ErrCollectionAlreadyExists),
	}
	r := newFailingRouter(s)

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections", map[string]interface{}{
		"name":       "col",
		"dimension":  4,
		"metricType": "L2",
		"indexType":  "FLAT",
	})
	assert.Equal(t, http.StatusConflict, w.Code)
}

func TestWriteStoreError_ValidationErrorReturns400(t *testing.T) {
	s := &failingStore{
		VectorStore: mock.NewMockVectorStore(),
		insertErr:   fmt.Errorf("insert: %w", store.ErrInvalidDimension),
	}
	r := newFailingRouter(s)

	w := doRequest(t, r, http.MethodPost, "/api/v1/collections/col/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{
			{"id": "v1", "vector": []float32{1, 2}},
		},
	})
	assert.Equal(t, http.StatusBadRequest, w.Code)
}
