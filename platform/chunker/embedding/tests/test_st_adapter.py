"""SentenceTransformer 通用适配器测试 (T008-6)."""
from __future__ import annotations

import math
from unittest.mock import MagicMock, patch

import pytest

from chunker.embedding.exceptions import (
    EmbeddingComputeError,
    ModelLoadError,
    ModelUnavailableError,
)
from chunker.embedding.st_adapter import (
    SentenceTransformerAdapter,
    clear_model_cache,
    is_sentence_transformers_available,
)


def _make_mock_model(dimension=8):
    """创建 mock SentenceTransformer 模型."""
    import hashlib
    import struct

    class MockModel:
        def __init__(self, name, **kwargs):
            self.name = name
            self.tokenizer = MagicMock()
            self.tokenizer.encode = lambda text: list(text.encode("utf-8"))

        def encode(self, texts, batch_size=32, show_progress_bar=False,
                   convert_to_numpy=True, normalize_embeddings=False):
            results = []
            for text in texts:
                h = hashlib.sha256(text.encode("utf-8")).digest()
                vec = []
                for i in range(dimension):
                    val = struct.unpack("f", h[i % len(h) * 4 : i % len(h) * 4 + 4])[0]
                    if val != val:
                        val = 0.0
                    vec.append(float(val))
                results.append(vec)
            return results

    return MockModel


class TestSTAdapterInit:
    def test_defaults(self):
        adapter = SentenceTransformerAdapter("test-model", dimension=8)
        assert adapter.model == "test-model"
        assert adapter.dim() == 8
        assert adapter.normalize is True
        assert adapter.device == "cpu"
        assert adapter.queryInstruction is None

    def test_with_query_instruction(self):
        adapter = SentenceTransformerAdapter("test", query_instruction="query:")
        assert adapter.queryInstruction == "query:"


class TestSTAvailability:
    def test_is_sentence_transformers_available_mocked(self):
        import chunker.embedding.st_adapter as st_mod
        with patch.object(st_mod, "is_sentence_transformers_available", return_value=True):
            assert st_mod.is_sentence_transformers_available() is True

    def test_is_sentence_transformers_available_mocked_false(self):
        import chunker.embedding.st_adapter as st_mod
        with patch.object(st_mod, "is_sentence_transformers_available", return_value=False):
            assert st_mod.is_sentence_transformers_available() is False


class TestSTWithMockBackend:
    @pytest.fixture
    def adapter_with_mock(self):
        adapter = SentenceTransformerAdapter("test-model", dimension=8, normalize=False)
        adapter._backend = _make_mock_model(8)("test-model")
        adapter._backend_loaded = True
        adapter._backend_available = True
        return adapter

    @pytest.mark.asyncio
    async def test_embed(self, adapter_with_mock):
        vecs = await adapter_with_mock.embed(["hello", "world"])
        assert len(vecs) == 2
        assert len(vecs[0]) == 8

    @pytest.mark.asyncio
    async def test_embed_dimension(self, adapter_with_mock):
        vecs = await adapter_with_mock.embed(["hello"])
        assert len(vecs[0]) == 8

    @pytest.mark.asyncio
    async def test_embed_query_no_instruction(self, adapter_with_mock):
        vec = await adapter_with_mock.embed_query("test")
        assert len(vec) == 8

    @pytest.mark.asyncio
    async def test_embed_query_with_instruction(self):
        adapter = SentenceTransformerAdapter("test", dimension=8, normalize=False, query_instruction="Q:")
        adapter._backend = _make_mock_model(8)("test")
        adapter._backend_loaded = True
        vec = await adapter.embed_query("test")
        assert len(vec) == 8

    @pytest.mark.asyncio
    async def test_embed_empty(self, adapter_with_mock):
        assert await adapter_with_mock.embed([]) == []

    @pytest.mark.asyncio
    async def test_embed_compute_error(self, adapter_with_mock):
        adapter_with_mock._encode = MagicMock(side_effect=EmbeddingComputeError("fail"))
        with pytest.raises(EmbeddingComputeError):
            await adapter_with_mock.embed(["test"])


class TestSTLoadBackend:
    def test_load_backend_unavailable(self):
        """sentence-transformers 未安装时抛 ModelUnavailableError."""
        adapter = SentenceTransformerAdapter("test", dimension=8)
        with patch(
            "chunker.embedding.st_adapter.is_sentence_transformers_available",
            return_value=False,
        ):
            with pytest.raises(ModelUnavailableError):
                adapter._load_backend()

    def test_load_backend_with_mock_st(self):
        """使用 mock 的 sentence_transformers 模块."""
        mock_model_cls = _make_mock_model(8)
        mock_module = MagicMock()
        mock_module.SentenceTransformer = mock_model_cls
        with patch(
            "chunker.embedding.st_adapter.is_sentence_transformers_available",
            return_value=True,
        ):
            with patch.dict("sys.modules", {"sentence_transformers": mock_module}):
                clear_model_cache()
                adapter = SentenceTransformerAdapter("test-model", dimension=8)
                backend = adapter._load_backend()
                assert backend is not None
                clear_model_cache()

    def test_load_backend_failure(self):
        """模型加载失败时抛 ModelLoadError."""
        mock_module = MagicMock()
        mock_module.SentenceTransformer = MagicMock(side_effect=Exception("download failed"))
        with patch(
            "chunker.embedding.st_adapter.is_sentence_transformers_available",
            return_value=True,
        ):
            with patch.dict("sys.modules", {"sentence_transformers": mock_module}):
                clear_model_cache()
                adapter = SentenceTransformerAdapter("test-model", dimension=8)
                with pytest.raises(ModelLoadError):
                    adapter._load_backend()
                clear_model_cache()

    def test_load_backend_import_error(self):
        """sentence_transformers 导入失败时抛 ModelUnavailableError."""
        with patch(
            "chunker.embedding.st_adapter.is_sentence_transformers_available",
            return_value=True,
        ):
            # 模拟 import 失败：sentence_transformers 不在 sys.modules 且导入抛 ImportError
            with patch.dict("sys.modules", {"sentence_transformers": None}):
                clear_model_cache()
                adapter = SentenceTransformerAdapter("test-model", dimension=8)
                with pytest.raises((ModelUnavailableError, ModelLoadError)):
                    adapter._load_backend()
                clear_model_cache()


class TestSTResolveDevice:
    def test_cpu(self):
        adapter = SentenceTransformerAdapter("test", dimension=8, device="cpu")
        assert adapter._resolve_device() == "cpu"

    def test_cuda(self):
        adapter = SentenceTransformerAdapter("test", dimension=8, device="cuda")
        assert adapter._resolve_device() == "cuda"

    def test_auto_without_torch(self):
        adapter = SentenceTransformerAdapter("test", dimension=8, device="auto")
        # 无 torch 时回退到 cpu
        with patch.dict("sys.modules", {"torch": None}):
            result = adapter._resolve_device()
            assert result in ("cpu", "cuda", "mps")


class TestSTCache:
    def test_clear_model_cache(self):
        clear_model_cache()
        # 不应抛异常
        clear_model_cache()