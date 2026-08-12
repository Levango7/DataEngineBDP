package mock

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
)

// newTestStore 创建一个预置集合的测试用 MockVectorStore。
func newTestStore(t *testing.T, name string, dim int, metric, index string) *MockVectorStore {
	t.Helper()
	s := NewMockVectorStore()
	err := s.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: name, Dimension: dim, MetricType: metric, IndexType: index,
	})
	require.NoError(t, err)
	return s
}

// ============ CreateCollection ============

func TestCreateCollection_Success(t *testing.T) {
	s := NewMockVectorStore()
	err := s.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "test_col", Dimension: 128, MetricType: store.MetricL2, IndexType: store.IndexHNSW,
	})
	assert.NoError(t, err)
}

func TestCreateCollection_AlreadyExists(t *testing.T) {
	s := newTestStore(t, "col", 4, store.MetricL2, store.IndexFLAT)
	err := s.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 4, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	})
	assert.ErrorIs(t, err, store.ErrCollectionAlreadyExists)
}

func TestCreateCollection_InvalidMetric(t *testing.T) {
	s := NewMockVectorStore()
	err := s.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 4, MetricType: "INVALID", IndexType: store.IndexFLAT,
	})
	assert.ErrorIs(t, err, store.ErrInvalidMetricType)
}

func TestCreateCollection_InvalidIndex(t *testing.T) {
	s := NewMockVectorStore()
	err := s.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 4, MetricType: store.MetricL2, IndexType: "INVALID",
	})
	assert.ErrorIs(t, err, store.ErrInvalidIndexType)
}

func TestCreateCollection_InvalidDimension(t *testing.T) {
	s := NewMockVectorStore()
	err := s.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 0, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	})
	assert.Error(t, err)
}

// ============ DropCollection ============

func TestDropCollection_Success(t *testing.T) {
	s := newTestStore(t, "col", 4, store.MetricL2, store.IndexFLAT)
	err := s.DropCollection(context.Background(), "col")
	assert.NoError(t, err)
}

func TestDropCollection_NotFound(t *testing.T) {
	s := NewMockVectorStore()
	err := s.DropCollection(context.Background(), "nonexistent")
	assert.ErrorIs(t, err, store.ErrCollectionNotFound)
}

// ============ Insert ============

func TestInsert_Success(t *testing.T) {
	s := newTestStore(t, "col", 3, store.MetricL2, store.IndexFLAT)
	err := s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{1, 2, 3}, Metadata: map[string]interface{}{"label": "a"}},
			{ID: "v2", Vector: []float32{4, 5, 6}},
		},
	})
	assert.NoError(t, err)

	stats, err := s.GetStats(context.Background(), "col")
	require.NoError(t, err)
	assert.Equal(t, int64(2), stats.VectorCount)
}

func TestInsert_CollectionNotFound(t *testing.T) {
	s := NewMockVectorStore()
	err := s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "nonexistent",
		Vectors:        []store.Vector{{ID: "v1", Vector: []float32{1, 2, 3}}},
	})
	assert.ErrorIs(t, err, store.ErrCollectionNotFound)
}

func TestInsert_InvalidDimension(t *testing.T) {
	s := newTestStore(t, "col", 3, store.MetricL2, store.IndexFLAT)
	err := s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "v1", Vector: []float32{1, 2}}},
	})
	assert.ErrorIs(t, err, store.ErrInvalidDimension)
}

func TestInsert_EmptyVectors(t *testing.T) {
	s := newTestStore(t, "col", 3, store.MetricL2, store.IndexFLAT)
	err := s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{},
	})
	assert.Error(t, err)
}

func TestInsert_MissingID(t *testing.T) {
	s := newTestStore(t, "col", 3, store.MetricL2, store.IndexFLAT)
	err := s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "", Vector: []float32{1, 2, 3}}},
	})
	assert.Error(t, err)
}

// ============ Search ============

func TestSearch_L2(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricL2, store.IndexFLAT)
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}},
			{ID: "v2", Vector: []float32{1, 1}},
			{ID: "v3", Vector: []float32{3, 3}},
		},
	}))

	results, err := s.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{1, 1},
		TopK:           2,
	})
	require.NoError(t, err)
	require.Len(t, results, 2)
	// v2 距离最近（距离 0），v1 次之（距离 2）
	assert.Equal(t, "v2", results[0].ID)
	assert.InDelta(t, 0.0, results[0].Score, 1e-6)
	assert.Equal(t, "v1", results[1].ID)
}

func TestSearch_IP(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricIP, store.IndexFLAT)
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{1, 0}},
			{ID: "v2", Vector: []float32{0, 1}},
			{ID: "v3", Vector: []float32{1, 1}},
		},
	}))

	results, err := s.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{1, 1},
		TopK:           3,
	})
	require.NoError(t, err)
	require.Len(t, results, 3)
	// v3 内积最大（=2），v1/v2 次之（=1）
	assert.Equal(t, "v3", results[0].ID)
	assert.InDelta(t, 2.0, results[0].Score, 1e-6)
}

func TestSearch_Cosine(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricCOSINE, store.IndexFLAT)
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{1, 0}},
			{ID: "v2", Vector: []float32{1, 1}},
		},
	}))

	results, err := s.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{1, 0},
		TopK:           2,
	})
	require.NoError(t, err)
	require.Len(t, results, 2)
	// v1 余弦相似度 = 1（完全相同方向）
	assert.Equal(t, "v1", results[0].ID)
	assert.InDelta(t, 1.0, results[0].Score, 1e-6)
}

func TestSearch_WithFilter(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricL2, store.IndexFLAT)
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}, Metadata: map[string]interface{}{"label": "a"}},
			{ID: "v2", Vector: []float32{1, 1}, Metadata: map[string]interface{}{"label": "b"}},
		},
	}))

	results, err := s.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{1, 1},
		TopK:           10,
		Filter:         "label=a",
	})
	require.NoError(t, err)
	require.Len(t, results, 1)
	assert.Equal(t, "v1", results[0].ID)
}

func TestSearch_CollectionNotFound(t *testing.T) {
	s := NewMockVectorStore()
	_, err := s.Search(context.Background(), store.SearchRequest{
		CollectionName: "nonexistent",
		Vector:         []float32{1, 1},
		TopK:           10,
	})
	assert.ErrorIs(t, err, store.ErrCollectionNotFound)
}

func TestSearch_DefaultTopK(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricL2, store.IndexFLAT)
	vectors := make([]store.Vector, 15)
	for i := range vectors {
		vectors[i] = store.Vector{
			ID:     "v" + string(rune('a'+i)),
			Vector: []float32{float32(i), float32(i)},
		}
	}
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col", Vectors: vectors,
	}))

	// topK=0 应使用默认值 10
	results, err := s.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{0, 0},
		TopK:           0,
	})
	require.NoError(t, err)
	assert.Len(t, results, 10)
}

// ============ HybridSearch ============

func TestHybridSearch_WithFilterAndThreshold(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricL2, store.IndexFLAT)
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{0, 0}, Metadata: map[string]interface{}{"label": "a"}},
			{ID: "v2", Vector: []float32{1, 1}, Metadata: map[string]interface{}{"label": "a"}},
			{ID: "v3", Vector: []float32{10, 10}, Metadata: map[string]interface{}{"label": "b"}},
		},
	}))

	// 混合检索：仅 label=a，且 L2 距离 <= 5
	results, err := s.HybridSearch(context.Background(), store.HybridSearchRequest{
		CollectionName: "col",
		Vector:         []float32{1, 1},
		TopK:           10,
		Filter:         "label=a",
		MinScore:       5.0,
	})
	require.NoError(t, err)
	// v2（距离 0）和 v1（距离 2）满足，v3 被过滤
	assert.Len(t, results, 2)
	assert.Equal(t, "v2", results[0].ID)
	assert.Equal(t, "v1", results[1].ID)
}

// ============ Delete ============

func TestDelete_Success(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricL2, store.IndexFLAT)
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: []float32{1, 1}},
			{ID: "v2", Vector: []float32{2, 2}},
		},
	}))

	err := s.Delete(context.Background(), "col", []string{"v1"})
	assert.NoError(t, err)

	stats, err := s.GetStats(context.Background(), "col")
	require.NoError(t, err)
	assert.Equal(t, int64(1), stats.VectorCount)
}

func TestDelete_CollectionNotFound(t *testing.T) {
	s := NewMockVectorStore()
	err := s.Delete(context.Background(), "nonexistent", []string{"v1"})
	assert.ErrorIs(t, err, store.ErrCollectionNotFound)
}

// ============ GetStats ============

func TestGetStats_Success(t *testing.T) {
	s := newTestStore(t, "col", 128, store.MetricIP, store.IndexHNSW)
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors: []store.Vector{
			{ID: "v1", Vector: make([]float32, 128)},
		},
	}))

	stats, err := s.GetStats(context.Background(), "col")
	require.NoError(t, err)
	assert.Equal(t, "col", stats.Name)
	assert.Equal(t, 128, stats.Dimension)
	assert.Equal(t, store.MetricIP, stats.MetricType)
	assert.Equal(t, store.IndexHNSW, stats.IndexType)
	assert.Equal(t, int64(1), stats.VectorCount)
}

func TestGetStats_NotFound(t *testing.T) {
	s := NewMockVectorStore()
	_, err := s.GetStats(context.Background(), "nonexistent")
	assert.ErrorIs(t, err, store.ErrCollectionNotFound)
}

// ============ 接口契约验证 ============

// TestImplementsVectorStore 编译期验证 MockVectorStore 实现 VectorStore 接口。
func TestImplementsVectorStore(t *testing.T) {
	var _ store.VectorStore = NewMockVectorStore()
}

// TestInsert_DeepCopy 验证 Insert 后修改外部切片不影响内部状态。
func TestInsert_DeepCopy(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricL2, store.IndexFLAT)
	original := []float32{1, 2}
	require.NoError(t, s.Insert(context.Background(), store.InsertRequest{
		CollectionName: "col",
		Vectors:        []store.Vector{{ID: "v1", Vector: original}},
	}))

	// 修改外部切片
	original[0] = 999

	// 内部状态应不受影响
	results, err := s.Search(context.Background(), store.SearchRequest{
		CollectionName: "col",
		Vector:         []float32{1, 2},
		TopK:           1,
	})
	require.NoError(t, err)
	require.Len(t, results, 1)
	// 若未深拷贝，距离会很大（999-1=998）
	assert.InDelta(t, 0.0, results[0].Score, 1e-6)
}

// TestErrorsSentinels 验证哨兵错误可被 errors.Is 识别。
func TestErrorsSentinels(t *testing.T) {
	s := newTestStore(t, "col", 2, store.MetricL2, store.IndexFLAT)

	// 重复创建
	err := s.CreateCollection(context.Background(), store.CreateCollectionRequest{
		Name: "col", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	})
	assert.True(t, errors.Is(err, store.ErrCollectionAlreadyExists))

	// 删除不存在的集合
	err = s.DropCollection(context.Background(), "nope")
	assert.True(t, errors.Is(err, store.ErrCollectionNotFound))
}
