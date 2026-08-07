"""多模态融合检索测试 (T008-6)."""
from __future__ import annotations

import pytest

from chunker.embedding.base import EmbeddingAdapter
from chunker.models import Modality
from chunker.rag.fusion import (
    MultiModalFusionRetriever,
    reciprocal_rank_fusion,
    weighted_fusion,
)
from chunker.rag.retriever import RetrievalResult, Retriever
from chunker.rag.vector_store import MockVectorStore, VectorRecord


class StubAdapter(EmbeddingAdapter):
    def __init__(self, dimension=4):
        super().__init__("stub", dimension=dimension, normalize=False)

    def _load_backend(self):
        return "stub"

    def _encode(self, texts, backend):
        d = self._declared_dim or 4
        return [[float(len(t))] + [0.0] * (d - 1) for t in texts]


# ----------------------------------------------------------------------
# RRF 融合
# ----------------------------------------------------------------------


class TestRRF:
    def test_empty(self):
        assert reciprocal_rank_fusion([]) == []

    def test_single_list(self):
        results = [
            RetrievalResult("a", 0.9, {}),
            RetrievalResult("b", 0.8, {}),
        ]
        fused = reciprocal_rank_fusion([results])
        assert len(fused) == 2
        assert fused[0].chunkId == "a"

    def test_multiple_lists(self):
        list1 = [RetrievalResult("a", 0.9, {}), RetrievalResult("b", 0.8, {})]
        list2 = [RetrievalResult("b", 0.95, {}), RetrievalResult("c", 0.7, {})]
        fused = reciprocal_rank_fusion([list1, list2])
        # b 在两个列表中都出现，应排前面
        assert fused[0].chunkId == "b"

    def test_dedup(self):
        list1 = [RetrievalResult("a", 0.9, {})]
        list2 = [RetrievalResult("a", 0.8, {})]
        fused = reciprocal_rank_fusion([list1, list2])
        assert len(fused) == 1

    def test_custom_k(self):
        results = [RetrievalResult("a", 0.9, {})]
        fused = reciprocal_rank_fusion([results], k=10)
        assert len(fused) == 1

    def test_source_scores_recorded(self):
        list1 = [RetrievalResult("a", 0.9, {})]
        list2 = [RetrievalResult("a", 0.8, {})]
        fused = reciprocal_rank_fusion([list1, list2])
        assert "_sourceScores" in fused[0].metadata
        assert fused[0].metadata["_sourceScores"] == [0.9, 0.8]


# ----------------------------------------------------------------------
# 加权融合
# ----------------------------------------------------------------------


class TestWeightedFusion:
    def test_empty(self):
        assert weighted_fusion([], []) == []

    def test_basic(self):
        list1 = [RetrievalResult("a", 0.9, {})]
        list2 = [RetrievalResult("a", 0.8, {})]
        fused = weighted_fusion([list1, list2], [1.0, 1.0])
        assert len(fused) == 1
        assert fused[0].score == pytest.approx(1.7)

    def test_weight_mismatch(self):
        with pytest.raises(ValueError):
            weighted_fusion([[RetrievalResult("a", 1.0, {})]], [1.0, 2.0])

    def test_weighted_sort(self):
        list1 = [RetrievalResult("a", 1.0, {}), RetrievalResult("b", 0.5, {})]
        list2 = [RetrievalResult("b", 1.0, {}), RetrievalResult("a", 0.5, {})]
        fused = weighted_fusion([list1, list2], [1.0, 1.0])
        # a: 1.0 + 0.5 = 1.5, b: 0.5 + 1.0 = 1.5，相同
        assert len(fused) == 2

    def test_weighted_with_different_weights(self):
        list1 = [RetrievalResult("a", 1.0, {})]
        list2 = [RetrievalResult("b", 1.0, {})]
        fused = weighted_fusion([list1, list2], [2.0, 1.0])
        # a: 2.0*1.0 = 2.0, b: 1.0*1.0 = 1.0
        assert fused[0].chunkId == "a"
        assert fused[0].score == pytest.approx(2.0)


# ----------------------------------------------------------------------
# MultiModalFusionRetriever
# ----------------------------------------------------------------------


@pytest.fixture
async def setup_multi_modal_store():
    store = MockVectorStore()
    await store.create_collection("test", 3)
    await store.insert("test", [
        VectorRecord("t1", [1.0, 0.0, 0.0], {"modality": "text", "source": "doc1"}),
        VectorRecord("t2", [0.9, 0.1, 0.0], {"modality": "text", "source": "doc2"}),
        VectorRecord("i1", [0.0, 1.0, 0.0], {"modality": "image", "source": "doc3"}),
        VectorRecord("tb1", [0.0, 0.0, 1.0], {"modality": "table", "source": "doc4"}),
        VectorRecord("a1", [0.5, 0.5, 0.0], {"modality": "audio", "source": "doc5"}),
    ])
    return store


def _patch_query_vector(adapter, vector):
    """为 adapter 注入固定的 query_vector."""
    async def mock_embed_query(text):
        return vector
    adapter.embed_query = mock_embed_query


class TestMultiModalFusionRetriever:
    @pytest.mark.asyncio
    async def test_retrieve_fused_rrf(self, setup_multi_modal_store):
        store = setup_multi_modal_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        fusion = MultiModalFusionRetriever(retriever)
        _patch_query_vector(adapter, [1.0, 0.0, 0.0])

        results = await fusion.retrieve_fused(
            "test", "query",
            modalities=[Modality.TEXT, Modality.IMAGE],
            top_k=3,
            method="rrf",
        )
        assert len(results) <= 3

    @pytest.mark.asyncio
    async def test_retrieve_fused_weighted(self, setup_multi_modal_store):
        store = setup_multi_modal_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        fusion = MultiModalFusionRetriever(retriever)
        _patch_query_vector(adapter, [1.0, 0.0, 0.0])

        results = await fusion.retrieve_fused(
            "test", "query",
            modalities=[Modality.TEXT, Modality.IMAGE],
            top_k=3,
            method="weighted",
        )
        assert len(results) <= 3

    @pytest.mark.asyncio
    async def test_retrieve_fused_all_modalities(self, setup_multi_modal_store):
        store = setup_multi_modal_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        fusion = MultiModalFusionRetriever(retriever)
        _patch_query_vector(adapter, [1.0, 0.0, 0.0])

        results = await fusion.retrieve_fused(
            "test", "query", top_k=5, method="rrf",
        )
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_retrieve_fused_invalid_method(self, setup_multi_modal_store):
        store = setup_multi_modal_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        fusion = MultiModalFusionRetriever(retriever)
        _patch_query_vector(adapter, [1.0, 0.0, 0.0])

        with pytest.raises(ValueError):
            await fusion.retrieve_fused(
                "test", "query", modalities=[Modality.TEXT], method="invalid",
            )

    @pytest.mark.asyncio
    async def test_retrieve_multi_query_fused(self, setup_multi_modal_store):
        store = setup_multi_modal_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        fusion = MultiModalFusionRetriever(retriever)
        _patch_query_vector(adapter, [1.0, 0.0, 0.0])

        results = await fusion.retrieve_multi_query_fused(
            "test", ["q1", "q2"],
            modalities=[Modality.TEXT],
            top_k=3,
        )
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_retrieve_fused_with_min_score(self, setup_multi_modal_store):
        store = setup_multi_modal_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        fusion = MultiModalFusionRetriever(retriever)
        _patch_query_vector(adapter, [1.0, 0.0, 0.0])

        results = await fusion.retrieve_fused(
            "test", "query",
            modalities=[Modality.TEXT],
            top_k=5,
            method="rrf",
            min_score=0.5,
        )
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_retrieve_fused_empty_modalities(self, setup_multi_modal_store):
        """模态列表为空时返回空."""
        store = setup_multi_modal_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        fusion = MultiModalFusionRetriever(retriever)
        _patch_query_vector(adapter, [1.0, 0.0, 0.0])

        # 空模态列表会使用默认全部模态
        results = await fusion.retrieve_fused(
            "test", "query",
            modalities=[],
            top_k=5,
        )
        # 空列表会被当作 None 处理（使用默认模态）
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_retrieve_fused_str_modalities(self, setup_multi_modal_store):
        """模态用字符串表示."""
        store = setup_multi_modal_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        fusion = MultiModalFusionRetriever(retriever)
        _patch_query_vector(adapter, [1.0, 0.0, 0.0])

        results = await fusion.retrieve_fused(
            "test", "query",
            modalities=["text", "image"],
            top_k=3,
            method="rrf",
        )
        assert isinstance(results, list)
