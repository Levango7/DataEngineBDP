"""Embedding 适配器模块 (T008-6).

提供统一的 Embedding 适配器抽象与注册机制，支持：
    - bge-large-zh / bge-large-en / bge-small-zh（基于 sentence-transformers）
    - m3e-base / m3e-small（基于 sentence-transformers）
    - openai / openai-small / openai-large（基于 OpenAI API）

基于 T008-1~5 多模态切片器框架，对齐设计文档 T008-6。

快速上手：
    from chunker.embedding import get_adapter

    adapter = get_adapter("bge-large-zh")
    vecs = await adapter.embed(["hello", "world"])
    query_vec = await adapter.embed_query("检索查询")
"""
from __future__ import annotations

# 触发适配器注册（导入即注册）
from chunker.embedding import bge_adapter, m3e_adapter, openai_adapter  # noqa: F401
from chunker.embedding.base import EmbeddingAdapter
from chunker.embedding.bge_adapter import (
    BGE_EN_QUERY_INSTRUCTION,
    BGE_LARGE_EN_MODEL,
    BGE_LARGE_ZH_MODEL,
    BGE_SMALL_ZH_MODEL,
    BGE_ZH_QUERY_INSTRUCTION,
    BGEAdapter,
)
from chunker.embedding.config import (
    DEFAULT_MODEL,
    MODEL_DIMENSIONS,
    SUPPORTED_MODELS,
    EmbeddingSettings,
    get_embedding_settings,
    get_model_dimension,
    model_short_name,
    resolve_model_name,
    reset_embedding_settings,
    should_normalize,
)
from chunker.embedding.exceptions import (
    EmbeddingComputeError,
    EmbeddingConfigError,
    EmbeddingDimensionError,
    EmbeddingError,
    EmbeddingRuntimeError,
    InvalidModelError,
    ModelLoadError,
    ModelUnavailableError,
)
from chunker.embedding.m3e_adapter import (
    M3E_BASE_MODEL,
    M3E_SMALL_MODEL,
    M3EAdapter,
)
from chunker.embedding.openai_adapter import (
    DEFAULT_BASE_URL,
    MOCK_API_KEY,
    OPENAI_LARGE_MODEL,
    OPENAI_SMALL_MODEL,
    OpenAIAdapter,
    is_openai_available,
)
from chunker.embedding.registry import (
    EmbeddingRegistry,
    clear_registry,
    get_adapter,
    is_adapter_registered,
    list_adapters,
    register_adapter,
    unregister_adapter,
)
from chunker.embedding.st_adapter import (
    SentenceTransformerAdapter,
    is_sentence_transformers_available,
)

__version__ = "0.1.0"

__all__ = [
    "__version__",
    # 抽象基类
    "EmbeddingAdapter",
    "SentenceTransformerAdapter",
    # 适配器
    "BGEAdapter",
    "M3EAdapter",
    "OpenAIAdapter",
    # 注册机制
    "EmbeddingRegistry",
    "register_adapter",
    "get_adapter",
    "list_adapters",
    "unregister_adapter",
    "is_adapter_registered",
    "clear_registry",
    # 配置
    "EmbeddingSettings",
    "get_embedding_settings",
    "reset_embedding_settings",
    "resolve_model_name",
    "model_short_name",
    "get_model_dimension",
    "should_normalize",
    "SUPPORTED_MODELS",
    "MODEL_DIMENSIONS",
    "DEFAULT_MODEL",
    # 异常
    "EmbeddingError",
    "EmbeddingConfigError",
    "InvalidModelError",
    "EmbeddingRuntimeError",
    "ModelLoadError",
    "EmbeddingComputeError",
    "ModelUnavailableError",
    "EmbeddingDimensionError",
    # 模型常量
    "BGE_LARGE_ZH_MODEL",
    "BGE_LARGE_EN_MODEL",
    "BGE_SMALL_ZH_MODEL",
    "BGE_ZH_QUERY_INSTRUCTION",
    "BGE_EN_QUERY_INSTRUCTION",
    "M3E_BASE_MODEL",
    "M3E_SMALL_MODEL",
    "OPENAI_SMALL_MODEL",
    "OPENAI_LARGE_MODEL",
    "DEFAULT_BASE_URL",
    "MOCK_API_KEY",
    # 可用性检查
    "is_sentence_transformers_available",
    "is_openai_available",
]