"""Embedding 注册机制测试 (T008-6)."""
from __future__ import annotations

import pytest

from chunker.embedding.base import EmbeddingAdapter
from chunker.embedding.registry import (
    EmbeddingRegistry,
    clear_registry,
    get_adapter,
    is_adapter_registered,
    list_adapters,
    register_adapter,
    unregister_adapter,
)


# ----------------------------------------------------------------------
# 测试用适配器
# ----------------------------------------------------------------------


class TestAdapter(EmbeddingAdapter):
    def __init__(self, model="test-model", **kwargs):
        super().__init__(model, **kwargs)

    def _load_backend(self):
        return "test"

    def _encode(self, texts, backend):
        return [[1.0] for _ in texts]


class TestRegistry:
    def test_register_and_get(self):
        register_adapter("test-adapter", TestAdapter)
        adapter = get_adapter("test-adapter")
        assert isinstance(adapter, TestAdapter)
        # model 参数会被设置为短名（自定义适配器透传）
        assert adapter.name() == "test-adapter"

    def test_register_with_defaults(self):
        register_adapter(
            "test-adapter-defaults",
            TestAdapter,
            defaults={"dimension": 128},
        )
        adapter = get_adapter("test-adapter-defaults")
        assert adapter.dim() == 128

    def test_get_with_kwargs_override(self):
        register_adapter(
            "test-adapter-override",
            TestAdapter,
            defaults={"dimension": 128},
        )
        adapter = get_adapter("test-adapter-override", dimension=256)
        assert adapter.dim() == 256

    def test_list_adapters(self):
        register_adapter("zzz", TestAdapter)
        register_adapter("aaa", TestAdapter)
        adapters = list_adapters()
        assert "zzz" in adapters
        assert "aaa" in adapters
        # 排序
        assert adapters == sorted(adapters)

    def test_is_registered(self):
        register_adapter("check-me", TestAdapter)
        assert is_adapter_registered("check-me") is True
        assert is_adapter_registered("not-registered") is False

    def test_unregister(self):
        register_adapter("temp", TestAdapter)
        unregister_adapter("temp")
        assert is_adapter_registered("temp") is False

    def test_get_unknown_raises(self):
        from chunker.embedding.exceptions import InvalidModelError

        with pytest.raises(InvalidModelError):
            get_adapter("nonexistent")

    def test_register_invalid_class_raises(self):
        with pytest.raises(TypeError):
            EmbeddingRegistry.register("invalid", object)  # type: ignore

    def test_get_class(self):
        register_adapter("get-class", TestAdapter)
        cls = EmbeddingRegistry.get_class("get-class")
        assert cls is TestAdapter

    def test_clear_registry(self):
        register_adapter("to-clear", TestAdapter)
        assert is_adapter_registered("to-clear")
        clear_registry()
        assert not is_adapter_registered("to-clear")
        # 重新注册所有内置适配器（供后续测试使用）
        from chunker.embedding.bge_adapter import (
            BGEAdapter, BGE_LARGE_ZH_MODEL, BGE_LARGE_EN_MODEL, BGE_SMALL_ZH_MODEL,
            BGE_ZH_QUERY_INSTRUCTION, BGE_EN_QUERY_INSTRUCTION,
        )
        from chunker.embedding.m3e_adapter import M3EAdapter, M3E_BASE_MODEL, M3E_SMALL_MODEL
        from chunker.embedding.openai_adapter import (
            OpenAIAdapter, OPENAI_SMALL_MODEL, OPENAI_LARGE_MODEL,
        )
        # BGE
        register_adapter("bge-large-zh", BGEAdapter, defaults={"dimension": 1024, "query_instruction": BGE_ZH_QUERY_INSTRUCTION})
        register_adapter("bge-large-en", BGEAdapter, defaults={"model": BGE_LARGE_EN_MODEL, "dimension": 1024, "query_instruction": BGE_EN_QUERY_INSTRUCTION})
        register_adapter("bge-small-zh", BGEAdapter, defaults={"model": BGE_SMALL_ZH_MODEL, "dimension": 512, "query_instruction": BGE_ZH_QUERY_INSTRUCTION})
        # M3E
        register_adapter("m3e-base", M3EAdapter, defaults={"dimension": 768})
        register_adapter("m3e-small", M3EAdapter, defaults={"model": M3E_SMALL_MODEL, "dimension": 384})
        # OpenAI
        register_adapter("openai", OpenAIAdapter, defaults={"dimension": 1536})
        register_adapter("openai-small", OpenAIAdapter, defaults={"model": OPENAI_SMALL_MODEL, "dimension": 1536})
        register_adapter("openai-large", OpenAIAdapter, defaults={"model": OPENAI_LARGE_MODEL, "dimension": 3072})

    def test_bge_registered(self):
        # BGE 适配器应已注册
        assert is_adapter_registered("bge-large-zh")
        assert is_adapter_registered("bge-large-en")
        assert is_adapter_registered("bge-small-zh")

    def test_m3e_registered(self):
        assert is_adapter_registered("m3e-base")
        assert is_adapter_registered("m3e-small")

    def test_openai_registered(self):
        assert is_adapter_registered("openai")
        assert is_adapter_registered("openai-small")
        assert is_adapter_registered("openai-large")