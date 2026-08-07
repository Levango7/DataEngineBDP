"""BaseChunker 抽象基类单元测试."""
from __future__ import annotations

import pytest

from chunker.base import BaseChunker
from chunker.exceptions import InvalidOverlapError
from chunker.models import Chunk, ChunkConfig, ChunkMetadata, Modality


# ----------------------------------------------------------------------
# 测试用具体切片器
# ----------------------------------------------------------------------


class _SimpleTextChunker(BaseChunker):
    """简单文本切片器：按 windowSize 滑窗切分."""

    MODALITY = Modality.TEXT

    async def _preprocess(self, content, config):
        if not isinstance(content, str):
            raise TypeError("content 必须是字符串")
        return content.strip()

    async def _split(self, preprocessed, config):
        if not preprocessed:
            return []
        chunks: list[Chunk] = []
        stride = config.stride()
        idx = 0
        for start in range(0, len(preprocessed), stride):
            end = min(start + config.windowSize, len(preprocessed))
            piece = preprocessed[start:end]
            chunks.append(
                Chunk(
                    id=self._make_chunk_id(),
                    content=piece,
                    metadata=self._make_metadata(
                        config, index=idx, start=start, end=end
                    ),
                )
            )
            idx += 1
            if end >= len(preprocessed):
                break
        return chunks

    async def _postprocess(self, chunks, config):
        for c in chunks:
            c.tokens = self._count_tokens(c.content)
        return chunks


class _NoOpChunker(BaseChunker):
    """空操作切片器：原样返回单个切片."""

    async def _preprocess(self, content, config):
        return content

    async def _split(self, preprocessed, config):
        return [
            Chunk(
                id="noop",
                content=preprocessed,
                metadata=ChunkMetadata(modality=config.modality),
            )
        ]

    async def _postprocess(self, chunks, config):
        return chunks


# ----------------------------------------------------------------------
# 抽象类不可实例化
# ----------------------------------------------------------------------


def test_base_chunker_is_abstract():
    """BaseChunker 含抽象方法，不可直接实例化."""
    with pytest.raises(TypeError):
        BaseChunker()  # type: ignore[abstract]


# ----------------------------------------------------------------------
# chunk 主流程
# ----------------------------------------------------------------------


class TestChunkFlow:
    @pytest.fixture
    def chunker(self) -> _SimpleTextChunker:
        return _SimpleTextChunker()

    async def test_chunk_basic(self, chunker):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=10, overlap=0.0)
        chunks = await chunker.chunk("hello world, this is a test", cfg)
        assert len(chunks) >= 1
        # 所有切片内容拼接应覆盖原文（去除空白后）
        joined = "".join(c.content for c in chunks)
        assert joined == "hello world, this is a test"

    async def test_chunk_with_overlap(self, chunker):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=10, overlap=0.5)
        text = "abcdefghijklmnopqrstuvwxyz"
        chunks = await chunker.chunk(text, cfg)
        # 有重叠时切片数应多于无重叠
        assert len(chunks) >= 2
        # 第一切片应是前 10 个字符
        assert chunks[0].content == text[:10]

    async def test_chunk_empty_string(self, chunker):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=10, overlap=0.0)
        chunks = await chunker.chunk("", cfg)
        assert chunks == []

    async def test_chunk_whitespace_only(self, chunker):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=10, overlap=0.0)
        chunks = await chunker.chunk("   ", cfg)
        assert chunks == []

    async def test_chunk_preprocess_type_error(self, chunker):
        cfg = ChunkConfig(modality=Modality.TEXT)
        with pytest.raises(TypeError):
            await chunker.chunk(123, cfg)  # type: ignore[arg-type]

    async def test_chunk_postprocess_sets_tokens(self, chunker):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=100, overlap=0.0)
        chunks = await chunker.chunk("hello world", cfg)
        assert all(c.tokens is not None for c in chunks)
        assert all(c.tokens >= 1 for c in chunks)

    async def test_chunk_metadata_index(self, chunker):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=5, overlap=0.0)
        chunks = await chunker.chunk("abcdefghij", cfg)
        for i, c in enumerate(chunks):
            assert c.metadata.index == i

    async def test_chunk_metadata_modality(self, chunker):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=100, overlap=0.0)
        chunks = await chunker.chunk("hello", cfg)
        assert all(c.metadata.modality is Modality.TEXT for c in chunks)


# ----------------------------------------------------------------------
# chunk_with_result
# ----------------------------------------------------------------------


class TestChunkWithResult:
    async def test_result_aggregation(self):
        chunker = _SimpleTextChunker()
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=5, overlap=0.0)
        result = await chunker.chunk_with_result("abcdefghij", cfg)
        assert result.count == 2
        assert result.modality is Modality.TEXT
        assert result.durationMs >= 0.0
        assert result.totalTokens > 0

    async def test_result_empty(self):
        chunker = _SimpleTextChunker()
        cfg = ChunkConfig(modality=Modality.TEXT)
        result = await chunker.chunk_with_result("", cfg)
        assert result.count == 0
        assert result.totalTokens == 0


# ----------------------------------------------------------------------
# 配置校验
# ----------------------------------------------------------------------


class TestConfigValidation:
    async def test_invalid_overlap_raises(self):
        chunker = _NoOpChunker()
        # 构造一个 overlap * windowSize >= windowSize 的非法配置
        # overlap < 1.0 由 pydantic 保证，但 overlap_size 可能等于 windowSize
        # 当 overlap=0.99, windowSize=100 -> overlap_size=99 < 100，仍合法
        # 我们手动构造一个非法场景：通过 model_construct 绕过校验
        cfg = ChunkConfig.model_construct(
            modality=Modality.TEXT, windowSize=10, overlap=1.0
        )
        with pytest.raises(InvalidOverlapError):
            await chunker.chunk("hello", cfg)

    async def test_valid_overlap_passes(self):
        chunker = _NoOpChunker()
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=10, overlap=0.9)
        chunks = await chunker.chunk("hello", cfg)
        assert len(chunks) == 1


# ----------------------------------------------------------------------
# 工具方法
# ----------------------------------------------------------------------


class TestUtilityMethods:
    def test_count_tokens_empty(self):
        chunker = _NoOpChunker()
        assert chunker._count_tokens("") == 0

    def test_count_tokens_short(self):
        chunker = _NoOpChunker()
        # 4 字符 -> 1 token
        assert chunker._count_tokens("abcd") == 1

    def test_count_tokens_long(self):
        chunker = _NoOpChunker()
        # 8 字符 -> 2 tokens
        assert chunker._count_tokens("abcdefgh") == 2

    def test_count_tokens_at_least_one(self):
        chunker = _NoOpChunker()
        # 1 字符 -> max(1, 0) = 1
        assert chunker._count_tokens("a") == 1

    def test_make_chunk_id_unique(self):
        chunker = _NoOpChunker()
        ids = {chunker._make_chunk_id() for _ in range(100)}
        assert len(ids) == 100

    def test_make_metadata(self):
        chunker = _NoOpChunker()
        cfg = ChunkConfig(modality=Modality.IMAGE)
        m = chunker._make_metadata(
            cfg, index=3, start=10, end=20, source="img.png", extra={"page": 1}
        )
        assert m.modality is Modality.IMAGE
        assert m.index == 3
        assert m.start == 10
        assert m.end == 20
        assert m.source == "img.png"
        assert m.extra == {"page": 1}

    def test_overlap_merge_default_noop(self):
        chunker = _NoOpChunker()
        cfg = ChunkConfig(modality=Modality.TEXT)
        c1 = Chunk(
            id="1",
            content="a",
            metadata=ChunkMetadata(modality=Modality.TEXT),
        )
        c2 = Chunk(
            id="2",
            content="b",
            metadata=ChunkMetadata(modality=Modality.TEXT),
        )
        result = chunker._overlap_merge([c1, c2], overlap_size=5)
        assert result is [c1, c2] or result == [c1, c2]


# ----------------------------------------------------------------------
# 模态初始化
# ----------------------------------------------------------------------


class TestModalityInit:
    def test_init_with_str(self):
        chunker = _NoOpChunker(modality="image")
        assert chunker.modality is Modality.IMAGE

    def test_init_with_enum(self):
        chunker = _NoOpChunker(modality=Modality.AUDIO)
        assert chunker.modality is Modality.AUDIO

    def test_init_with_class_attribute(self):
        chunker = _SimpleTextChunker()
        assert chunker.modality is Modality.TEXT

    def test_init_default_text(self):
        chunker = _NoOpChunker()
        assert chunker.modality is Modality.TEXT