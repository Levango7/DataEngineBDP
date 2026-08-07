"""OpenAI 适配器测试 (T008-6)."""
from __future__ import annotations

import math
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from chunker.embedding.openai_adapter import (
    DEFAULT_BASE_URL,
    MOCK_API_KEY,
    OPENAI_LARGE_MODEL,
    OPENAI_SMALL_MODEL,
    OpenAIAdapter,
    is_openai_available,
)
from chunker.embedding.exceptions import (
    EmbeddingComputeError,
    ModelLoadError,
    ModelUnavailableError,
)


class TestOpenAIAdapterInit:
    def test_default_model(self):
        adapter = OpenAIAdapter(api_key="sk-test")
        assert adapter.model == OPENAI_SMALL_MODEL
        assert adapter.dim() == 1536
        assert adapter.normalize is False
        assert adapter.baseUrl == DEFAULT_BASE_URL

    def test_large_model(self):
        adapter = OpenAIAdapter(model=OPENAI_LARGE_MODEL, api_key="sk-test")
        assert adapter.dim() == 3072

    def test_explicit_dimension(self):
        adapter = OpenAIAdapter(api_key="sk-test", dimension=256)
        assert adapter.dim() == 256

    def test_custom_base_url(self):
        adapter = OpenAIAdapter(api_key="sk-test", base_url="https://custom.api/v1")
        assert adapter.baseUrl == "https://custom.api/v1"

    def test_request_dimensions(self):
        adapter = OpenAIAdapter(api_key="sk-test", request_dimensions=512)
        assert adapter.requestDimensions == 512


class TestOpenAIMockMode:
    """Mock 模式（api_key='mock'）测试."""

    @pytest.mark.asyncio
    async def test_embed_mock(self):
        adapter = OpenAIAdapter(api_key=MOCK_API_KEY, dimension=64)
        vecs = await adapter.embed(["hello", "world"])
        assert len(vecs) == 2
        assert len(vecs[0]) == 64

    @pytest.mark.asyncio
    async def test_embed_mock_deterministic(self):
        """同一文本生成同一向量."""
        adapter = OpenAIAdapter(api_key=MOCK_API_KEY, dimension=32)
        vecs1 = await adapter.embed(["test"])
        vecs2 = await adapter.embed(["test"])
        assert vecs1[0] == vecs2[0]

    @pytest.mark.asyncio
    async def test_embed_mock_different_texts(self):
        """不同文本生成不同向量."""
        adapter = OpenAIAdapter(api_key=MOCK_API_KEY, dimension=32)
        vecs = await adapter.embed(["hello", "world"])
        assert vecs[0] != vecs[1]

    @pytest.mark.asyncio
    async def test_embed_mock_empty(self):
        adapter = OpenAIAdapter(api_key=MOCK_API_KEY)
        assert await adapter.embed([]) == []

    @pytest.mark.asyncio
    async def test_embed_query_mock(self):
        adapter = OpenAIAdapter(api_key=MOCK_API_KEY, dimension=32)
        vec = await adapter.embed_query("test")
        assert len(vec) == 32

    @pytest.mark.asyncio
    async def test_embed_mock_normalize(self):
        adapter = OpenAIAdapter(api_key=MOCK_API_KEY, dimension=32, normalize=True)
        vecs = await adapter.embed(["test"])
        norm = math.sqrt(sum(x * x for x in vecs[0]))
        assert math.isclose(norm, 1.0, abs_tol=1e-5)

    @pytest.mark.asyncio
    async def test_mock_is_available(self):
        adapter = OpenAIAdapter(api_key=MOCK_API_KEY)
        assert adapter.is_available() is True


class TestOpenAIRealMode:
    def test_no_api_key_raises(self):
        adapter = OpenAIAdapter(api_key=None)
        with pytest.raises(ModelUnavailableError):
            adapter._ensure_backend()

    def test_empty_api_key_raises(self):
        adapter = OpenAIAdapter(api_key="")
        with pytest.raises(ModelUnavailableError):
            adapter._ensure_backend()

    @pytest.mark.asyncio
    async def test_embed_with_mock_client(self):
        """使用 mock 的 openai.AsyncOpenAI 测试."""
        # 构造 mock 响应
        mock_embedding = [0.1] * 1536
        mock_item = MagicMock()
        mock_item.embedding = mock_embedding
        mock_response = MagicMock()
        mock_response.data = [mock_item]
        mock_create = AsyncMock(return_value=mock_response)
        mock_client = MagicMock()
        mock_client.embeddings = MagicMock()
        mock_client.embeddings.create = mock_create

        adapter = OpenAIAdapter(api_key="sk-test", dimension=1536, normalize=False)
        # 注入 mock 客户端
        adapter._backend = mock_client
        adapter._backend_loaded = True
        adapter._backend_available = True

        vecs = await adapter.embed(["test"])
        assert len(vecs) == 1
        assert len(vecs[0]) == 1536
        assert vecs[0][0] == 0.1
        mock_create.assert_called_once()

    @pytest.mark.asyncio
    async def test_embed_with_request_dimensions(self):
        mock_embedding = [0.2] * 512
        mock_item = MagicMock()
        mock_item.embedding = mock_embedding
        mock_response = MagicMock()
        mock_response.data = [mock_item]
        mock_create = AsyncMock(return_value=mock_response)
        mock_client = MagicMock()
        mock_client.embeddings.create = mock_create

        adapter = OpenAIAdapter(
            api_key="sk-test",
            dimension=512,
            request_dimensions=512,
        )
        adapter._backend = mock_client
        adapter._backend_loaded = True

        vecs = await adapter.embed(["test"])
        assert len(vecs[0]) == 512
        # 验证 dimensions 参数传递
        call_kwargs = mock_create.call_args.kwargs
        assert call_kwargs.get("dimensions") == 512

    @pytest.mark.asyncio
    async def test_embed_batch(self):
        mock_embeddings = [[0.1 * i] * 4 for i in range(1, 4)]
        mock_items = []
        for emb in mock_embeddings:
            item = MagicMock()
            item.embedding = emb
            mock_items.append(item)
        mock_response = MagicMock()
        mock_response.data = mock_items
        mock_create = AsyncMock(return_value=mock_response)
        mock_client = MagicMock()
        mock_client.embeddings.create = mock_create

        adapter = OpenAIAdapter(api_key="sk-test", dimension=4)
        adapter._backend = mock_client
        adapter._backend_loaded = True

        vecs = await adapter.embed(["a", "b", "c"])
        assert len(vecs) == 3

    @pytest.mark.asyncio
    async def test_embed_compute_error(self):
        mock_create = AsyncMock(side_effect=Exception("API error"))
        mock_client = MagicMock()
        mock_client.embeddings.create = mock_create

        adapter = OpenAIAdapter(api_key="sk-test", dimension=4)
        adapter._backend = mock_client
        adapter._backend_loaded = True

        with pytest.raises(EmbeddingComputeError):
            await adapter.embed(["test"])


class TestOpenAIConstants:
    def test_model_constants(self):
        assert OPENAI_SMALL_MODEL == "text-embedding-3-small"
        assert OPENAI_LARGE_MODEL == "text-embedding-3-large"

    def test_mock_key(self):
        assert MOCK_API_KEY == "mock"

    def test_is_openai_available(self):
        assert isinstance(is_openai_available(), bool)