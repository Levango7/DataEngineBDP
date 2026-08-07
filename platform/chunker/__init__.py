"""Shuqing Big Data Platform - 多模态切片器框架 (T008-1).

提供统一的多模态切片器抽象与注册机制，支持文本/表格/图像/语音/视频/代码等模态。
基于 pydantic v2 + asyncio，对齐设计文档 T008-1。

快速上手：
    from chunker import BaseChunker, ChunkConfig, register_chunker, get_chunker

    @register_chunker("text")
    class TextChunker(BaseChunker):
        async def _preprocess(self, content, config): return content
        async def _split(self, content, config): ...
        async def _postprocess(self, chunks, config): return chunks

    chunker = get_chunker("text")
    chunks = await chunker.chunk("hello world", ChunkConfig(modality="text"))
"""
from __future__ import annotations

from chunker.base import BaseChunker
from chunker.config import (
    ChunkerSettings,
    ModalityDefaults,
    get_settings,
    reset_settings,
)
from chunker.exceptions import (
    ChunkerConfigError,
    ChunkerError,
    ChunkerRuntimeError,
    InvalidOverlapError,
    PreprocessError,
    UnsupportedModalityError,
)
from chunker.models import (
    Chunk,
    ChunkConfig,
    ChunkMetadata,
    ChunkResult,
    Modality,
)
from chunker.registry import (
    ChunkerRegistry,
    clear_registry,
    get_chunker,
    is_chunker_registered,
    list_modalities,
    register_chunker,
    unregister_chunker,
)

# T008-6: Embedding + RAG 子模块（可选导入，依赖未安装时不影响核心功能）
try:
    from chunker import embedding  # noqa: F401
except ImportError:  # pragma: no cover
    pass
try:
    from chunker import rag  # noqa: F401
except ImportError:  # pragma: no cover
    pass

__version__ = "0.1.0"

__all__ = [
    "__version__",
    # 数据模型
    "Chunk",
    "ChunkConfig",
    "ChunkMetadata",
    "ChunkResult",
    "Modality",
    # 抽象基类
    "BaseChunker",
    # 注册机制
    "ChunkerRegistry",
    "register_chunker",
    "get_chunker",
    "list_modalities",
    "unregister_chunker",
    "is_chunker_registered",
    "clear_registry",
    # 配置
    "ChunkerSettings",
    "ModalityDefaults",
    "get_settings",
    "reset_settings",
    # 异常
    "ChunkerError",
    "UnsupportedModalityError",
    "ChunkerConfigError",
    "InvalidOverlapError",
    "ChunkerRuntimeError",
    "PreprocessError",
]