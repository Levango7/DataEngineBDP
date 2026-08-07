"""端到端 RAG 管道测试 (T008-6)."""
from __future__ import annotations

import pytest

from chunker.base import BaseChunker
from chunker.embedding.base import EmbeddingAdapter
from chunker.models import Chunk, ChunkConfig, ChunkMetadata, Modality
from chunker.rag.pipeline import RAGPipeline
from chunker.rag.vector_store import MockVectorStore


# ----------------------------------------------------------------------
# 测试用 stub
# ----------------------------------------------------------------------


class StubChunker(BaseChunker):
    """简单切片器：按字符数切分."""

    MODALITY = Modality.TEXT

    async def _preprocess(self, content, config):
        if not isinstance(content, str):
            raise TypeError("需要 str")
        return content

    async def _split(self, preprocessed, config):
        text = preprocessed
        if not text:
            return []
        window = config.windowSize
        chunks = []
        for i in range(0, len(text), window):
            sub = text[i : i + window]
            chunks.append(Chunk(
                id=self._make_chunk_id(),
                content=sub,
                metadata=self._make_metadata(config, index=len(chunks), start=i, end=i + len(sub)),
            ))
        return chunks

    async def _postprocess(self, chunks, config):
        for c in chunks:
            c.tokens = len(c.content) // 4
        return chunks


class StubAdapter(EmbeddingAdapter):
    def __init__(self, dimension=4):
        super().__init__("stub", dimension=dimension, normalize=False)

    def _load_backend(self):
        return "stub"

    def _encode(self, texts, backend):
        d = self._declared_dim or 4
        results = []
        for text in texts:
            vec = [0.0] * d
            for i, ch in enumerate(text[:d]):
                vec[i] = float(ord(ch) % 100) / 100.0
            results.append(vec)
        return results


# ----------------------------------------------------------------------
# 测试
# ----------------------------------------------------------------------


class TestRAGPipeline:
    @pytest.mark.asyncio
    async def test_init(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(),
            store=MockVectorStore(),
        )
        assert pipeline.defaultCollection == "chunks"
        assert pipeline.indexer is not None
        assert pipeline.retriever is not None
        assert pipeline.fusionRetriever is not None

    @pytest.mark.asyncio
    async def test_index_and_retrieve(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(dimension=4),
            store=MockVectorStore(),
        )
        config = ChunkConfig(modality=Modality.TEXT, windowSize=10)
        chunks, count = await pipeline.index(
            "test", "hello world this is a test document", config
        )
        assert count > 0
        assert len(chunks) > 0

        results = await pipeline.retrieve("test", "hello", top_k=5)
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_index_with_chunks(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(dimension=4),
            store=MockVectorStore(),
        )
        chunks = [
            Chunk(
                id="c1",
                content="hello",
                metadata=ChunkMetadata(modality=Modality.TEXT, index=0),
            ),
            Chunk(
                id="c2",
                content="world",
                metadata=ChunkMetadata(modality=Modality.TEXT, index=1),
            ),
        ]
        _, count = await pipeline.index("test", None, chunks=chunks)
        assert count == 2

    @pytest.mark.asyncio
    async def test_index_empty(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(),
            store=MockVectorStore(),
        )
        config = ChunkConfig(modality=Modality.TEXT, windowSize=10)
        chunks, count = await pipeline.index("test", "", config)
        assert count == 0
        assert chunks == []

    @pytest.mark.asyncio
    async def test_retrieve_fused(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(dimension=4),
            store=MockVectorStore(),
        )
        config = ChunkConfig(modality=Modality.TEXT, windowSize=10)
        await pipeline.index("test", "hello world test document", config)

        results = await pipeline.retrieve_fused(
            "test", "hello",
            modalities=[Modality.TEXT],
            top_k=3,
            method="rrf",
        )
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_index_and_retrieve_e2e(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(dimension=4),
            store=MockVectorStore(),
        )
        config = ChunkConfig(modality=Modality.TEXT, windowSize=10)
        results = await pipeline.index_and_retrieve(
            "hello world test document content here",
            config,
            "hello",
            collection_name="test",
            top_k=3,
        )
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_index_and_retrieve_fused(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(dimension=4),
            store=MockVectorStore(),
        )
        config = ChunkConfig(modality=Modality.TEXT, windowSize=10)
        results = await pipeline.index_and_retrieve(
            "hello world test document",
            config,
            "hello",
            collection_name="test",
            top_k=3,
            fused=True,
            modalities=[Modality.TEXT],
        )
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_index_requires_config(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(),
            store=MockVectorStore(),
        )
        from chunker.rag.exceptions import RAGError
        with pytest.raises(RAGError):
            await pipeline.index("test", "content", None)

    @pytest.mark.asyncio
    async def test_close(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(),
            store=MockVectorStore(),
        )
        await pipeline.close()  # 不应抛异常

    @pytest.mark.asyncio
    async def test_default_collection(self):
        pipeline = RAGPipeline(
            chunker=StubChunker(),
            adapter=StubAdapter(dimension=4),
            store=MockVectorStore(),
            collection_name="my_coll",
        )
        config = ChunkConfig(modality=Modality.TEXT, windowSize=10)
        await pipeline.index(None, "hello world", config)
        results = await pipeline.retrieve(None, "hello")
        assert isinstance(results, list)