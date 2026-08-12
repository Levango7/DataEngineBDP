"""检索器测试 (T008-6)."""

from __future__ import annotations

from chunker.embedding.base import EmbeddingAdapter
from chunker.models import Modality
from chunker.rag.exceptions import RetrieveError
from chunker.rag.retriever import RetrievalResult, Retriever
from chunker.rag.vector_store import MockVectorStore, VectorRecord
import pytest


class StubAdapter(EmbeddingAdapter):
    def __init__(self, dimension=4):
        super().__init__("stub", dimension=dimension, normalize=False)

    def _load_backend(self):
        return "stub"

    def _encode(self, texts, backend):
        d = self._declared_dim or 4
        return [[float(len(t))] + [0.0] * (d - 1) for t in texts]


@pytest.fixture
async def setup_store():
    """构造带数据的 store."""
    store = MockVectorStore()
    await store.create_collection("test", 3)
    await store.insert(
        "test",
        [
            VectorRecord("c1", [1.0, 0.0, 0.0], {"modality": "text", "source": "doc1"}),
            VectorRecord("c2", [0.0, 1.0, 0.0], {"modality": "image", "source": "doc2"}),
            VectorRecord("c3", [0.0, 0.0, 1.0], {"modality": "text", "source": "doc3"}),
        ],
    )
    return store


class TestRetrievalResult:
    def test_init(self):
        r = RetrievalResult("c1", 0.9, {"modality": "text"})
        assert r.chunkId == "c1"
        assert r.score == 0.9

    def test_to_chunk(self):
        r = RetrievalResult(
            "c1",
            0.9,
            {
                "modality": "text",
                "source": "doc1",
                "start": 0,
                "end": 10,
                "index": 0,
                "tokens": 5,
            },
        )
        chunk = r.to_chunk()
        assert chunk.id == "c1"
        assert chunk.metadata.modality == Modality.TEXT
        assert chunk.metadata.source == "doc1"

    def test_to_chunk_invalid_modality(self):
        r = RetrievalResult("c1", 0.9, {"modality": "unknown"})
        chunk = r.to_chunk()
        assert chunk.metadata.modality == Modality.TEXT  # 回退

    def test_to_dict(self):
        r = RetrievalResult("c1", 0.9, {"a": 1})
        d = r.to_dict()
        assert d["chunkId"] == "c1"
        assert d["score"] == 0.9


class TestRetriever:
    @pytest.mark.asyncio
    async def test_retrieve_basic(self, setup_store):
        store = setup_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        # 使用预计算向量
        results = await retriever.retrieve("test", "query", top_k=2, query_vector=[1.0, 0.0, 0.0])
        assert len(results) == 2
        assert results[0].chunkId == "c1"

    @pytest.mark.asyncio
    async def test_retrieve_empty_query(self, setup_store):
        store = setup_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        results = await retriever.retrieve("test", "")
        assert results == []

    @pytest.mark.asyncio
    async def test_retrieve_with_filter(self, setup_store):
        store = setup_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        results = await retriever.retrieve(
            "test",
            "query",
            top_k=10,
            filter='modality == "text"',
            query_vector=[1.0, 0.0, 0.0],
        )
        assert all(r.metadata.get("modality") == "text" for r in results)

    @pytest.mark.asyncio
    async def test_retrieve_by_modality(self, setup_store):
        store = setup_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        results = await retriever.retrieve_by_modality(
            "test",
            "query",
            Modality.IMAGE,
            top_k=10,
            query_vector=[0.0, 1.0, 0.0],
        )
        assert all(r.metadata.get("modality") == "image" for r in results)
        assert len(results) == 1

    @pytest.mark.asyncio
    async def test_retrieve_by_modality_str(self, setup_store):
        store = setup_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        results = await retriever.retrieve_by_modality(
            "test",
            "query",
            "text",
            top_k=10,
            query_vector=[1.0, 0.0, 0.0],
        )
        assert all(r.metadata.get("modality") == "text" for r in results)

    @pytest.mark.asyncio
    async def test_retrieve_min_score(self, setup_store):
        store = setup_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        results = await retriever.retrieve(
            "test",
            "query",
            top_k=10,
            min_score=0.99,
            query_vector=[1.0, 0.0, 0.0],
        )
        # 只有完全匹配的 c1 满足 score >= 0.99
        assert all(r.score >= 0.99 for r in results)

    @pytest.mark.asyncio
    async def test_retrieve_multi(self, setup_store):
        store = setup_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        results = await retriever.retrieve_multi(
            "test",
            ["q1", "q2"],
            top_k=2,
        )
        assert len(results) == 2
        assert len(results[0]) <= 2

    @pytest.mark.asyncio
    async def test_retrieve_nonexistent_collection(self):
        store = MockVectorStore()
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter)
        with pytest.raises(RetrieveError):
            await retriever.retrieve("nope", "query", query_vector=[1.0, 0.0, 0.0])

    @pytest.mark.asyncio
    async def test_retrieve_default_top_k(self, setup_store):
        store = setup_store
        adapter = StubAdapter(dimension=3)
        retriever = Retriever(store, adapter, default_top_k=2)
        results = await retriever.retrieve("test", "query", query_vector=[1.0, 0.0, 0.0])
        assert len(results) <= 2
