"""BGE 适配器测试 (T008-6)."""

from __future__ import annotations

import math
from unittest.mock import MagicMock

from chunker.embedding.bge_adapter import (
    BGE_EN_QUERY_INSTRUCTION,
    BGE_LARGE_EN_MODEL,
    BGE_LARGE_ZH_MODEL,
    BGE_SMALL_ZH_MODEL,
    BGE_ZH_QUERY_INSTRUCTION,
    BGEAdapter,
)
import pytest

# ----------------------------------------------------------------------
# Mock 编码函数
# ----------------------------------------------------------------------


def _mock_encode(texts, batch_size=32, show_progress_bar=False, convert_to_numpy=True, normalize_embeddings=False):
    """确定性 mock 编码：基于文本哈希生成 1024 维向量."""
    import hashlib
    import struct

    results = []
    for text in texts:
        h = hashlib.sha256(text.encode("utf-8")).digest()
        seed = bytearray()
        counter = 0
        while len(seed) < 1024 * 4:
            seed.extend(hashlib.sha256(h + counter.to_bytes(4, "big")).digest())
            counter += 1
        vec = []
        for i in range(1024):
            val = struct.unpack("f", seed[i * 4 : i * 4 + 4])[0]
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


# ----------------------------------------------------------------------
# 初始化测试
# ----------------------------------------------------------------------


class TestBGEAdapterInit:
    def test_default_model(self):
        adapter = BGEAdapter()
        assert adapter.model == BGE_LARGE_ZH_MODEL
        assert adapter.dim() == 1024
        assert adapter.normalize is True
        assert adapter.queryInstruction == BGE_ZH_QUERY_INSTRUCTION

    def test_en_model(self):
        adapter = BGEAdapter(model=BGE_LARGE_EN_MODEL)
        assert adapter.model == BGE_LARGE_EN_MODEL
        assert adapter.dim() == 1024
        assert adapter.queryInstruction == BGE_EN_QUERY_INSTRUCTION

    def test_small_model(self):
        adapter = BGEAdapter(model=BGE_SMALL_ZH_MODEL)
        assert adapter.dim() == 512

    def test_explicit_dimension(self):
        adapter = BGEAdapter(dimension=256)
        assert adapter.dim() == 256

    def test_explicit_query_instruction(self):
        adapter = BGEAdapter(query_instruction="custom:")
        assert adapter.queryInstruction == "custom:"


# ----------------------------------------------------------------------
# 可用性测试
# ----------------------------------------------------------------------


class TestBGEAvailability:
    def test_is_available_returns_bool(self):
        # 使用 mock 后端，避免加载真实模型
        adapter = BGEAdapter()
        adapter._backend = MockSTModel(BGE_LARGE_ZH_MODEL)
        adapter._backend_loaded = True
        adapter._backend_available = True
        result = adapter.is_available()
        assert result is True


# ----------------------------------------------------------------------
# 使用 mock 后端的测试
# ----------------------------------------------------------------------


class TestBGEWithMockBackend:
    """直接 mock _load_backend 方法，避免加载真实模型."""

    @pytest.fixture
    def mocked_adapter(self):
        """返回带 mock 后端的 BGE 适配器."""
        adapter = BGEAdapter(dimension=1024, normalize=False)
        mock_model = MockSTModel(BGE_LARGE_ZH_MODEL)
        adapter._backend = mock_model
        adapter._backend_loaded = True
        adapter._backend_available = True
        return adapter

    @pytest.mark.asyncio
    async def test_embed_basic(self, mocked_adapter):
        vecs = await mocked_adapter.embed(["你好", "世界"])
        assert len(vecs) == 2
        assert len(vecs[0]) == 1024

    @pytest.mark.asyncio
    async def test_embed_query_with_instruction(self, mocked_adapter):
        vec = await mocked_adapter.embed_query("测试查询")
        assert len(vec) == 1024

    @pytest.mark.asyncio
    async def test_embed_normalize(self):
        adapter = BGEAdapter(dimension=1024, normalize=True)
        adapter._backend = MockSTModel(BGE_LARGE_ZH_MODEL)
        adapter._backend_loaded = True
        vecs = await adapter.embed(["test"])
        norm = math.sqrt(sum(x * x for x in vecs[0]))
        assert math.isclose(norm, 1.0, abs_tol=1e-5)

    @pytest.mark.asyncio
    async def test_embed_empty(self, mocked_adapter):
        assert await mocked_adapter.embed([]) == []

    @pytest.mark.asyncio
    async def test_embed_deterministic(self, mocked_adapter):
        """同一文本生成同一向量."""
        v1 = await mocked_adapter.embed(["test"])
        v2 = await mocked_adapter.embed(["test"])
        assert v1[0] == v2[0]

    @pytest.mark.asyncio
    async def test_embed_batch(self, mocked_adapter):
        texts = [f"text_{i}" for i in range(5)]
        vecs = await mocked_adapter.embed(texts)
        assert len(vecs) == 5


# ----------------------------------------------------------------------
# 常量测试
# ----------------------------------------------------------------------


class TestBGEConstants:
    def test_model_constants(self):
        assert BGE_LARGE_ZH_MODEL == "BAAI/bge-large-zh"
        assert BGE_LARGE_EN_MODEL == "BAAI/bge-large-en"
        assert BGE_SMALL_ZH_MODEL == "BAAI/bge-small-zh"

    def test_query_instructions(self):
        assert "检索" in BGE_ZH_QUERY_INSTRUCTION
        assert "search" in BGE_EN_QUERY_INSTRUCTION.lower()
