"""Embedding 适配器注册机制 (T008-6).

提供运行时动态注册/注销适配器的能力，支持：
    - 装饰器 @register_adapter(name) 注册适配器类
    - get_adapter(name) 获取适配器实例
    - list_adapters() 列出所有已注册适配器
    - unregister_adapter(name) 注销适配器
    - clear_registry() 清空注册表（测试用）

注册表维护 (name -> adapter_factory) 映射，
get_adapter 每次返回新实例，避免共享状态。

对齐设计文档 T008-6。
"""
from __future__ import annotations

from threading import RLock
from typing import Any, Callable, Type

from chunker.embedding.base import EmbeddingAdapter
from chunker.embedding.config import resolve_model_name
from chunker.embedding.exceptions import InvalidModelError


class EmbeddingRegistry:
    """Embedding 适配器注册表（线程安全单例风格）.

    使用方式：
        # 1. 装饰器注册
        @register_adapter("bge-large-zh")
        class BGEAdapter(EmbeddingAdapter): ...

        # 2. 获取实例
        adapter = get_adapter("bge-large-zh")
        vecs = await adapter.embed(["hello"])
    """

    _lock: RLock = RLock()
    # 短名 -> 适配器类
    _adapters: dict[str, Type[EmbeddingAdapter]] = {}
    # 短名 -> 默认构造参数
    _defaults: dict[str, dict[str, Any]] = {}

    @classmethod
    def register(
        cls,
        name: str,
        adapter_cls: Type[EmbeddingAdapter],
        *,
        defaults: dict[str, Any] | None = None,
    ) -> Type[EmbeddingAdapter]:
        """注册适配器类.

        :param name: 模型短名（如 ``"bge-large-zh"``）
        :param adapter_cls: 适配器类（EmbeddingAdapter 子类）
        :param defaults: 默认构造参数
        :return: 注册的类（便于装饰器链式使用）
        :raises TypeError: adapter_cls 不是 EmbeddingAdapter 子类
        """
        if not (
            isinstance(adapter_cls, type)
            and issubclass(adapter_cls, EmbeddingAdapter)
        ):
            raise TypeError(
                f"adapter_cls 必须是 EmbeddingAdapter 的子类，得到 {adapter_cls!r}"
            )
        with cls._lock:
            cls._adapters[name] = adapter_cls
            if defaults is not None:
                cls._defaults[name] = dict(defaults)
        return adapter_cls

    @classmethod
    def unregister(cls, name: str) -> None:
        """注销适配器.

        :param name: 模型短名
        """
        with cls._lock:
            cls._adapters.pop(name, None)
            cls._defaults.pop(name, None)

    @classmethod
    def get(
        cls,
        name: str,
        **kwargs: Any,
    ) -> EmbeddingAdapter:
        """获取适配器实例.

        :param name: 模型短名或完整名
        :param kwargs: 构造参数（覆盖默认参数）
        :return: 适配器实例（每次新建）
        :raises InvalidModelError: 适配器未注册
        """
        short = cls._resolve_short_name(name)
        with cls._lock:
            adapter_cls = cls._adapters.get(short)
            if adapter_cls is None:
                raise InvalidModelError(
                    name, available=list(cls._adapters.keys())
                )
            defaults = dict(cls._defaults.get(short, {}))
        # 合并默认参数与显式参数
        defaults.update(kwargs)
        # 传入完整模型名：仅对已知模型做 resolve，自定义适配器透传短名
        if "model" not in defaults:
            try:
                defaults["model"] = resolve_model_name(short)
            except InvalidModelError:
                defaults["model"] = short
        return adapter_cls(**defaults)

    @classmethod
    def get_class(cls, name: str) -> Type[EmbeddingAdapter]:
        """获取适配器类（不实例化）.

        :param name: 模型短名或完整名
        :return: 适配器类
        :raises InvalidModelError: 适配器未注册
        """
        short = cls._resolve_short_name(name)
        with cls._lock:
            adapter_cls = cls._adapters.get(short)
            if adapter_cls is None:
                raise InvalidModelError(
                    name, available=list(cls._adapters.keys())
                )
            return adapter_cls

    @classmethod
    def list_adapters(cls) -> list[str]:
        """列出所有已注册适配器短名（按字母排序）."""
        with cls._lock:
            return sorted(cls._adapters.keys())

    @classmethod
    def is_registered(cls, name: str) -> bool:
        """检查适配器是否已注册."""
        try:
            short = cls._resolve_short_name(name)
        except InvalidModelError:
            return False
        with cls._lock:
            return short in cls._adapters

    @classmethod
    def clear(cls) -> None:
        """清空注册表（测试用）."""
        with cls._lock:
            cls._adapters.clear()
            cls._defaults.clear()

    @classmethod
    def _resolve_short_name(cls, name: str) -> str:
        """将模型名解析为短名.

        :param name: 模型短名或完整名
        :return: 短名
        :raises InvalidModelError: 未知模型
        """
        from chunker.embedding.config import SUPPORTED_MODELS, model_short_name

        if name in SUPPORTED_MODELS:
            return name
        short = model_short_name(name)
        if short != "custom":
            return short
        # 未知短名：可能是用户自定义注册的
        return name


# ----------------------------------------------------------------------
# 模块级便捷 API
# ----------------------------------------------------------------------


def register_adapter(
    name: str,
    cls: Optional[Type[EmbeddingAdapter]] = None,
    *,
    defaults: dict[str, Any] | None = None,
) -> Any:
    """注册适配器，支持装饰器与函数两种调用方式.

    用法 1（装饰器）：
        @register_adapter("bge-large-zh", defaults={"dimension": 1024})
        class BGEAdapter(EmbeddingAdapter): ...

    用法 2（函数）：
        register_adapter("bge-large-en", BGEAdapter, defaults={...})

    :param name: 模型短名
    :param cls: 适配器类（函数调用时提供，装饰器调用时省略）
    :param defaults: 默认构造参数
    :return: 装饰器调用时返回类装饰器；函数调用时返回注册的类
    """
    if cls is not None:
        # 函数调用方式
        return EmbeddingRegistry.register(name, cls, defaults=defaults)

    # 装饰器调用方式
    def decorator(cls: Type[EmbeddingAdapter]) -> Type[EmbeddingAdapter]:
        return EmbeddingRegistry.register(name, cls, defaults=defaults)

    return decorator


def get_adapter(name: str, **kwargs: Any) -> EmbeddingAdapter:
    """获取已注册适配器实例."""
    return EmbeddingRegistry.get(name, **kwargs)


def list_adapters() -> list[str]:
    """列出所有已注册适配器短名."""
    return EmbeddingRegistry.list_adapters()


def unregister_adapter(name: str) -> None:
    """注销适配器."""
    EmbeddingRegistry.unregister(name)


def is_adapter_registered(name: str) -> bool:
    """检查适配器是否已注册."""
    return EmbeddingRegistry.is_registered(name)


def clear_registry() -> None:
    """清空注册表（测试用）."""
    EmbeddingRegistry.clear()