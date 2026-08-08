"""多模态切片器异常定义.

异常层次：
    ChunkerError                       切片器基础异常
    ├── UnsupportedModalityError       不支持的模态
    ├── ChunkerConfigError             配置错误
    │   └── InvalidOverlapError        重叠率配置非法
    └── ChunkerRuntimeError            运行时错误
        └── PreprocessError            预处理失败

对齐设计文档 T008-1：多模态切片器框架与接口抽象。
"""

from __future__ import annotations


class ChunkerError(Exception):
    """切片器基础异常.

    所有 chunker 模块内抛出的异常均应继承自此基类，
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


class UnsupportedModalityError(ChunkerError):
    """不支持的模态错误.

    当调用方请求未注册的模态切片器时抛出。
    """

    def __init__(self, modality: str, available: list[str] | None = None) -> None:
        self.modality = modality
        self.available = available or []
        hint = f"，可用模态: {self.available}" if self.available else ""
        super().__init__(f"不支持的模态: {modality}{hint}")


class ChunkerConfigError(ChunkerError):
    """切片器配置错误.

    当 ChunkConfig / ChunkerSettings 校验失败或语义非法时抛出。
    """


class InvalidOverlapError(ChunkerConfigError):
    """重叠率配置非法.

    overlap 必须满足 0 <= overlap < windowSize，
    否则切片会出现无限循环或重复率 100% 的退化情形。
    """

    def __init__(self, overlap: float, windowSize: int) -> None:
        self.overlap = overlap
        self.windowSize = windowSize
        super().__init__(f"重叠率 overlap={overlap} 非法，要求 0 <= overlap < windowSize={windowSize}")


class ChunkerRuntimeError(ChunkerError):
    """切片器运行时错误.

    预处理/切分/后处理阶段抛出的运行时异常基类。
    """


class PreprocessError(ChunkerRuntimeError):
    """预处理失败."""
