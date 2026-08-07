"""Embedding 适配器异常定义 (T008-6).

异常层次：
    EmbeddingError                       Embedding 基础异常
    ├── EmbeddingConfigError             配置错误
    │   └── InvalidModelError            模型名非法
    ├── EmbeddingRuntimeError            运行时错误
    │   ├── ModelLoadError               模型加载失败
    │   ├── EmbeddingComputeError        向量计算失败
    │   └── ModelUnavailableError        模型不可用（依赖缺失/离线）
    └── EmbeddingDimensionError          向量维度不匹配

对齐设计文档 T008-6：Embedding 模型适配与 RAG 管道集成。
"""
from __future__ import annotations


class EmbeddingError(Exception):
    """Embedding 基础异常.

    所有 embedding 模块内抛出的异常均应继承自此基类，
    便于上层调用方统一捕获并区分业务异常与系统异常。
    """

    def __init__(self, message: str = "", *, cause: Exception | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.cause = cause

    def __str__(self) -> str:
        if self.cause is not None:
            return f"{self.message} (cause: {self.cause!r})"
        return self.message


class EmbeddingConfigError(EmbeddingError):
    """Embedding 配置错误.

    当 EmbeddingSettings / 适配器参数校验失败时抛出。
    """


class InvalidModelError(EmbeddingConfigError):
    """模型名非法.

    当调用方请求未注册或未知的 embedding 模型时抛出。
    """

    def __init__(self, model: str, available: list[str] | None = None) -> None:
        self.model = model
        self.available = available or []
        hint = f"，可用模型: {self.available}" if self.available else ""
        super().__init__(f"非法的 embedding 模型: {model}{hint}")


class EmbeddingRuntimeError(EmbeddingError):
    """Embedding 运行时错误.

    模型加载、向量计算等运行时异常基类。
    """


class ModelLoadError(EmbeddingRuntimeError):
    """模型加载失败.

    当 sentence-transformers / openai 等后端加载模型失败时抛出。
    """


class EmbeddingComputeError(EmbeddingRuntimeError):
    """向量计算失败.

    当 embedding 计算过程中出现异常（输入非法、推理失败等）时抛出。
    """


class ModelUnavailableError(EmbeddingRuntimeError):
    """模型不可用.

    当依赖库未安装或模型离线且无回退时抛出。
    """

    def __init__(self, model: str, reason: str = "") -> None:
        self.model = model
        msg = f"embedding 模型不可用: {model}"
        if reason:
            msg = f"{msg}，原因: {reason}"
        super().__init__(msg)


class EmbeddingDimensionError(EmbeddingError):
    """向量维度不匹配.

    当计算得到的向量维度与模型声明维度不一致时抛出。
    """

    def __init__(self, expected: int, actual: int) -> None:
        self.expected = expected
        self.actual = actual
        super().__init__(
            f"embedding 维度不匹配，期望 {expected}，实际 {actual}"
        )