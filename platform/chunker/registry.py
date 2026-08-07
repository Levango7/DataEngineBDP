"""模态切片器注册机制.

提供运行时动态注册/注销切片器的能力，支持：
    - 装饰器 @register_chunker(modality) 注册切片器类
    - get_chunker(modality) 获取切片器实例
    - list_modalities() 列出所有已注册模态
    - unregister_chunker(modality) 注销切片器
    - clear_registry() 清空注册表（测试用）

注册表维护 (modality -> chunker_class) 映射，
get_chunker 每次返回新实例，避免共享状态。

对齐设计文档 T008-1。
"""
from __future__ import annotations

from threading import RLock
from typing import Type

from chunker.base import BaseChunker
from chunker.exceptions import UnsupportedModalityError
from chunker.models import Modality


class ChunkerRegistry:
    """切片器注册表（线程安全单例风格）.

    使用方式：
        # 1. 装饰器注册
        @register_chunker("text")
        class TextChunker(BaseChunker): ...

        # 2. 获取实例
        chunker = get_chunker("text")
        chunks = await chunker.chunk(content, config)

        # 3. 列出模态
        print(list_modalities())  # ['text', 'image', ...]
    """

    _lock: RLock = RLock()
    _chunkers: dict[str, Type[BaseChunker]] = {}

    @classmethod
    def register(
        cls,
        modality: Modality | str,
        chunker_cls: Type[BaseChunker],
    ) -> Type[BaseChunker]:
        """注册切片器类.

        :param modality: 模态类型
        :param chunker_cls: 切片器类（BaseChunker 子类）
        :return: 注册的类（便于装饰器链式使用）
        :raises TypeError: chunker_cls 不是 BaseChunker 子类
        """
        if not (isinstance(chunker_cls, type) and issubclass(chunker_cls, BaseChunker)):
            raise TypeError(
                f"chunker_cls 必须是 BaseChunker 的子类，得到 {chunker_cls!r}"
            )
        key = modality.value if isinstance(modality, Modality) else str(modality).lower()
        with cls._lock:
            cls._chunkers[key] = chunker_cls
        return chunker_cls

    @classmethod
    def unregister(cls, modality: Modality | str) -> None:
        """注销切片器.

        :param modality: 模态类型
        """
        key = modality.value if isinstance(modality, Modality) else str(modality).lower()
        with cls._lock:
            cls._chunkers.pop(key, None)

    @classmethod
    def get(cls, modality: Modality | str) -> BaseChunker:
        """获取切片器实例.

        :param modality: 模态类型
        :return: 切片器实例（每次新建）
        :raises UnsupportedModalityError: 模态未注册
        """
        key = modality.value if isinstance(modality, Modality) else str(modality).lower()
        with cls._lock:
            chunker_cls = cls._chunkers.get(key)
            if chunker_cls is None:
                raise UnsupportedModalityError(
                    modality=key, available=list(cls._chunkers.keys())
                )
            return chunker_cls()

    @classmethod
    def get_class(cls, modality: Modality | str) -> Type[BaseChunker]:
        """获取切片器类（不实例化）.

        :param modality: 模态类型
        :return: 切片器类
        :raises UnsupportedModalityError: 模态未注册
        """
        key = modality.value if isinstance(modality, Modality) else str(modality).lower()
        with cls._lock:
            chunker_cls = cls._chunkers.get(key)
            if chunker_cls is None:
                raise UnsupportedModalityError(
                    modality=key, available=list(cls._chunkers.keys())
                )
            return chunker_cls

    @classmethod
    def list_modalities(cls) -> list[str]:
        """列出所有已注册模态（按字母排序）."""
        with cls._lock:
            return sorted(cls._chunkers.keys())

    @classmethod
    def is_registered(cls, modality: Modality | str) -> bool:
        """检查模态是否已注册."""
        key = modality.value if isinstance(modality, Modality) else str(modality).lower()
        with cls._lock:
            return key in cls._chunkers

    @classmethod
    def clear(cls) -> None:
        """清空注册表（测试用）."""
        with cls._lock:
            cls._chunkers.clear()


# ----------------------------------------------------------------------
# 模块级便捷 API
# ----------------------------------------------------------------------


def register_chunker(modality: Modality | str):
    """装饰器：注册切片器类.

    用法：
        @register_chunker("text")
        class TextChunker(BaseChunker): ...

    :param modality: 模态类型
    :return: 类装饰器
    """

    def decorator(cls: Type[BaseChunker]) -> Type[BaseChunker]:
        return ChunkerRegistry.register(modality, cls)

    return decorator


def get_chunker(modality: Modality | str) -> BaseChunker:
    """获取已注册切片器实例."""
    return ChunkerRegistry.get(modality)


def list_modalities() -> list[str]:
    """列出所有已注册模态."""
    return ChunkerRegistry.list_modalities()


def unregister_chunker(modality: Modality | str) -> None:
    """注销切片器."""
    ChunkerRegistry.unregister(modality)


def is_chunker_registered(modality: Modality | str) -> bool:
    """检查模态是否已注册."""
    return ChunkerRegistry.is_registered(modality)


def clear_registry() -> None:
    """清空注册表（测试用）."""
    ChunkerRegistry.clear()