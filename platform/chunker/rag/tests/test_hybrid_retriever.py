"""混合检索与重排序测试 (T009)."""
from __future__ import annotations

import json
from typing import Any

import pytest

from chunker.embedding.base import EmbeddingAdapter
from chunker.models import Chunk, ChunkMetadata, Modality
from chunker.rag.hybrid_retriever import (
    BM25Index,
    BM25Retriever,
    CrossEncoderReranker,
    DEFAULT_BM25_B,
    DEFAULT_BM25_K1,
    DEFAULT_CHANNEL_WEIGHTS,
    DEFAULT_EXPAND_SYNONYMS,
    HybridRetrievalResult,
    HybridRetriever,
    IdentityReranker,
    KnowledgeGraph,
    LLMReranker,
    MockKnowledgeGraph,
    Reranker,
    create_reranker,
    tokenize,
)
from chunker.rag.retriever import RetrievalResult, Retriever
from chunker.rag.vector_store import MockVectorStore, VectorRecord


# ----------------------------------------------------------------------
# 测试用 stub
# ----------------------------------------------------------------------


class StubAdapter(EmbeddingAdapter):
    """确定性 stub embedding 适配器."""

    def __init__(self, dimension: int = 4):
        super().__init__("stub", dimension=dimension, normalize=False)

    def _load_backend(self):
        return "stub"

    def _encode(self, texts, backend):
        d = self._declared_dim or 4
        # 用首字符编码做确定性向量
        results = []
        for t in texts:
            base = float(len(t))
            vec = [base] + [0.0] * (d - 1)
            results.append(vec)
        return results


def _patch_query_vector(adapter: EmbeddingAdapter, vector: list[float]) -> None:
    """为 adapter 注入固定的 query_vector."""

    async def mock_embed_query(text: str) -> list[float]:
        return vector

    adapter.embed_query = mock_embed_query  # type: ignore[method-assign]


# ----------------------------------------------------------------------
# tokenize
# ----------------------------------------------------------------------


class TestTokenize:
    def test_empty(self):
        assert tokenize("") == []

    def test_english(self):
        toks = tokenize("Hello World 123")
        assert "hello" in toks
        assert "world" in toks
        assert "123" in toks

    def test_no_lowercase(self):
        toks = tokenize("Hello", lowercase=False)
        assert toks == ["Hello"]

    def test_chinese(self):
        toks = tokenize("知识图谱", lowercase=False)
        assert toks == ["知", "识", "图", "谱"]

    def test_mixed(self):
        toks = tokenize("RAG 检索")
        assert "rag" in toks
        assert "检" in toks
        assert "索" in toks

    def test_punctuation_only(self):
        assert tokenize("!!! ???") == []


# ----------------------------------------------------------------------
# BM25Index
# ----------------------------------------------------------------------


class TestBM25Index:
    def test_empty_search(self):
        idx = BM25Index()
        assert idx.search("foo") == []
        assert idx.docCount == 0
        assert idx.avgDocLen == 0.0

    def test_add_and_search(self):
        idx = BM25Index()
        idx.add_doc("d1", "the quick brown fox")
        idx.add_doc("d2", "the lazy dog")
        hits = idx.search("fox", top_k=2)
        assert len(hits) == 1
        assert hits[0][0] == "d1"
        assert hits[0][1] > 0

    def test_search_empty_query(self):
        idx = BM25Index()
        idx.add_doc("d1", "hello world")
        assert idx.search("") == []

    def test_search_no_match(self):
        idx = BM25Index()
        idx.add_doc("d1", "hello world")
        assert idx.search("foobar") == []

    def test_doc_count_and_avg_len(self):
        idx = BM25Index()
        idx.add_doc("d1", "a b c")
        idx.add_doc("d2", "a b")
        assert idx.docCount == 2
        assert idx.avgDocLen == 2.5

    def test_remove_doc(self):
        idx = BM25Index()
        idx.add_doc("d1", "hello world")
        idx.add_doc("d2", "hello foo")
        idx.remove_doc("d1")
        assert idx.docCount == 1
        hits = idx.search("world")
        assert hits == []

    def test_remove_nonexistent(self):
        idx = BM25Index()
        idx.remove_doc("nope")  # 不报错

    def test_update_doc(self):
        idx = BM25Index()
        idx.add_doc("d1", "hello world")
        idx.add_doc("d1", "foo bar")  # 覆盖
        assert idx.docCount == 1
        assert idx.search("world") == []
        assert idx.search("foo")[0][0] == "d1"

    def test_clear(self):
        idx = BM25Index()
        idx.add_doc("d1", "hello")
        idx.add_doc("d2", "world")
        idx.clear()
        assert idx.docCount == 0
        assert idx.search("hello") == []

    def test_custom_k1_b(self):
        idx = BM25Index(k1=2.0, b=0.5)
        idx.add_doc("d1", "machine learning")
        idx.add_doc("d2", "deep learning")
        hits = idx.search("learning", top_k=2)
        assert len(hits) == 2

    def test_no_lowercase(self):
        idx = BM25Index(lowercase=False)
        idx.add_doc("d1", "Python")
        hits = idx.search("python")
        assert hits == []
        hits = idx.search("Python")
        assert len(hits) == 1

    def test_top_k_limit(self):
        idx = BM25Index()
        for i in range(5):
            idx.add_doc(f"d{i}", f"common word{i}")
        hits = idx.search("common", top_k=2)
        assert len(hits) == 2

    def test_idf_weighting(self):
        """稀有词应得分更高."""
        idx = BM25Index()
        idx.add_doc("d1", "common rare")
        idx.add_doc("d2", "common")
        idx.add_doc("d3", "common")
        hits_rare = idx.search("rare")
        hits_common = idx.search("common")
        # rare 只在 d1 出现，得分应高于 common 在 d1 的得分
        assert hits_rare[0][1] > hits_common[0][1]


# ----------------------------------------------------------------------
# BM25Retriever
# ----------------------------------------------------------------------


class TestBM25Retriever:
    @pytest.mark.asyncio
    async def test_retrieve_basic(self):
        bm25 = BM25Retriever()
        bm25.add_doc("c1", "知识图谱增强检索", {"modality": "text"})
        bm25.add_doc("c2", "向量数据库 Milvus", {"modality": "text"})
        results = await bm25.retrieve("any", "知识图谱", top_k=2)
        assert len(results) >= 1
        assert results[0].chunkId == "c1"
        assert results[0].metadata["modality"] == "text"

    @pytest.mark.asyncio
    async def test_retrieve_empty_query(self):
        bm25 = BM25Retriever()
        bm25.add_doc("c1", "hello")
        results = await bm25.retrieve("any", "")
        assert results == []

    @pytest.mark.asyncio
    async def test_retrieve_no_match(self):
        bm25 = BM25Retriever()
        bm25.add_doc("c1", "hello world")
        results = await bm25.retrieve("any", "foobar")
        assert results == []

    def test_add_chunks(self):
        bm25 = BM25Retriever()
        chunks = [
            Chunk(
                id="c1",
                content="知识图谱",
                metadata=ChunkMetadata(modality=Modality.TEXT, source="doc1"),
            ),
            Chunk(
                id="c2",
                content="向量检索",
                metadata=ChunkMetadata(modality=Modality.TEXT, source="doc2"),
            ),
        ]
        count = bm25.add_chunks(chunks)
        assert count == 2
        assert bm25.index.docCount == 2

    def test_add_chunks_with_dict_content(self):
        bm25 = BM25Retriever()
        chunk = Chunk(
            id="c1",
            content={"col": ["a", "b"]},
            metadata=ChunkMetadata(modality=Modality.TABLE, source="t1"),
        )
        bm25.add_chunks([chunk])
        assert bm25.index.docCount == 1

    def test_remove_doc(self):
        bm25 = BM25Retriever()
        bm25.add_doc("c1", "hello", {"a": 1})
        bm25.remove_doc("c1")
        assert bm25.index.docCount == 0
        assert "c1" not in bm25._metaStore

    def test_clear(self):
        bm25 = BM25Retriever()
        bm25.add_doc("c1", "hello")
        bm25.clear()
        assert bm25.index.docCount == 0

    @pytest.mark.asyncio
    async def test_custom_index(self):
        idx = BM25Index(k1=2.0)
        bm25 = BM25Retriever(index=idx)
        bm25.add_doc("c1", "test doc")
        results = await bm25.retrieve("", "test")
        assert len(results) == 1

    @pytest.mark.asyncio
    async def test_metadata_store(self):
        bm25 = BM25Retriever()
        bm25.add_doc("c1", "hello", {"source": "doc1"})
        results = await bm25.retrieve("", "hello")
        assert results[0].metadata["source"] == "doc1"


# ----------------------------------------------------------------------
# MockKnowledgeGraph
# ----------------------------------------------------------------------


class TestMockKnowledgeGraph:
    @pytest.mark.asyncio
    async def test_link_entities_basic(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", label="Person", name="张三", aliases=["老张"])
        ents = await kg.link_entities("张三是谁", top_k=5)
        assert len(ents) == 1
        assert ents[0]["id"] == "e1"

    @pytest.mark.asyncio
    async def test_link_entities_by_alias(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="张三", aliases=["老张", "Zhang San"])
        ents = await kg.link_entities("老张的年龄")
        assert len(ents) == 1
        assert ents[0]["id"] == "e1"

    @pytest.mark.asyncio
    async def test_link_entities_empty_query(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="张三")
        assert await kg.link_entities("") == []

    @pytest.mark.asyncio
    async def test_link_entities_top_k(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="甲")
        kg.add_entity("e2", name="乙")
        kg.add_entity("e3", name="丙")
        ents = await kg.link_entities("甲乙丙", top_k=2)
        assert len(ents) == 2

    @pytest.mark.asyncio
    async def test_link_entities_no_match(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="张三")
        assert await kg.link_entities("李四") == []

    @pytest.mark.asyncio
    async def test_neighbor_chunks(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="张三", chunk_ids=["c1", "c2"])
        kg.add_entity("e2", name="李四", chunk_ids=["c2", "c3"])
        ents = await kg.link_entities("张三李四")
        chunks = await kg.neighbor_chunks(ents, top_k=10)
        assert "c1" in chunks
        assert "c2" in chunks
        assert "c3" in chunks

    @pytest.mark.asyncio
    async def test_neighbor_chunks_top_k(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="甲", chunk_ids=["c1", "c2", "c3"])
        ents = await kg.link_entities("甲")
        chunks = await kg.neighbor_chunks(ents, top_k=2)
        assert len(chunks) == 2

    @pytest.mark.asyncio
    async def test_neighbor_chunks_empty(self):
        kg = MockKnowledgeGraph()
        chunks = await kg.neighbor_chunks([])
        assert chunks == []

    @pytest.mark.asyncio
    async def test_expand_query(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="知识图谱", aliases=["KG", "Knowledge Graph"])
        ents = await kg.link_entities("知识图谱")
        expansions = await kg.expand_query("知识图谱", ents)
        assert "KG" in expansions
        assert "Knowledge Graph" in expansions

    @pytest.mark.asyncio
    async def test_expand_query_excludes_name_and_query(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="AI", aliases=["AI", "人工智能"])
        ents = [{"id": "e1", "name": "AI", "aliases": ["AI", "人工智能"]}]
        expansions = await kg.expand_query("AI", ents)
        # "AI" 是 name 也在 query 中，应排除
        assert "AI" not in expansions
        assert "人工智能" in expansions

    @pytest.mark.asyncio
    async def test_expand_query_max_synonyms(self):
        kg = MockKnowledgeGraph()
        ents = [
            {
                "id": "e1",
                "name": "X",
                "aliases": ["a1", "a2", "a3", "a4"],
            }
        ]
        expansions = await kg.expand_query("X", ents, max_synonyms=2)
        assert len(expansions) == 2

    def test_remove_entity(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="张三", aliases=["老张"])
        kg.remove_entity("e1")
        assert kg.entityCount == 0
        # 别名索引也应清除
        assert "张三" not in kg._aliasIndex

    def test_remove_nonexistent(self):
        kg = MockKnowledgeGraph()
        kg.remove_entity("nope")  # 不报错

    def test_clear(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="张三")
        kg.clear()
        assert kg.entityCount == 0

    def test_update_entity(self):
        kg = MockKnowledgeGraph()
        kg.add_entity("e1", name="张三", aliases=["老张"])
        kg.add_entity("e1", name="李四", aliases=["老李"])
        # 旧别名应清除
        assert kg._aliasIndex.get("张三") is None
        assert kg._aliasIndex.get("老张") is None
        assert kg._aliasIndex.get("李四") == "e1"

    def test_entity_count(self):
        kg = MockKnowledgeGraph()
        assert kg.entityCount == 0
        kg.add_entity("e1", name="甲")
        kg.add_entity("e2", name="乙")
        assert kg.entityCount == 2


# ----------------------------------------------------------------------
# Rerankers
# ----------------------------------------------------------------------


class TestIdentityReranker:
    @pytest.mark.asyncio
    async def test_rerank_no_top_k(self):
        r = IdentityReranker()
        results = [
            RetrievalResult("a", 0.9, {}),
            RetrievalResult("b", 0.8, {}),
        ]
        out = await r.rerank("q", results)
        assert len(out) == 2
        assert out[0].chunkId == "a"

    @pytest.mark.asyncio
    async def test_rerank_with_top_k(self):
        r = IdentityReranker()
        results = [RetrievalResult(f"c{i}", 0.9 - i * 0.1, {}) for i in range(5)]
        out = await r.rerank("q", results, top_k=2)
        assert len(out) == 2

    @pytest.mark.asyncio
    async def test_rerank_empty(self):
        r = IdentityReranker()
        assert await r.rerank("q", []) == []

    def test_name(self):
        assert IdentityReranker().name == "identity"


class TestCrossEncoderReranker:
    @pytest.mark.asyncio
    async def test_rerank_basic(self):
        async def score_fn(q: str, doc: str) -> float:
            return 1.0 if q in doc else 0.0

        r = CrossEncoderReranker(score_fn)
        results = [
            RetrievalResult("a", 0.5, {"content": "hello world"}),
            RetrievalResult("b", 0.6, {"content": "foo bar"}),
        ]
        out = await r.rerank("hello", results, top_k=2)
        assert out[0].chunkId == "a"
        assert out[0].metadata["reranker"] == "cross_encoder"
        assert "rerankScore" in out[0].metadata

    @pytest.mark.asyncio
    async def test_rerank_empty(self):
        async def score_fn(q, d):
            return 0.0

        r = CrossEncoderReranker(score_fn)
        assert await r.rerank("q", []) == []

    @pytest.mark.asyncio
    async def test_score_fn_exception(self):
        async def score_fn(q, d):
            raise RuntimeError("boom")

        r = CrossEncoderReranker(score_fn)
        results = [RetrievalResult("a", 0.5, {"content": "x"})]
        out = await r.rerank("q", results)
        # 应回退到原分数
        assert len(out) == 1
        assert out[0].score == 0.5

    @pytest.mark.asyncio
    async def test_normalize(self):
        async def score_fn(q, d):
            return float(len(d))

        r = CrossEncoderReranker(score_fn, normalize=True)
        results = [
            RetrievalResult("a", 0.5, {"content": "ab"}),
            RetrievalResult("b", 0.5, {"content": "abcd"}),
        ]
        out = await r.rerank("q", results)
        # 归一化后 b(4) 应为 1.0，a(2) 应为 0.0
        assert out[0].chunkId == "b"
        assert out[0].score == pytest.approx(1.0)
        assert out[1].score == pytest.approx(0.0)

    @pytest.mark.asyncio
    async def test_top_k(self):
        async def score_fn(q, d):
            return float(len(d))

        r = CrossEncoderReranker(score_fn)
        results = [
            RetrievalResult(f"c{i}", 0.5, {"content": "x" * (i + 1)})
            for i in range(5)
        ]
        out = await r.rerank("q", results, top_k=2)
        assert len(out) == 2

    def test_name(self):
        async def score_fn(q, d):
            return 0.0

        assert CrossEncoderReranker(score_fn).name == "cross_encoder"


class TestLLMReranker:
    @pytest.mark.asyncio
    async def test_rerank_basic(self):
        async def llm_fn(q, cands):
            # 返回与候选文本长度成正比的分数
            return [float(len(c["text"])) for c in cands]

        r = LLMReranker(llm_fn)
        results = [
            RetrievalResult("a", 0.5, {"content": "ab"}),
            RetrievalResult("b", 0.5, {"content": "abcd"}),
        ]
        out = await r.rerank("q", results)
        assert out[0].chunkId == "b"
        assert out[0].metadata["reranker"] == "llm"

    @pytest.mark.asyncio
    async def test_rerank_empty(self):
        async def llm_fn(q, cands):
            return []

        r = LLMReranker(llm_fn)
        assert await r.rerank("q", []) == []

    @pytest.mark.asyncio
    async def test_llm_exception(self):
        async def llm_fn(q, cands):
            raise RuntimeError("llm error")

        r = LLMReranker(llm_fn)
        results = [RetrievalResult("a", 0.7, {"content": "x"})]
        out = await r.rerank("q", results)
        assert len(out) == 1
        assert out[0].score == 0.7  # 回退原分数

    @pytest.mark.asyncio
    async def test_llm_wrong_count(self):
        async def llm_fn(q, cands):
            return [1.0]  # 只返回 1 个，但候选 2 个

        r = LLMReranker(llm_fn)
        results = [
            RetrievalResult("a", 0.5, {"content": "x"}),
            RetrievalResult("b", 0.6, {"content": "y"}),
        ]
        out = await r.rerank("q", results)
        # 应回退原分数
        assert len(out) == 2

    @pytest.mark.asyncio
    async def test_batch(self):
        call_count = 0

        async def llm_fn(q, cands):
            nonlocal call_count
            call_count += 1
            return [1.0] * len(cands)

        r = LLMReranker(llm_fn, batch_size=2)
        results = [
            RetrievalResult(f"c{i}", 0.5, {"content": "x"})
            for i in range(5)
        ]
        await r.rerank("q", results)
        # 5 个候选，batch=2，应调用 3 次
        assert call_count == 3

    @pytest.mark.asyncio
    async def test_top_k(self):
        async def llm_fn(q, cands):
            return [1.0] * len(cands)

        r = LLMReranker(llm_fn)
        results = [
            RetrievalResult(f"c{i}", 0.5, {"content": "x"})
            for i in range(5)
        ]
        out = await r.rerank("q", results, top_k=2)
        assert len(out) == 2

    def test_name(self):
        async def llm_fn(q, c):
            return []

        assert LLMReranker(llm_fn).name == "llm"


class TestCreateReranker:
    def test_identity(self):
        r = create_reranker("identity")
        assert isinstance(r, IdentityReranker)

    def test_cross_encoder(self):
        async def fn(q, d):
            return 0.0

        r = create_reranker("cross_encoder", score_fn=fn)
        assert isinstance(r, CrossEncoderReranker)

    def test_cross_encoder_no_fn(self):
        with pytest.raises(ValueError):
            create_reranker("cross_encoder")

    def test_llm(self):
        async def fn(q, c):
            return []

        r = create_reranker("llm", llm_score_fn=fn)
        assert isinstance(r, LLMReranker)

    def test_llm_no_fn(self):
        with pytest.raises(ValueError):
            create_reranker("llm")

    def test_unknown(self):
        with pytest.raises(ValueError):
            create_reranker("unknown")


# ----------------------------------------------------------------------
# HybridRetrievalResult
# ----------------------------------------------------------------------


class TestHybridRetrievalResult:
    def test_init(self):
        r = HybridRetrievalResult(
            [RetrievalResult("a", 0.9, {})],
            channel_results={"vector": [RetrievalResult("a", 0.9, {})]},
            expanded_queries=["q2"],
            linked_entities=[{"id": "e1"}],
            fused_method="rrf",
            reranker_name="identity",
        )
        assert r.topK == 1
        assert len(r) == 1
        assert r.fusedMethod == "rrf"
        assert r.rerankerName == "identity"

    def test_defaults(self):
        r = HybridRetrievalResult([])
        assert r.topK == 0
        assert r.channelResults == {}
        assert r.expandedQueries == []
        assert r.linkedEntities == []
        assert r.fusedMethod == "rrf"
        assert r.rerankerName == "identity"

    def test_to_dict(self):
        r = HybridRetrievalResult(
            [RetrievalResult("a", 0.9, {"k": "v"})],
            channel_results={"vector": [RetrievalResult("a", 0.9, {})]},
            expanded_queries=["q2"],
            linked_entities=[{"id": "e1"}],
        )
        d = r.to_dict()
        assert d["results"][0]["chunkId"] == "a"
        assert "vector" in d["channelResults"]
        assert d["expandedQueries"] == ["q2"]
        assert d["linkedEntities"] == [{"id": "e1"}]

    def test_iter(self):
        r = HybridRetrievalResult(
            [RetrievalResult("a", 0.9, {}), RetrievalResult("b", 0.8, {})]
        )
        ids = [x.chunkId for x in r]
        assert ids == ["a", "b"]


# ----------------------------------------------------------------------
# HybridRetriever
# ----------------------------------------------------------------------


@pytest.fixture
async def hybrid_setup():
    """构造带向量+BM25+KG 的混合检索环境."""
    store = MockVectorStore()
    await store.create_collection("test", 3)
    await store.insert(
        "test",
        [
            VectorRecord(
                "c1",
                [1.0, 0.0, 0.0],
                {
                    "modality": "text",
                    "source": "doc1",
                    "content": "知识图谱增强检索",
                },
            ),
            VectorRecord(
                "c2",
                [0.9, 0.1, 0.0],
                {
                    "modality": "text",
                    "source": "doc2",
                    "content": "向量数据库 Milvus",
                },
            ),
            VectorRecord(
                "c3",
                [0.0, 1.0, 0.0],
                {
                    "modality": "text",
                    "source": "doc3",
                    "content": "BM25 关键词检索",
                },
            ),
        ],
    )

    adapter = StubAdapter(dimension=3)
    _patch_query_vector(adapter, [1.0, 0.0, 0.0])
    retriever = Retriever(store, adapter)

    bm25 = BM25Retriever()
    bm25.add_doc("c1", "知识图谱增强检索", {"modality": "text", "content": "知识图谱增强检索"})
    bm25.add_doc("c2", "向量数据库 Milvus", {"modality": "text", "content": "向量数据库 Milvus"})
    bm25.add_doc("c3", "BM25 关键词检索", {"modality": "text", "content": "BM25 关键词检索"})

    kg = MockKnowledgeGraph()
    kg.add_entity(
        "e1",
        name="知识图谱",
        aliases=["KG", "Knowledge Graph"],
        chunk_ids=["c1", "c2"],
    )

    return retriever, bm25, kg


class TestHybridRetriever:
    @pytest.mark.asyncio
    async def test_retrieve_vector_only(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=2, channels=["vector"]
        )
        assert isinstance(result, HybridRetrievalResult)
        assert result.topK <= 2
        assert "vector" in result.channelResults

    @pytest.mark.asyncio
    async def test_retrieve_keyword_only(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=2, channels=["keyword"]
        )
        assert "keyword" in result.channelResults
        assert result.topK <= 2

    @pytest.mark.asyncio
    async def test_retrieve_kg_only(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=2, channels=["kg"]
        )
        assert "kg" in result.channelResults
        # KG 通过实体链接召回 c1, c2
        assert result.topK >= 1

    @pytest.mark.asyncio
    async def test_retrieve_all_channels(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=5, channels=["vector", "keyword", "kg"]
        )
        assert len(result.channelResults) == 3

    @pytest.mark.asyncio
    async def test_retrieve_default_channels(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve("test", "知识图谱", top_k=5)
        # 默认全部通道
        assert len(result.channelResults) == 3

    @pytest.mark.asyncio
    async def test_retrieve_weighted_fusion(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test",
            "知识图谱",
            top_k=3,
            method="weighted",
            channels=["vector", "keyword"],
        )
        assert result.fusedMethod == "weighted"

    @pytest.mark.asyncio
    async def test_retrieve_invalid_method(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        with pytest.raises(ValueError):
            await hybrid.retrieve(
                "test", "知识图谱", method="invalid"
            )

    @pytest.mark.asyncio
    async def test_retrieve_empty_query(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve("test", "")
        assert result.topK == 0

    @pytest.mark.asyncio
    async def test_retrieve_with_rerank(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup

        async def score_fn(q, doc):
            return 1.0 if "知识" in doc else 0.0

        reranker = CrossEncoderReranker(score_fn)
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg, reranker=reranker)
        result = await hybrid.retrieve(
            "test",
            "知识图谱",
            top_k=3,
            rerank=True,
            channels=["vector", "keyword"],
        )
        assert result.rerankerName == "cross_encoder"
        for r in result.results:
            assert "rerankScore" in r.metadata

    @pytest.mark.asyncio
    async def test_retrieve_no_rerank(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=3, rerank=False
        )
        assert result.rerankerName == "identity"

    @pytest.mark.asyncio
    async def test_retrieve_with_min_score(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test",
            "知识图谱",
            top_k=5,
            min_score=0.0,  # 极低阈值，应不过滤
            channels=["vector"],
        )
        assert isinstance(result, HybridRetrievalResult)

    @pytest.mark.asyncio
    async def test_retrieve_with_high_min_score(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test",
            "知识图谱",
            top_k=5,
            min_score=999.0,  # 极高阈值，应全部过滤
            channels=["vector"],
        )
        assert result.topK == 0

    @pytest.mark.asyncio
    async def test_kg_query_expansion(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(
            retriever, bm25=bm25, kg=kg, enable_query_expansion=True
        )
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=5, channels=["vector", "kg"]
        )
        # 应有扩展查询
        assert len(result.expandedQueries) >= 1
        assert len(result.linkedEntities) >= 1

    @pytest.mark.asyncio
    async def test_kg_query_expansion_disabled(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(
            retriever, bm25=bm25, kg=kg, enable_query_expansion=False
        )
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=5, channels=["vector", "kg"]
        )
        assert result.expandedQueries == []

    @pytest.mark.asyncio
    async def test_no_bm25(self, hybrid_setup):
        retriever, _, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=None, kg=kg)
        result = await hybrid.retrieve("test", "知识图谱", top_k=3)
        # 默认通道应只有 vector 和 kg
        assert "keyword" not in result.channelResults

    @pytest.mark.asyncio
    async def test_no_kg(self, hybrid_setup):
        retriever, bm25, _ = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=None)
        result = await hybrid.retrieve("test", "知识图谱", top_k=3)
        assert "kg" not in result.channelResults

    @pytest.mark.asyncio
    async def test_invalid_channel_ignored(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=3, channels=["vector", "unknown"]
        )
        assert "vector" in result.channelResults
        assert "unknown" not in result.channelResults

    @pytest.mark.asyncio
    async def test_keyword_channel_without_bm25(self, hybrid_setup):
        """请求 keyword 通道但未配置 bm25，应忽略."""
        retriever, _, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=None, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=3, channels=["keyword"]
        )
        assert result.topK == 0

    @pytest.mark.asyncio
    async def test_kg_channel_without_kg(self, hybrid_setup):
        """请求 kg 通道但未配置 kg，应忽略."""
        retriever, bm25, _ = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=None)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=3, channels=["kg"]
        )
        assert result.topK == 0

    @pytest.mark.asyncio
    async def test_retrieve_multi(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        results = await hybrid.retrieve_multi(
            "test", ["知识图谱", "BM25"], top_k=3
        )
        assert len(results) == 2
        assert all(isinstance(r, HybridRetrievalResult) for r in results)

    @pytest.mark.asyncio
    async def test_vector_failure_tolerated(self, hybrid_setup):
        """向量检索失败不应阻断其他通道.

        _retrieve_vector 内部捕获异常并返回空列表（支持扩展查询部分失败容错），
        因此 vector 通道仍出现在 channelResults 中但结果为空。
        """
        retriever, bm25, kg = hybrid_setup

        # 注入失败的 retrieve
        async def fail_retrieve(*args, **kwargs):
            raise RuntimeError("vector store down")

        retriever.retrieve = fail_retrieve  # type: ignore[method-assign]

        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=3, channels=["vector", "keyword"]
        )
        # keyword 应仍工作并有结果
        assert "keyword" in result.channelResults
        assert len(result.channelResults["keyword"]) >= 1
        # vector 通道被尝试但所有查询失败，结果为空
        assert result.channelResults.get("vector", []) == []

    @pytest.mark.asyncio
    async def test_kg_link_failure_tolerated(self, hybrid_setup):
        """KG 链接失败不应阻断."""
        retriever, bm25, kg = hybrid_setup

        async def fail_link(query, **kwargs):
            raise RuntimeError("kg down")

        kg.link_entities = fail_link  # type: ignore[method-assign]

        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=3, channels=["vector", "kg"]
        )
        # KG 链接失败，但 vector 应仍工作
        assert "vector" in result.channelResults

    @pytest.mark.asyncio
    async def test_reranker_failure_tolerated(self, hybrid_setup):
        """重排序失败应回退到融合结果."""
        retriever, bm25, kg = hybrid_setup

        class BadReranker(Reranker):
            async def rerank(self, query, results, *, top_k=None):
                raise RuntimeError("rerank failed")

            @property
            def name(self) -> str:
                return "bad"

        hybrid = HybridRetriever(
            retriever, bm25=bm25, kg=kg, reranker=BadReranker()
        )
        result = await hybrid.retrieve(
            "test", "知识图谱", top_k=3, channels=["vector"]
        )
        # 应有结果（回退到融合）
        assert isinstance(result, HybridRetrievalResult)

    @pytest.mark.asyncio
    async def test_channel_weights(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(
            retriever,
            bm25=bm25,
            kg=kg,
            channel_weights={"vector": 2.0, "keyword": 1.0, "kg": 0.5},
        )
        result = await hybrid.retrieve(
            "test",
            "知识图谱",
            top_k=3,
            method="weighted",
            channels=["vector", "keyword"],
        )
        assert result.fusedMethod == "weighted"

    @pytest.mark.asyncio
    async def test_filter_passed_to_vector(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test",
            "知识图谱",
            top_k=3,
            channels=["vector"],
            filter='modality == "text"',
        )
        assert isinstance(result, HybridRetrievalResult)

    @pytest.mark.asyncio
    async def test_kg_no_linked_entities(self, hybrid_setup):
        """查询不命中任何实体时，KG 通道返回空."""
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        result = await hybrid.retrieve(
            "test", "完全无关的查询词", top_k=3, channels=["kg"]
        )
        # KG 通道存在但结果可能为空
        assert "kg" in result.channelResults or result.topK == 0

    @pytest.mark.asyncio
    async def test_default_reranker_is_identity(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        assert isinstance(hybrid.reranker, IdentityReranker)

    @pytest.mark.asyncio
    async def test_resolve_channels_default(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        assert hybrid._resolve_channels(None) == ["vector", "keyword", "kg"]

    @pytest.mark.asyncio
    async def test_resolve_channels_subset(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        assert hybrid._resolve_channels(["vector"]) == ["vector"]

    @pytest.mark.asyncio
    async def test_resolve_channels_empty(self, hybrid_setup):
        retriever, bm25, kg = hybrid_setup
        hybrid = HybridRetriever(retriever, bm25=bm25, kg=kg)
        assert hybrid._resolve_channels([]) == []


# ----------------------------------------------------------------------
# KnowledgeGraph 抽象
# ----------------------------------------------------------------------


class TestKnowledgeGraphABC:
    def test_cannot_instantiate_abc(self):
        with pytest.raises(TypeError):
            KnowledgeGraph()  # type: ignore[abstract]

    @pytest.mark.asyncio
    async def test_subclass_must_implement(self):
        class PartialKG(KnowledgeGraph):
            async def link_entities(self, query, *, top_k=5):
                return []

        # 仍未实现 neighbor_chunks / expand_query
        with pytest.raises(TypeError):
            PartialKG()  # type: ignore[abstract]


# ----------------------------------------------------------------------
# Reranker 抽象
# ----------------------------------------------------------------------


class TestRerankerABC:
    def test_cannot_instantiate_abc(self):
        with pytest.raises(TypeError):
            Reranker()  # type: ignore[abstract]


# ----------------------------------------------------------------------
# 辅助函数
# ----------------------------------------------------------------------


class TestHelpers:
    def test_chunk_to_text_string(self):
        from chunker.rag.hybrid_retriever import _chunk_to_text

        c = Chunk(
            id="c1",
            content="hello",
            metadata=ChunkMetadata(modality=Modality.TEXT),
        )
        assert _chunk_to_text(c) == "hello"

    def test_chunk_to_text_dict(self):
        from chunker.rag.hybrid_retriever import _chunk_to_text

        c = Chunk(
            id="c1",
            content={"a": 1},
            metadata=ChunkMetadata(modality=Modality.TABLE),
        )
        text = _chunk_to_text(c)
        assert json.loads(text) == {"a": 1}

    def test_chunk_to_text_list(self):
        from chunker.rag.hybrid_retriever import _chunk_to_text

        c = Chunk(
            id="c1",
            content=[1, 2, 3],
            metadata=ChunkMetadata(modality=Modality.TABLE),
        )
        text = _chunk_to_text(c)
        assert json.loads(text) == [1, 2, 3]

    def test_chunk_to_text_bytes(self):
        from chunker.rag.hybrid_retriever import _chunk_to_text

        c = Chunk(
            id="c1",
            content=b"binary",
            metadata=ChunkMetadata(
                modality=Modality.IMAGE, extra={"text": "ocr text"}
            ),
        )
        assert _chunk_to_text(c) == "ocr text"

    def test_chunk_to_text_bytes_no_extra(self):
        from chunker.rag.hybrid_retriever import _chunk_to_text

        c = Chunk(
            id="c1",
            content=b"binary",
            metadata=ChunkMetadata(modality=Modality.IMAGE, source="img.png"),
        )
        assert _chunk_to_text(c) == "img.png"

    def test_chunk_to_text_other(self):
        from chunker.rag.hybrid_retriever import _chunk_to_text

        c = Chunk(
            id="c1",
            content=42,
            metadata=ChunkMetadata(modality=Modality.TEXT),
        )
        assert _chunk_to_text(c) == "42"

    def test_chunk_to_meta(self):
        from chunker.rag.hybrid_retriever import _chunk_to_meta

        c = Chunk(
            id="c1",
            content="hello",
            metadata=ChunkMetadata(
                modality=Modality.TEXT,
                source="doc1",
                start=0,
                end=5,
                index=0,
                extra={"k": "v"},
            ),
        )
        meta = _chunk_to_meta(c)
        assert meta["modality"] == "text"
        assert meta["source"] == "doc1"
        assert meta["extra"] == {"k": "v"}

    def test_chunk_to_meta_no_metadata(self):
        from chunker.rag.hybrid_retriever import _chunk_to_meta

        class FakeChunk:
            id = "c1"
            content = "x"
            metadata = None

        meta = _chunk_to_meta(FakeChunk())
        assert meta == {}