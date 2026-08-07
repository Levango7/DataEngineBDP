"""Embedding 抽象基类测试 (T008-6)."""
from __future__ import annotations

import asyncio
import math
from typing import Any
from unittest.mock import MagicMock

import pytest

from chunker.embedding.base import EmbeddingAdapter
from chunker.embedding.exceptions import (
    EmbeddingComputeError,
    EmbeddingDimensionError,
    ModelLoadError,
    ModelUnavailableError,
)


# ----------------------------------------------------------------------
# 测试用具体适配器
# ----------------------------------------------------------------------


class DummyAdapter(EmbeddingAdapter):
    """用于测试基类逻辑的简单适配器."""

    def __init__(self, dimension=4, **kwargs):
        super().__init__("dummy", dimension=dimension, **kwargs)
        self._load_called = False

    def _load_backend(self) -> Any:
        self._load_called = True
        return "dummy_backend"

    def _encode(self, texts: list[str], backend: Any) -> list[list[float]]:
        # 简单编码：每个字符的 ASCII 值填充到 dimension
        d = self._declared_dim or 4
        results = []
        for text in texts:
            vec = [0.0] * d
            for i, ch in enumerate(text[:d]):
                vec[i] = float(ord(ch))
            results.append(vec)
        return results


# ----------------------------------------------------------------------
# 归一化
# ----------------------------------------------------------------------


class TestNormalize:
    def test_normalize_basic(self):
        vec = [3.0, 4.0]
        result = EmbeddingAdapter._normalize(vec)
        assert math.isclose(result[0], 0.6, abs_tol=1e-6)
        assert math.isclose(result[1], 0.8, abs_tol=1e-6)

    def test_normalize_zero_vector(self):
        vec = [0.0, 0.0, 0.0]
        result = EmbeddingAdapter._normalize(vec)
        assert result == vec

    def test_normalize_empty(self):
        assert EmbeddingAdapter._normalize([]) == []

    def test_normalize_unit_vector(self):
        vec = [1.0, 0.0, 0.0]
        result = EmbeddingAdapter._normalize(vec)
        assert math.isclose(result[0], 1.0, abs_tol=1e-6)

    def test_normalize_batch(self):
        adapter = DummyAdapter()
        vecs = [[3.0, 4.0], [1.0, 0.0]]
        results = adapter._normalize_batch(vecs)
        assert math.isclose(results[0][0], 0.6, abs_tol=1e-6)
        assert math.isclose(results[1][0], 1.0, abs_tol=1e-6)


# ----------------------------------------------------------------------
# 基类接口
# ----------------------------------------------------------------------


class TestBaseInterface:
    def test_name(self):
        adapter = DummyAdapter()
        assert adapter.name() == "dummy"

    def test_dim_declared(self):
        adapter = DummyAdapter(dimension=128)
        assert adapter.dim() == 128

    def test_dim_zero(self):
        adapter = DummyAdapter(dimension=0)
        assert adapter.dim() == 0

    def test_is_available_true(self):
        adapter = DummyAdapter()
        assert adapter.is_available() is True

    def test_ensure_backend_caches(self):
        adapter = DummyAdapter()
        b1 = adapter._ensure_backend()
        b2 = adapter._ensure_backend()
        assert b1 is b2
        assert adapter._load_called


class TestUnavailableAdapter:
    def test_is_available_false_on_load_error(self):
        class FailAdapter(EmbeddingAdapter):
            def _load_backend(self):
                raise ModelLoadError("fail")

            def _encode(self, texts, backend):
                return []

        adapter = FailAdapter("fail")
        assert adapter.is_available() is False

    def test_is_available_false_on_unavailable(self):
        class UnavailAdapter(EmbeddingAdapter):
            def _load_backend(self):
                raise ModelUnavailableError("fail", "missing")

            def _encode(self, texts, backend):
                return []

        adapter = UnavailAdapter("fail")
        assert adapter.is_available() is False


# ----------------------------------------------------------------------
# embed / embed_query
# ----------------------------------------------------------------------


class TestEmbed:
    @pytest.mark.asyncio
    async def test_embed_basic(self):
        adapter = DummyAdapter(dimension=4, normalize=False)
        vecs = await adapter.embed(["ab", "cd"])
        assert len(vecs) == 2
        assert len(vecs[0]) == 4
        assert vecs[0][0] == ord("a")
        assert vecs[0][1] == ord("b")

    @pytest.mark.asyncio
    async def test_embed_empty(self):
        adapter = DummyAdapter()
        assert await adapter.embed([]) == []

    @pytest.mark.asyncio
    async def test_embed_with_normalize(self):
        adapter = DummyAdapter(dimension=2, normalize=True)
        vecs = await adapter.embed(["ab"])
        # 归一化后模长为 1
        norm = math.sqrt(sum(x * x for x in vecs[0]))
        assert math.isclose(norm, 1.0, abs_tol=1e-6)

    @pytest.mark.asyncio
    async def test_embed_query(self):
        adapter = DummyAdapter(dimension=4, normalize=False)
        vec = await adapter.embed_query("ab")
        assert len(vec) == 4
        assert vec[0] == ord("a")

    @pytest.mark.asyncio
    async def test_embed_query_empty(self):
        adapter = DummyAdapter()
        assert await adapter.embed_query("") == []

    @pytest.mark.asyncio
    async def test_embed_dimension_mismatch(self):
        adapter = DummyAdapter(dimension=4, normalize=False)
        # 覆盖 _encode 返回错误维度
        adapter._encode = lambda texts, backend: [[1.0, 2.0]]  # 维度 2 != 4
        with pytest.raises(EmbeddingDimensionError):
            await adapter.embed(["test"])

    @pytest.mark.asyncio
    async def test_embed_compute_error(self):
        adapter = DummyAdapter(dimension=4)
        adapter._encode = MagicMock(side_effect=EmbeddingComputeError("fail"))
        with pytest.raises(EmbeddingComputeError):
            await adapter.embed(["test"])

    @pytest.mark.asyncio
    async def test_embed_generic_error_wrapped(self):
        adapter = DummyAdapter(dimension=4)
        adapter._encode = MagicMock(side_effect=ValueError("generic"))
        with pytest.raises(EmbeddingComputeError):
            await adapter.embed(["test"])

    @pytest.mark.asyncio
    async def test_embed_probes_dimension(self):
        adapter = DummyAdapter(dimension=0, normalize=False)
        await adapter.embed(["test"])
        assert adapter._probed_dim == 4
        assert adapter.dim() == 4

    @pytest.mark.asyncio
    async def test_embed_large_batch(self):
        adapter = DummyAdapter(dimension=4, normalize=False, async_chunk=2)
        texts = [f"t{i}" for i in range(10)]
        vecs = await adapter.embed(texts)
        assert len(vecs) == 10
        # 验证顺序正确
        assert vecs[0][0] == ord("t")
        assert vecs[5][1] == ord("5")