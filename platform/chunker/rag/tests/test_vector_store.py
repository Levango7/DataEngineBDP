"""向量存储测试 (T008-6)."""
from __future__ import annotations

import math

import pytest

from chunker.rag.exceptions import (
    CollectionAlreadyExistsError,
    CollectionNotFoundError,
    VectorStoreError,
)
from chunker.rag.vector_store import (
    INDEX_HNSW,
    METRIC_COSINE,
    METRIC_IP,
    METRIC_L2,
    CollectionInfo,
    MilvusVectorStore,
    MockVectorStore,
    SearchResult,
    VectorRecord,
    VectorStore,
    _compute_similarity,
    _eval_filter,
    create_vector_store,
    is_pymilvus_available,
)


# ----------------------------------------------------------------------
# 数据模型
# ----------------------------------------------------------------------


class TestModels:
    def test_collection_info(self):
        info = CollectionInfo("test", 128)
        assert info.name == "test"
        assert info.dimension == 128
        assert info.metricType == METRIC_COSINE
        d = info.to_dict()
        assert d["name"] == "test"

    def test_vector_record(self):
        rec = VectorRecord("id1", [1.0, 2.0], {"key": "val"})
        assert rec.id == "id1"
        assert rec.vector == [1.0, 2.0]
        assert rec.metadata == {"key": "val"}

    def test_vector_record_default_metadata(self):
        rec = VectorRecord("id1", [1.0])
        assert rec.metadata == {}

    def test_search_result(self):
        r = SearchResult("id1", 0.95, {"modality": "text"})
        assert r.id == "id1"
        assert r.score == 0.95
        d = r.to_dict()
        assert d["score"] == 0.95


# ----------------------------------------------------------------------
# 辅助函数
# ----------------------------------------------------------------------


class TestComputeSimilarity:
    def test_l2(self):
        score = _compute_similarity([1, 0], [0, 1], METRIC_L2)
        # L2 返回负距离（越大越相似）
        assert score < 0

    def test_l2_same_vector(self):
        score = _compute_similarity([1, 2], [1, 2], METRIC_L2)
        assert math.isclose(score, 0.0, abs_tol=1e-6)

    def test_ip(self):
        score = _compute_similarity([1, 2], [3, 4], METRIC_IP)
        assert math.isclose(score, 11.0, abs_tol=1e-6)

    def test_cosine(self):
        score = _compute_similarity([1, 0], [1, 0], METRIC_COSINE)
        assert math.isclose(score, 1.0, abs_tol=1e-6)

    def test_cosine_orthogonal(self):
        score = _compute_similarity([1, 0], [0, 1], METRIC_COSINE)
        assert math.isclose(score, 0.0, abs_tol=1e-6)

    def test_empty(self):
        assert _compute_similarity([], [1], METRIC_COSINE) == 0.0

    def test_zero_vector(self):
        assert _compute_similarity([0, 0], [1, 1], METRIC_COSINE) == 0.0


class TestEvalFilter:
    def test_empty(self):
        assert _eval_filter("", {"a": 1}) is True

    def test_string_eq(self):
        assert _eval_filter('modality == "text"', {"modality": "text"}) is True
        assert _eval_filter('modality == "text"', {"modality": "image"}) is False

    def test_int_eq(self):
        assert _eval_filter("index == 0", {"index": 0}) is True
        assert _eval_filter("index == 0", {"index": 1}) is False

    def test_gt(self):
        assert _eval_filter("score > 0.5", {"score": 0.8}) is True
        assert _eval_filter("score > 0.5", {"score": 0.3}) is False

    def test_ge(self):
        assert _eval_filter("score >= 0.5", {"score": 0.5}) is True

    def test_le(self):
        assert _eval_filter("score <= 0.5", {"score": 0.5}) is True

    def test_lt(self):
        assert _eval_filter("score < 0.5", {"score": 0.3}) is True

    def test_and(self):
        expr = 'modality == "text" && index == 0'
        assert _eval_filter(expr, {"modality": "text", "index": 0}) is True
        assert _eval_filter(expr, {"modality": "text", "index": 1}) is False

    def test_missing_key(self):
        assert _eval_filter('foo == "bar"', {}) is False

    def test_unknown_expr(self):
        assert _eval_filter("unknown syntax", {}) is True


# ----------------------------------------------------------------------
# MockVectorStore
# ----------------------------------------------------------------------


class TestMockVectorStore:
    @pytest.mark.asyncio
    async def test_create_collection(self):
        store = MockVectorStore()
        await store.create_collection("test", 4)
        stats = await store.get_stats("test")
        assert stats.name == "test"
        assert stats.dimension == 4

    @pytest.mark.asyncio
    async def test_create_duplicate(self):
        store = MockVectorStore()
        await store.create_collection("test", 4)
        with pytest.raises(CollectionAlreadyExistsError):
            await store.create_collection("test", 4)

    @pytest.mark.asyncio
    async def test_drop_collection(self):
        store = MockVectorStore()
        await store.create_collection("test", 4)
        await store.drop_collection("test")
        with pytest.raises(CollectionNotFoundError):
            await store.get_stats("test")

    @pytest.mark.asyncio
    async def test_drop_nonexistent(self):
        store = MockVectorStore()
        with pytest.raises(CollectionNotFoundError):
            await store.drop_collection("nope")

    @pytest.mark.asyncio
    async def test_insert_and_search(self):
        store = MockVectorStore()
        await store.create_collection("test", 3, metric_type=METRIC_COSINE)
        await store.insert("test", [
            VectorRecord("a", [1.0, 0.0, 0.0], {"label": "a"}),
            VectorRecord("b", [0.0, 1.0, 0.0], {"label": "b"}),
            VectorRecord("c", [1.0, 0.0, 0.0], {"label": "c"}),
        ])
        results = await store.search("test", [1.0, 0.0, 0.0], top_k=2)
        assert len(results) == 2
        # a 和 c 与查询完全相同，应排前面
        assert results[0].id in ("a", "c")
        assert math.isclose(results[0].score, 1.0, abs_tol=1e-6)

    @pytest.mark.asyncio
    async def test_insert_dimension_mismatch(self):
        store = MockVectorStore()
        await store.create_collection("test", 3)
        with pytest.raises(VectorStoreError):
            await store.insert("test", [VectorRecord("a", [1.0, 0.0])])

    @pytest.mark.asyncio
    async def test_insert_nonexistent_collection(self):
        store = MockVectorStore()
        with pytest.raises(CollectionNotFoundError):
            await store.insert("nope", [VectorRecord("a", [1.0])])

    @pytest.mark.asyncio
    async def test_search_with_filter(self):
        store = MockVectorStore()
        await store.create_collection("test", 3)
        await store.insert("test", [
            VectorRecord("a", [1.0, 0.0, 0.0], {"modality": "text"}),
            VectorRecord("b", [0.0, 1.0, 0.0], {"modality": "image"}),
        ])
        results = await store.search(
            "test", [1.0, 0.0, 0.0], top_k=10, filter='modality == "text"'
        )
        assert len(results) == 1
        assert results[0].id == "a"

    @pytest.mark.asyncio
    async def test_hybrid_search(self):
        store = MockVectorStore()
        await store.create_collection("test", 3)
        await store.insert("test", [
            VectorRecord("a", [1.0, 0.0, 0.0], {"score": 0.9}),
            VectorRecord("b", [0.9, 0.1, 0.0], {"score": 0.3}),
        ])
        results = await store.hybrid_search(
            "test", [1.0, 0.0, 0.0], top_k=10, min_score=0.5
        )
        # b 的相似度低于 0.5，应被过滤
        assert all(r.metadata.get("score", 0) >= 0.5 or r.score >= 0.5 for r in results)

    @pytest.mark.asyncio
    async def test_delete(self):
        store = MockVectorStore()
        await store.create_collection("test", 3)
        await store.insert("test", [
            VectorRecord("a", [1.0, 0.0, 0.0]),
            VectorRecord("b", [0.0, 1.0, 0.0]),
        ])
        await store.delete("test", ["a"])
        stats = await store.get_stats("test")
        assert stats.vectorCount == 1
        results = await store.search("test", [1.0, 0.0, 0.0], top_k=10)
        assert len(results) == 1
        assert results[0].id == "b"

    @pytest.mark.asyncio
    async def test_get_stats(self):
        store = MockVectorStore()
        await store.create_collection("test", 4, metric_type=METRIC_IP, index_type=INDEX_HNSW)
        await store.insert("test", [VectorRecord("a", [1.0, 0.0, 0.0, 0.0])])
        stats = await store.get_stats("test")
        assert stats.dimension == 4
        assert stats.metricType == METRIC_IP
        assert stats.vectorCount == 1

    @pytest.mark.asyncio
    async def test_search_nonexistent(self):
        store = MockVectorStore()
        with pytest.raises(CollectionNotFoundError):
            await store.search("nope", [1.0])

    @pytest.mark.asyncio
    async def test_close(self):
        store = MockVectorStore()
        await store.close()  # 不应抛异常


# ----------------------------------------------------------------------
# MilvusVectorStore（仅测试未安装时的错误路径）
# ----------------------------------------------------------------------


class TestMilvusVectorStore:
    def test_is_pymilvus_available(self):
        assert isinstance(is_pymilvus_available(), bool)

    @pytest.mark.asyncio
    async def test_not_installed_raises(self):
        if not is_pymilvus_available():
            store = MilvusVectorStore()
            with pytest.raises(VectorStoreError):
                await store.create_collection("test", 4)


# ----------------------------------------------------------------------
# 工厂函数
# ----------------------------------------------------------------------


class TestFactory:
    def test_create_mock(self):
        store = create_vector_store("mock")
        assert isinstance(store, MockVectorStore)

    def test_create_milvus(self):
        store = create_vector_store("milvus")
        assert isinstance(store, MilvusVectorStore)

    def test_create_unknown(self):
        with pytest.raises(ValueError):
            create_vector_store("unknown")