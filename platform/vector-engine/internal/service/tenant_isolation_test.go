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

// ctxFor 返回带指定租户身份的 context。
func ctxFor(tenantID string) context.Context {
	return WithTenantID(context.Background(), tenantID)
}

// ============ 缺失租户身份 fail-fast ============

// TestMissingTenant_RejectsAllOperations 验证缺失租户身份时所有数据操作被拒绝。
// 这是租户隔离的安全基石：没有租户身份的请求不得触碰任何向量数据。
func TestMissingTenant_RejectsAllOperations(t *testing.T) {
	svc := NewVectorService(mock.NewMockVectorStore())
	ctx := context.Background() // 无租户身份

	t.Run("CreateCollection", func(t *testing.T) {
		err := svc.CreateCollection(ctx, store.CreateCollectionRequest{
			Name: "col", Dimension: 4, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
		})
		assert.ErrorIs(t, err, ErrMissingTenant)
	})

	t.Run("DropCollection", func(t *testing.T) {
		err := svc.DropCollection(ctx, "col")
		assert.ErrorIs(t, err, ErrMissingTenant)
	})

	t.Run("Insert", func(t *testing.T) {
		err := svc.Insert(ctx, store.InsertRequest{
			CollectionName: "col",
			Vectors:        []store.Vector{{ID: "v1", Vector: []float32{1, 2}}},
		})
		assert.ErrorIs(t, err, ErrMissingTenant)
	})

	t.Run("Search", func(t *testing.T) {
		_, err := svc.Search(ctx, store.SearchRequest{
			CollectionName: "col", Vector: []float32{1, 2}, TopK: 5,
		})
		assert.ErrorIs(t, err, ErrMissingTenant)
	})

	t.Run("HybridSearch", func(t *testing.T) {
		_, err := svc.HybridSearch(ctx, store.HybridSearchRequest{
			CollectionName: "col", Vector: []float32{1, 2}, TopK: 5, Filter: "x=1",
		})
		assert.ErrorIs(t, err, ErrMissingTenant)
	})

	t.Run("Delete", func(t *testing.T) {
		err := svc.Delete(ctx, "col", []string{"v1"})
		assert.ErrorIs(t, err, ErrMissingTenant)
	})

	t.Run("ListCollections", func(t *testing.T) {
		_, err := svc.ListCollections(ctx)
		assert.ErrorIs(t, err, ErrMissingTenant)
	})

	t.Run("GetStats", func(t *testing.T) {
		_, err := svc.GetStats(ctx, "col")
		assert.ErrorIs(t, err, ErrMissingTenant)
	})
}

// ============ 集合命名空间隔离 ============

// TestTenantIsolation_SameCollectionNameInDifferentTenants 验证两个租户可以创建同名集合。
// 集合名在存储层映射为 "<tenantId>__<name>"，互不冲突。
func TestTenantIsolation_SameCollectionNameInDifferentTenants(t *testing.T) {
	svc := NewVectorService(mock.NewMockVectorStore())

	// 租户 A 创建集合 "shared"
	require.NoError(t, svc.CreateCollection(ctxFor("tenantA"), store.CreateCollectionRequest{
		Name: "shared", Dimension: 3, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))

	// 租户 B 创建同名集合 "shared"——不应冲突
	require.NoError(t, svc.CreateCollection(ctxFor("tenantB"), store.CreateCollectionRequest{
		Name: "shared", Dimension: 3, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))

	// 两个租户各自只看到自己的集合
	colsA, err := svc.ListCollections(ctxFor("tenantA"))
	require.NoError(t, err)
	assert.Len(t, colsA, 1)
	assert.Equal(t, "shared", colsA[0].Name)

	colsB, err := svc.ListCollections(ctxFor("tenantB"))
	require.NoError(t, err)
	assert.Len(t, colsB, 1)
	assert.Equal(t, "shared", colsB[0].Name)
}

// ============ 跨租户数据不可见 ============

// TestTenantIsolation_SearchCrossTenantInvisible 验证租户 B 无法检索到租户 A 的向量。
func TestTenantIsolation_SearchCrossTenantInvisible(t *testing.T) {
	svc := NewVectorService(mock.NewMockVectorStore())

	// 租户 A 创建集合并插入向量
	require.NoError(t, svc.CreateCollection(ctxFor("tenantA"), store.CreateCollectionRequest{
		Name: "docs", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	require.NoError(t, svc.Insert(ctxFor("tenantA"), store.InsertRequest{
		CollectionName: "docs",
		Vectors: []store.Vector{
			{ID: "a1", Vector: []float32{1, 0}},
			{ID: "a2", Vector: []float32{0, 1}},
		},
	}))

	// 租户 B 创建同名集合并插入自己的向量
	require.NoError(t, svc.CreateCollection(ctxFor("tenantB"), store.CreateCollectionRequest{
		Name: "docs", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	require.NoError(t, svc.Insert(ctxFor("tenantB"), store.InsertRequest{
		CollectionName: "docs",
		Vectors: []store.Vector{
			{ID: "b1", Vector: []float32{1, 1}},
		},
	}))

	// 租户 B 检索 "docs"——只能看到自己的向量，看不到租户 A 的
	results, err := svc.Search(ctxFor("tenantB"), store.SearchRequest{
		CollectionName: "docs", Vector: []float32{1, 1}, TopK: 10,
	})
	require.NoError(t, err)
	assert.Len(t, results, 1)
	assert.Equal(t, "b1", results[0].ID)

	// 租户 A 检索 "docs"——只能看到自己的向量，看不到租户 B 的
	results, err = svc.Search(ctxFor("tenantA"), store.SearchRequest{
		CollectionName: "docs", Vector: []float32{1, 0}, TopK: 10,
	})
	require.NoError(t, err)
	assert.Len(t, results, 2)
	ids := []string{results[0].ID, results[1].ID}
	assert.Contains(t, ids, "a1")
	assert.Contains(t, ids, "a2")
	assert.NotContains(t, ids, "b1")
}

// TestTenantIsolation_DropCrossTenantNotFound 验证租户 B 删除租户 A 的集合名时得到 NotFound。
// 因为映射后内部名不同（tenantB__docs vs tenantA__docs），租户 B 的命名空间下不存在该集合。
func TestTenantIsolation_DropCrossTenantNotFound(t *testing.T) {
	svc := NewVectorService(mock.NewMockVectorStore())

	// 租户 A 创建集合
	require.NoError(t, svc.CreateCollection(ctxFor("tenantA"), store.CreateCollectionRequest{
		Name: "secret", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))

	// 租户 B 尝试删除同名集合——应返回 NotFound（隔离保护）
	err := svc.DropCollection(ctxFor("tenantB"), "secret")
	assert.True(t, errors.Is(err, store.ErrCollectionNotFound))
}

// TestTenantIsolation_DeleteVectorsCrossTenantNotFound 验证租户 B 无法删除租户 A 的向量。
func TestTenantIsolation_DeleteVectorsCrossTenantNotFound(t *testing.T) {
	svc := NewVectorService(mock.NewMockVectorStore())

	// 租户 A 创建集合并插入向量
	require.NoError(t, svc.CreateCollection(ctxFor("tenantA"), store.CreateCollectionRequest{
		Name: "data", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	require.NoError(t, svc.Insert(ctxFor("tenantA"), store.InsertRequest{
		CollectionName: "data",
		Vectors:        []store.Vector{{ID: "v1", Vector: []float32{1, 2}}},
	}))

	// 租户 B 尝试删除 "data" 集合中的向量——应返回 NotFound
	err := svc.Delete(ctxFor("tenantB"), "data", []string{"v1"})
	assert.True(t, errors.Is(err, store.ErrCollectionNotFound))

	// 验证租户 A 的向量仍然存在
	results, err := svc.Search(ctxFor("tenantA"), store.SearchRequest{
		CollectionName: "data", Vector: []float32{1, 2}, TopK: 10,
	})
	require.NoError(t, err)
	assert.Len(t, results, 1)
	assert.Equal(t, "v1", results[0].ID)
}

// TestTenantIsolation_GetStatsCrossTenantNotFound 验证租户 B 无法获取租户 A 的集合统计。
func TestTenantIsolation_GetStatsCrossTenantNotFound(t *testing.T) {
	svc := NewVectorService(mock.NewMockVectorStore())

	// 租户 A 创建集合
	require.NoError(t, svc.CreateCollection(ctxFor("tenantA"), store.CreateCollectionRequest{
		Name: "metrics", Dimension: 64, MetricType: store.MetricIP, IndexType: store.IndexHNSW,
	}))

	// 租户 B 尝试获取统计——应返回 NotFound
	_, err := svc.GetStats(ctxFor("tenantB"), "metrics")
	assert.True(t, errors.Is(err, store.ErrCollectionNotFound))

	// 租户 A 可以正常获取
	stats, err := svc.GetStats(ctxFor("tenantA"), "metrics")
	require.NoError(t, err)
	assert.Equal(t, "metrics", stats.Name)
	assert.Equal(t, 64, stats.Dimension)
}

// TestTenantIsolation_ListCollectionsOnlyOwnTenant 验证 ListCollections 只返回当前租户的集合。
func TestTenantIsolation_ListCollectionsOnlyOwnTenant(t *testing.T) {
	svc := NewVectorService(mock.NewMockVectorStore())

	// 三个租户各创建不同数量的集合
	require.NoError(t, svc.CreateCollection(ctxFor("tA"), store.CreateCollectionRequest{
		Name: "a1", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	require.NoError(t, svc.CreateCollection(ctxFor("tA"), store.CreateCollectionRequest{
		Name: "a2", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	require.NoError(t, svc.CreateCollection(ctxFor("tB"), store.CreateCollectionRequest{
		Name: "b1", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))

	// 租户 tA 应看到 2 个集合
	colsA, err := svc.ListCollections(ctxFor("tA"))
	require.NoError(t, err)
	assert.Len(t, colsA, 2)

	// 租户 tB 应看到 1 个集合
	colsB, err := svc.ListCollections(ctxFor("tB"))
	require.NoError(t, err)
	assert.Len(t, colsB, 1)
	assert.Equal(t, "b1", colsB[0].Name)

	// 租户 tC 应看到 0 个集合
	colsC, err := svc.ListCollections(ctxFor("tC"))
	require.NoError(t, err)
	assert.Empty(t, colsC)
}

// TestTenantIsolation_HybridSearchCrossTenantInvisible 验证混合检索也受租户隔离保护。
func TestTenantIsolation_HybridSearchCrossTenantInvisible(t *testing.T) {
	svc := NewVectorService(mock.NewMockVectorStore())

	// 租户 A 创建集合并插入带标签的向量
	require.NoError(t, svc.CreateCollection(ctxFor("tenantA"), store.CreateCollectionRequest{
		Name: "hybrid", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))
	require.NoError(t, svc.Insert(ctxFor("tenantA"), store.InsertRequest{
		CollectionName: "hybrid",
		Vectors: []store.Vector{
			{ID: "a1", Vector: []float32{1, 0}, Metadata: map[string]interface{}{"label": "x"}},
		},
	}))

	// 租户 B 创建同名集合但为空
	require.NoError(t, svc.CreateCollection(ctxFor("tenantB"), store.CreateCollectionRequest{
		Name: "hybrid", Dimension: 2, MetricType: store.MetricL2, IndexType: store.IndexFLAT,
	}))

	// 租户 B 混合检索——结果应为空（看不到租户 A 的数据）
	results, err := svc.HybridSearch(ctxFor("tenantB"), store.HybridSearchRequest{
		CollectionName: "hybrid", Vector: []float32{1, 0}, TopK: 10, Filter: "label=x",
	})
	require.NoError(t, err)
	assert.Empty(t, results)
}
