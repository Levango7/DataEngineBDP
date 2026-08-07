"""M3E 适配器测试 (T008-6)."""
from __future__ import annotations

import math
from unittest.mock import MagicMock, patch

import pytest

from chunker.embedding.m3e_adapter import (
    M3E_BASE_MODEL,
    M3E_SMALL_MODEL,
    M3EAdapter,
)
from chunker.embedding.exceptions import ModelLoadError, ModelUnavailableError


def _mock_encode(texts, **kwargs):
    import hashlib
    import struct
    results = []
    for text in texts:
        h = hashlib.sha256(text.encode("utf-8")).digest()
        seed = bytearray()
        counter = 0
        while len(seed) < 768 * 4:
            seed.extend(hashlib.sha256(h + counter.to_bytes(4, "big")).digest())
            counter += 1
        vec = []
        for i in range(768):
            val = struct.unpack("f", seed[i*4:i*4+4])[0]
            if val != val:
                val = 0.0
            vec.append(float(val))
        results.append(vec)
    return results


class MockSTModel:
    def __init__(self, name, **kwargs):
        self.name = name
        self.tokenizer = MagicMock()
        self.tokenizer.encode = lambda text: list(text.encode("utf-8"))

    def encode(self, texts, **kwargs):
        return _mock_encode(texts, **kwargs)


class TestM3EAdapterInit:
    def test_default_model(self):
        adapter = M3EAdapter()
        assert adapter.model == M3E_BASE_MODEL
        assert adapter.dim() == 768
        assert adapter.normalize is True
        assert adapter.queryInstruction is None

    def test_small_model(self):
        adapter = M3EAdapter(model=M3E_SMALL_MODEL)
        assert adapter.dim() == 384

    def test_explicit_dimension(self):
        adapter = M3EAdapter(dimension=256)
        assert adapter.dim() == 256

    def test_explicit_query_instruction(self):
        adapter = M3EAdapter(query_instruction="custom:")
        assert adapter.queryInstruction == "custom:"


class TestM3EAvailability:
    def test_is_available_returns_bool(self):
        # 使用 mock 后端，避免加载真实模型
        adapter = M3EAdapter()
        adapter._backend = MockSTModel(M3E_BASE_MODEL)
        adapter._backend_loaded = True
        adapter._backend_available = True
        result = adapter.is_available()
        assert result is True


class TestM3EWithMockBackend:
    @pytest.fixture
    def mocked_adapter(self):
        adapter = M3EAdapter(dimension=768, normalize=False)
        adapter._backend = MockSTModel(M3E_BASE_MODEL)
        adapter._backend_loaded = True
        adapter._backend_available = True
        return adapter

    @pytest.mark.asyncio
    async def test_embed_basic(self, mocked_adapter):
        vecs = await mocked_adapter.embed(["你好", "世界"])
        assert len(vecs) == 2
        assert len(vecs[0]) == 768

    @pytest.mark.asyncio
    async def test_embed_query_no_instruction(self, mocked_adapter):
        vec = await mocked_adapter.embed_query("测试查询")
        assert len(vec) == 768

    @pytest.mark.asyncio
    async def test_embed_normalize(self):
        adapter = M3EAdapter(dimension=768, normalize=True)
        adapter._backend = MockSTModel(M3E_BASE_MODEL)
        adapter._backend_loaded = True
        vecs = await adapter.embed(["test"])
        norm = math.sqrt(sum(x * x for x in vecs[0]))
        assert math.isclose(norm, 1.0, abs_tol=1e-5)

    @pytest.mark.asyncio
    async def test_embed_empty(self, mocked_adapter):
        assert await mocked_adapter.embed([]) == []

    @pytest.mark.asyncio
    async def test_embed_deterministic(self, mocked_adapter):
        v1 = await mocked_adapter.embed(["test"])
        v2 = await mocked_adapter.embed(["test"])
        assert v1[0] == v2[0]
