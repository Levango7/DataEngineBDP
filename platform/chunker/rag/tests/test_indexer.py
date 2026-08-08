"""索引器测试 (T008-6)."""

from __future__ import annotations

from chunker.embedding.base import EmbeddingAdapter
from chunker.models import Chunk, ChunkMetadata, Modality
from chunker.rag.exceptions import EmbeddingMissingError
from chunker.rag.indexer import Indexer
from chunker.rag.vector_store import MockVectorStore
import pytest

# ----------------------------------------------------------------------
# fixtures
# ----------------------------------------------------------------------


def _make_chunk(
    content: str,
    modality: Modality = Modality.TEXT,
    embedding: list[float] | None = None,
    chunk_id: str = "c1",
) -> Chunk:
    return Chunk(
        id=chunk_id,
        content=content,
        metadata=ChunkMetadata(modality=modality, source="test", index=0),
        embedding=embedding,
        tokens=10,
    )


class StubAdapter(EmbeddingAdapter):
    """简单 stub 适配器."""

    def __init__(self, dimension=4):
        super().__init__("stub", dimension=dimension, normalize=False)

    def _load_backend(self):
        return "stub"

    def _encode(self, texts, backend):
        d = self._declared_dim or 4
        return [[float(len(t))] + [0.0] * (d - 1) for t in texts]


# ----------------------------------------------------------------------
# 测试
# ----------------------------------------------------------------------


class TestIndexer:
    @pytest.mark.asyncio
    async def test_index_with_embeddings(self):
        store = MockVectorStore()
        indexer = Indexer(store)
        chunks = [
            _make_chunk("hello", embedding=[1.0, 0.0, 0.0, 0.0], chunk_id="c1"),
            _make_chunk("world", embedding=[0.0, 1.0, 0.0, 0.0], chunk_id="c2"),
        ]
        count = await indexer.index("test", chunks)
        assert count == 2
        stats = await store.get_stats("test")
        assert stats.vectorCount == 2

    @pytest.mark.asyncio
    async def test_index_empty(self):
        store = MockVectorStore()
        indexer = Indexer(store)
        count = await indexer.index("test", [])
        assert count == 0

    @pytest.mark.asyncio
    async def test_index_auto_embed(self):
        store = MockVectorStore()
        adapter = StubAdapter(dimension=4)
        indexer = Indexer(store, adapter)
        chunks = [_make_chunk("hello", chunk_id="c1")]
        count = await indexer.index("test", chunks, auto_embed=True)
        assert count == 1

    @pytest.mark.asyncio
    async def test_index_missing_embedding_no_adapter(self):
        store = MockVectorStore()
        indexer = Indexer(store)  # 无 adapter
        chunks = [_make_chunk("hello", embedding=None, chunk_id="c1")]
        with pytest.raises(EmbeddingMissingError):
            await indexer.index("test", chunks, auto_embed=True)

    @pytest.mark.asyncio
    async def test_index_missing_embedding_auto_embed_false(self):
        store = MockVectorStore()
        adapter = StubAdapter(dimension=4)
        indexer = Indexer(store, adapter)
        chunks = [_make_chunk("hello", embedding=None, chunk_id="c1")]
        with pytest.raises(EmbeddingMissingError):
            await indexer.index("test", chunks, auto_embed=False)

    @pytest.mark.asyncio
    async def test_ensure_collection(self):
        store = MockVectorStore()
        indexer = Indexer(store)
        await indexer.ensure_collection("test", 128)
        await indexer.ensure_collection("test", 128)  # 幂等
        stats = await store.get_stats("test")
        assert stats.dimension == 128

    @pytest.mark.asyncio
    async def test_chunk_to_text_string(self):
        chunk = _make_chunk("hello")
        assert Indexer._chunk_to_text(chunk) == "hello"

    @pytest.mark.asyncio
    async def test_chunk_to_text_bytes(self):
        chunk = Chunk(
            id="c1",
            content=b"\x89PNG",
            metadata=ChunkMetadata(modality=Modality.IMAGE, source="img.png", extra={"text": "OCR text"}),
        )
        assert Indexer._chunk_to_text(chunk) == "OCR text"

    @pytest.mark.asyncio
    async def test_chunk_to_text_dict(self):
        chunk = Chunk(
            id="c1",
            content={"col1": "val1", "col2": "val2"},
            metadata=ChunkMetadata(modality=Modality.TABLE),
        )
        text = Indexer._chunk_to_text(chunk)
        assert "col1" in text
        assert "val1" in text

    @pytest.mark.asyncio
    async def test_chunk_to_text_list(self):
        chunk = Chunk(
            id="c1",
            content=[["a", "b"], ["c", "d"]],
            metadata=ChunkMetadata(modality=Modality.TABLE),
        )
        text = Indexer._chunk_to_text(chunk)
        assert "a" in text

    @pytest.mark.asyncio
    async def test_chunk_to_metadata(self):
        chunk = _make_chunk("hello", embedding=[1.0])
        meta = Indexer._chunk_to_metadata(chunk)
        assert meta["chunkId"] == "c1"
        assert meta["modality"] == "text"
        assert meta["source"] == "test"
        assert meta["tokens"] == 10

    @pytest.mark.asyncio
    async def test_infer_dimension(self):
        chunks = [
            _make_chunk("a", embedding=[1.0, 2.0, 3.0]),
            _make_chunk("b", embedding=None),
        ]
        assert Indexer._infer_dimension(chunks) == 3

    @pytest.mark.asyncio
    async def test_infer_dimension_empty(self):
        assert Indexer._infer_dimension([]) == 0

    @pytest.mark.asyncio
    async def test_batch_insert(self):
        store = MockVectorStore()
        indexer = Indexer(store, batch_size=2)
        chunks = [_make_chunk(f"t{i}", embedding=[float(i), 0.0, 0.0, 0.0], chunk_id=f"c{i}") for i in range(5)]
        count = await indexer.index("test", chunks)
        assert count == 5

    @pytest.mark.asyncio
    async def test_index_multi_modality(self):
        store = MockVectorStore()
        indexer = Indexer(store)
        chunks = [
            _make_chunk("text", modality=Modality.TEXT, embedding=[1.0, 0.0], chunk_id="t1"),
            _make_chunk("table", modality=Modality.TABLE, embedding=[0.0, 1.0], chunk_id="t2"),
            _make_chunk("image", modality=Modality.IMAGE, embedding=[1.0, 1.0], chunk_id="t3"),
            _make_chunk("audio", modality=Modality.AUDIO, embedding=[1.0, 0.5], chunk_id="t4"),
        ]
        count = await indexer.index("test", chunks)
        assert count == 4
