"""注册机制单元测试."""
from __future__ import annotations

import pytest

from chunker.base import BaseChunker
from chunker.exceptions import UnsupportedModalityError
from chunker.models import Chunk, ChunkConfig, Modality
from chunker.registry import (
    ChunkerRegistry,
    clear_registry,
    get_chunker,
    is_chunker_registered,
    list_modalities,
    register_chunker,
    unregister_chunker,
)


# ----------------------------------------------------------------------
# 测试用切片器
# ----------------------------------------------------------------------


class _DummyChunkerA(BaseChunker):
    async def _preprocess(self, content, config):
        return content

    async def _split(self, preprocessed, config):
        return [
            Chunk(
                id="a",
                content=preprocessed,
                metadata=chunker_metadata(config),
            )
        ]

    async def _postprocess(self, chunks, config):
        return chunks


class _DummyChunkerB(BaseChunker):
    async def _preprocess(self, content, config):
        return content

    async def _split(self, preprocessed, config):
        return []

    async def _postprocess(self, chunks, config):
        return chunks


def chunker_metadata(config):
    from chunker.models import ChunkMetadata

    return ChunkMetadata(modality=config.modality)


# ----------------------------------------------------------------------
# 装饰器注册
# ----------------------------------------------------------------------


class TestDecoratorRegister:
    def test_register_returns_class(self):
        @register_chunker("text")
        class MyChunker(_DummyChunkerA):
            pass

        assert MyChunker.__name__ == "MyChunker"
        assert is_chunker_registered("text")

    def test_register_with_enum(self):
        @register_chunker(Modality.IMAGE)
        class ImageChunker(_DummyChunkerB):
            pass

        assert is_chunker_registered(Modality.IMAGE)
        assert "image" in list_modalities()


# ----------------------------------------------------------------------
# 显式注册
# ----------------------------------------------------------------------


class TestExplicitRegister:
    def test_register_class(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        assert is_chunker_registered("text")

    def test_register_invalid_type_raises(self):
        with pytest.raises(TypeError):
            ChunkerRegistry.register("text", object)  # type: ignore[arg-type]

    def test_register_non_subclass_raises(self):
        class NotAChunker:
            pass

        with pytest.raises(TypeError):
            ChunkerRegistry.register("text", NotAChunker)  # type: ignore[arg-type]

    def test_register_overwrite(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        ChunkerRegistry.register("text", _DummyChunkerB)
        cls = ChunkerRegistry.get_class("text")
        assert cls is _DummyChunkerB


# ----------------------------------------------------------------------
# 获取切片器
# ----------------------------------------------------------------------


class TestGetChunker:
    def test_get_returns_instance(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        chunker = get_chunker("text")
        assert isinstance(chunker, _DummyChunkerA)

    def test_get_returns_new_instance_each_time(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        c1 = get_chunker("text")
        c2 = get_chunker("text")
        assert c1 is not c2

    def test_get_unregistered_raises(self):
        with pytest.raises(UnsupportedModalityError) as exc:
            get_chunker("nonexistent")
        assert "nonexistent" in str(exc.value)
        assert exc.value.modality == "nonexistent"

    def test_get_with_enum(self):
        ChunkerRegistry.register(Modality.AUDIO, _DummyChunkerA)
        chunker = get_chunker(Modality.AUDIO)
        assert isinstance(chunker, _DummyChunkerA)

    def test_get_class(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        cls = ChunkerRegistry.get_class("text")
        assert cls is _DummyChunkerA

    def test_get_class_unregistered_raises(self):
        with pytest.raises(UnsupportedModalityError):
            ChunkerRegistry.get_class("nope")


# ----------------------------------------------------------------------
# 列出模态
# ----------------------------------------------------------------------


class TestListModalities:
    def test_empty(self):
        assert list_modalities() == []

    def test_sorted(self):
        ChunkerRegistry.register("image", _DummyChunkerA)
        ChunkerRegistry.register("text", _DummyChunkerA)
        ChunkerRegistry.register("audio", _DummyChunkerB)
        assert list_modalities() == ["audio", "image", "text"]


# ----------------------------------------------------------------------
# 注销
# ----------------------------------------------------------------------


class TestUnregister:
    def test_unregister_existing(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        assert is_chunker_registered("text")
        unregister_chunker("text")
        assert not is_chunker_registered("text")

    def test_unregister_nonexistent_silent(self):
        # 注销未注册模态不应抛错
        unregister_chunker("nonexistent")

    def test_unregister_with_enum(self):
        ChunkerRegistry.register(Modality.IMAGE, _DummyChunkerA)
        unregister_chunker(Modality.IMAGE)
        assert not is_chunker_registered(Modality.IMAGE)


# ----------------------------------------------------------------------
# 检查注册
# ----------------------------------------------------------------------


class TestIsRegistered:
    def test_registered_true(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        assert is_chunker_registered("text") is True

    def test_not_registered_false(self):
        assert is_chunker_registered("text") is False

    def test_check_with_enum(self):
        ChunkerRegistry.register(Modality.VIDEO, _DummyChunkerA)
        assert is_chunker_registered(Modality.VIDEO) is True
        assert is_chunker_registered(Modality.AUDIO) is False


# ----------------------------------------------------------------------
# 清空
# ----------------------------------------------------------------------


class TestClear:
    def test_clear(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        ChunkerRegistry.register("image", _DummyChunkerB)
        clear_registry()
        assert list_modalities() == []
        assert not is_chunker_registered("text")


# ----------------------------------------------------------------------
# 大小写归一化
# ----------------------------------------------------------------------


class TestKeyNormalization:
    def test_uppercase_key_normalized(self):
        ChunkerRegistry.register("TEXT", _DummyChunkerA)
        # 内部存储为小写
        assert is_chunker_registered("text")
        assert is_chunker_registered("TEXT")
        assert "text" in list_modalities()

    def test_get_with_uppercase(self):
        ChunkerRegistry.register("text", _DummyChunkerA)
        chunker = get_chunker("TEXT")
        assert isinstance(chunker, _DummyChunkerA)


# ----------------------------------------------------------------------
# 端到端：注册 -> 获取 -> 切片
# ----------------------------------------------------------------------


class TestEndToEnd:
    async def test_register_and_chunk(self):
        @register_chunker("text")
        class TextChunker(_DummyChunkerA):
            pass

        chunker = get_chunker("text")
        cfg = ChunkConfig(modality=Modality.TEXT)
        chunks = await chunker.chunk("hello", cfg)
        assert len(chunks) == 1
        assert chunks[0].content == "hello"