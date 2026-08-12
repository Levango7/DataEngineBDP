package service

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store/mock"
)

// newTestService 创建一个基于 MockVectorStore 的测试用 VectorService。
func newTestService(t *testing.T) *VectorService {
	t.Helper()
	return NewVectorService(mock.NewMockVectorStore())
}

// setupCollection 创建一个预置集合与向量的 service。
func setupCollection(t *testing.T, name string, dim int) *VectorService {
	t.Helper()
	svc := newTestService(t)
	require.NoError(t, svc.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: name, Dimension: dim, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	return svc
}

// ============ CreateCollection ============

func TestCreateCollection_Success(t *testing.T) {
	svc := newTestService(t)
	err := svc.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 128, MetricType: store.MetricIP, IndexType: store.IndexHNSW,
	})
	assert.NoError(t, err)
}

func TestCreateCollection_InvalidMetric(t *testing.T) {
	svc := newTestService(t)
	err := svc.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 4, MetricType: "BAD", IndexType: store.IndexFLAT,
	})
	assert.ErrorIs(t, err, store.ErrInvalidMetricType)
}

func TestCreateCollection_EmptyName(t *testing.T) {
	svc := newTestService(t)
	err := svc.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "", Dimension: 4, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	})
	assert.Error(t, err)
}

// ============ Insert ============

func TestInsert_Success(t *testing.T) {
	svc := setupCollection(t, "col", 3)
	err := svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{1, 2, 3}},
		},
	})
	assert.NoError(t, err)
}

func TestInsert_MissingID(t *testing.T) {
	svc := setupCollection(t, "col", 3)
	err := svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "", Vector: []float32{1, 2, 3}}},
	})
	assert.Error(t, err)
}

func TestInsert_EmptyVector(t *testing.T) {
	svc := setupCollection(t, "col", 3)
	err := svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "v1", Vector: []float32{}}},
	})
	assert.Error(t, err)
}

// ============ Search ============

func TestSearch_Success(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}},
			{ID: "v2", Vector: []float32{1, 1}},
		},
	}))

	results, err := svc.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{1, 1},
		TopK:           2,
	})
	require.NoError(t, err)
	assert.Len(t, results, 2)
	assert.Equal(t, "v2", results[0].ID)
}

func TestSearch_TopKNormalization(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}},
		},
	}))

	// topK=0 应归一化为默认值 10
	results, err := svc.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{0, 0},
		TopK:           0,
	})
	require.NoError(t, err)
	assert.Len(t, results, 1)

	// topK 超过上限应截断为 maxTopK
	results, err = svc.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{0, 0},
		TopK:           99999,
	})
	require.NoError(t, err)
	assert.Len(t, results, 1) // 实际只有 1 条向量
}

func TestSearch_NegativeTopK(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	_, err := svc.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{0, 0},
		TopK:           -1,
	})
	assert.Error(t, err)
}

func TestSearch_EmptyVector(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	_, err := svc.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{},
		TopK:           10,
	})
	assert.Error(t, err)
}

// ============ HybridSearch ============

func TestHybridSearch_Success(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}, Metadata: map[string]interface{}{"label": "a"}},
			{ID: "v2", Vector: []float32{1, 1}, Metadata: map[string]interface{}{"label": "b"}},
		},
	}))

	results, err := svc.HybridSearch(context.Background(), store.HybridSearchRequest{
		CollectionName: "col",
		Vector:         []float32{0, 0},
		TopK:           10,
		Filter:         "label=a",
	})
	require.NoError(t, err)
	assert.Len(t, results, 1)
	assert.Equal(t, "v1", results[0].ID)
}

func TestHybridSearch_MissingFilter(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	_, err := svc.HybridSearch(context.Background(), store.HybridSearchRequest{
		CollectionName: "col",
		Vector:         []float32{0, 0},
		TopK:           10,
		Filter:         "",
	})
	assert.Error(t, err)
}

// ============ Delete ============

func TestDelete_Success(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "v1", Vector: []float32{1, 1}}},
	}))

	err := svc.Delete(context.Background(), "col", []string{"v1"})
	assert.NoError(t, err)
}

func TestDelete_EmptyIDs(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	err := svc.Delete(context.Background(), "col", []string{})
	assert.Error(t, err)
}

// ============ GetStats ============

func TestGetStats_Success(t *testing.T) {
	svc := setupCollection(t, "col", 64)
	require.NoError(t, svc.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "v1", Vector: make([]float32, 64)}},
	}))

	stats, err := svc.GetStats(context.Background(), "col")
	require.NoError(t, err)
	assert.Equal(t, "col", stats.Name)
	assert.Equal(t, 64, stats.Dimension)
	assert.Equal(t, int64(1), stats.VectorCount)
}

func TestGetStats_EmptyName(t *testing.T) {
	svc := newTestService(t)
	_, err := svc.GetStats(context.Background(), "")
	assert.Error(t, err)
}

// ============ DropCollection ============

func TestDropCollection_Success(t *testing.T) {
	svc := setupCollection(t, "col", 2)
	err := svc.DropCollection(context.Background(), "col")
	assert.NoError(t, err)
}

func TestDropCollection_EmptyName(t *testing.T) {
	svc := newTestService(t)
	err := svc.DropCollection(context.Background(), "")
	assert.Error(t, err)
}

// ============ 哨兵错误映射 ============

func TestErrorMapping(t *testing.T) {
	svc := newTestService(t)

	// 集合不存在
	_, err := svc.GetStats(context.Background(), "nope")
	assert.True(t, errors.Is(err, store.ErrCollectionNotFound))

	// 集合已存在
	require.NoError(t, svc.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	err = svc.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	})
	assert.True(t, errors.Is(err, store.ErrCollectionAlreadyExists))
}
