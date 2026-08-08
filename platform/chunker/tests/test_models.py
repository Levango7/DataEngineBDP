"""数据模型单元测试."""

from __future__ import annotations

from chunker.models import (
    Chunk,
    ChunkConfig,
    ChunkMetadata,
    ChunkResult,
    Modality,
)
from pydantic import ValidationError
import pytest

# ----------------------------------------------------------------------
# Modality
# ----------------------------------------------------------------------


class TestModality:
    def test_values(self):
        assert Modality.TEXT.value == "text"
        assert Modality.TABLE.value == "table"
        assert Modality.IMAGE.value == "image"
        assert Modality.AUDIO.value == "audio"
        assert Modality.VIDEO.value == "video"
        assert Modality.CODE.value == "code"

    def test_from_string(self):
        assert Modality("text") is Modality.TEXT
        assert Modality("image") is Modality.IMAGE

    def test_invalid_string(self):
        with pytest.raises(ValueError):
            Modality("unknown")


# ----------------------------------------------------------------------
# ChunkMetadata
# ----------------------------------------------------------------------


class TestChunkMetadata:
    def test_default(self):
        m = ChunkMetadata(modality=Modality.TEXT)
        assert m.modality is Modality.TEXT
        assert m.source == ""
        assert m.start == 0
        assert m.end == 0
        assert m.index == 0
        assert m.extra == {}

    def test_end_lt_start_invalid(self):
        with pytest.raises(ValidationError):
            ChunkMetadata(modality=Modality.TEXT, start=10, end=5)

    def test_end_eq_start_ok(self):
        m = ChunkMetadata(modality=Modality.TEXT, start=5, end=5)
        assert m.end == 5

    def test_extra_field(self):
        m = ChunkMetadata(
            modality=Modality.IMAGE,
            extra={"bbox": [0, 0, 100, 100], "page": 1},
        )
        assert m.extra["bbox"] == [0, 0, 100, 100]
        assert m.extra["page"] == 1

    def test_negative_start_invalid(self):
        with pytest.raises(ValidationError):
            ChunkMetadata(modality=Modality.TEXT, start=-1)


# ----------------------------------------------------------------------
# Chunk
# ----------------------------------------------------------------------


class TestChunk:
    def _make(self, **kw) -> Chunk:
        defaults = dict(
            id="c1",
            content="hello",
            metadata=ChunkMetadata(modality=Modality.TEXT),
        )
        defaults.update(kw)
        return Chunk(**defaults)

    def test_basic(self):
        c = self._make()
        assert c.id == "c1"
        assert c.content == "hello"
        assert c.embedding is None
        assert c.tokens is None
        assert c.createdAt is not None

    def test_empty_id_invalid(self):
        with pytest.raises(ValidationError):
            self._make(id="")

    def test_with_embedding(self):
        c = self._make()
        c2 = c.with_embedding([0.1, 0.2, 0.3])
        assert c2.embedding == [0.1, 0.2, 0.3]
        # 原对象不变
        assert c.embedding is None

    def test_with_tokens(self):
        c = self._make()
        c2 = c.with_tokens(42)
        assert c2.tokens == 42
        assert c.tokens is None

    def test_negative_tokens_invalid(self):
        with pytest.raises(ValidationError):
            self._make(tokens=-1)

    def test_arbitrary_content(self):
        c = self._make(content={"rows": [[1, 2], [3, 4]]})
        assert c.content == {"rows": [[1, 2], [3, 4]]}


# ----------------------------------------------------------------------
# ChunkConfig
# ----------------------------------------------------------------------


class TestChunkConfig:
    def test_default(self):
        cfg = ChunkConfig(modality=Modality.TEXT)
        assert cfg.modality is Modality.TEXT
        assert cfg.windowSize == 512
        assert cfg.overlap == 0.1
        assert cfg.maxTokens == 8192
        assert cfg.minChunkSize == 1
        assert cfg.language == "auto"
        assert cfg.extra == {}

    def test_overlap_size(self):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=100, overlap=0.2)
        assert cfg.overlap_size() == 20

    def test_stride(self):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=100, overlap=0.2)
        assert cfg.stride() == 80

    def test_stride_at_least_one(self):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=10, overlap=0.9)
        # overlap_size = 9, stride = 1
        assert cfg.stride() == 1

    def test_overlap_zero(self):
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=100, overlap=0.0)
        assert cfg.overlap_size() == 0
        assert cfg.stride() == 100

    def test_overlap_negative_invalid(self):
        with pytest.raises(ValidationError):
            ChunkConfig(modality=Modality.TEXT, overlap=-0.1)

    def test_overlap_one_invalid(self):
        with pytest.raises(ValidationError):
            ChunkConfig(modality=Modality.TEXT, overlap=1.0)

    def test_overlap_ge_one_invalid(self):
        with pytest.raises(ValidationError):
            ChunkConfig(modality=Modality.TEXT, overlap=1.5)

    def test_window_size_zero_invalid(self):
        with pytest.raises(ValidationError):
            ChunkConfig(modality=Modality.TEXT, windowSize=0)

    def test_max_tokens_zero_invalid(self):
        with pytest.raises(ValidationError):
            ChunkConfig(modality=Modality.TEXT, maxTokens=0)

    def test_extra_config(self):
        cfg = ChunkConfig(
            modality=Modality.IMAGE,
            extra={"splitBy": "grid", "gridSize": 256},
        )
        assert cfg.extra["splitBy"] == "grid"


# ----------------------------------------------------------------------
# ChunkResult
# ----------------------------------------------------------------------


class TestChunkResult:
    def test_default(self):
        r = ChunkResult()
        assert r.chunks == []
        assert r.totalTokens == 0
        assert r.durationMs == 0.0
        assert r.modality is None
        assert r.source == ""
        assert r.count == 0

    def test_with_chunks(self):
        c1 = Chunk(
            id="c1",
            content="a",
            metadata=ChunkMetadata(modality=Modality.TEXT),
            tokens=10,
        )
        c2 = Chunk(
            id="c2",
            content="b",
            metadata=ChunkMetadata(modality=Modality.TEXT),
            tokens=20,
        )
        r = ChunkResult(chunks=[c1, c2], totalTokens=30, modality=Modality.TEXT)
        assert r.count == 2
        assert r.totalTokens == 30

    def test_negative_total_tokens_invalid(self):
        with pytest.raises(ValidationError):
            ChunkResult(totalTokens=-1)

    def test_negative_duration_invalid(self):
        with pytest.raises(ValidationError):
            ChunkResult(durationMs=-0.1)
