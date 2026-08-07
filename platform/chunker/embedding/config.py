"""Embedding 适配器配置 (T008-6).

支持三种加载方式（优先级从高到低）：
    1. 显式构造参数
    2. 环境变量（前缀 CHUNKER_EMBEDDING_）
    3. YAML 配置文件

对齐设计文档 T008-6。
"""
from __future__ import annotations

from functools import lru_cache
from typing import Any, Optional

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from chunker.embedding.exceptions import EmbeddingConfigError, InvalidModelError

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 支持的 embedding 模型名（短名 -> 完整模型标识）
SUPPORTED_MODELS: dict[str, str] = {
    "bge-large-zh": "BAAI/bge-large-zh",
    "bge-large-en": "BAAI/bge-large-en",
    "bge-small-zh": "BAAI/bge-small-zh",
    "m3e-base": "moka-ai/m3e-base",
    "m3e-small": "moka-ai/m3e-small",
    "openai": "text-embedding-3-small",
    "openai-small": "text-embedding-3-small",
    "openai-large": "text-embedding-3-large",
}

#: 默认模型短名
DEFAULT_MODEL = "bge-large-zh"

#: 各模型默认向量维度
MODEL_DIMENSIONS: dict[str, int] = {
    "bge-large-zh": 1024,
    "bge-large-en": 1024,
    "bge-small-zh": 512,
    "m3e-base": 768,
    "m3e-small": 384,
    "openai": 1536,
    "openai-small": 1536,
    "openai-large": 3072,
}

#: 各模型归一化标志（bge/m3e 需要归一化，openai 已归一化）
MODEL_NORMALIZE: dict[str, bool] = {
    "bge-large-zh": True,
    "bge-large-en": True,
    "bge-small-zh": True,
    "m3e-base": True,
    "m3e-small": True,
    "openai": False,
    "openai-small": False,
    "openai-large": False,
}

#: OpenAI 默认 API 基址（可指向兼容端点如 Azure OpenAI）
DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"

#: 批量计算默认分块大小
DEFAULT_BATCH_SIZE = 32

#: 异步计算分块大小（每块交由线程池执行）
DEFAULT_ASYNC_CHUNK = 16

#: 单次请求最大文本数
DEFAULT_MAX_TEXTS = 1024


def resolve_model_name(name: str) -> str:
    """将模型短名解析为完整模型标识.

    :param name: 模型短名或完整名（如 ``"bge-large-zh"`` 或 ``"BAAI/bge-large-zh"``）
    :return: 完整模型标识
    :raises InvalidModelError: 模型名未知
    """
    if not name:
        raise InvalidModelError(name, list(SUPPORTED_MODELS.keys()))
    # 完整名直接返回
    if name in SUPPORTED_MODELS.values():
        return name
    # 短名解析
    if name in SUPPORTED_MODELS:
        return SUPPORTED_MODELS[name]
    # 已是 HF 路径形式但未注册：允许透传（便于扩展）
    if "/" in name:
        return name
    raise InvalidModelError(name, list(SUPPORTED_MODELS.keys()))


def model_short_name(name: str) -> str:
    """获取模型短名（用于查维度/归一化标志）.

    :param name: 模型名（短名或完整名）
    :return: 短名；未知返回 ``"custom"``
    """
    if name in SUPPORTED_MODELS:
        return name
    for short, full in SUPPORTED_MODELS.items():
        if name == full:
            return short
    return "custom"


def get_model_dimension(name: str) -> int:
    """获取模型默认向量维度.

    :param name: 模型名（短名或完整名）
    :return: 维度；未知返回 0（表示运行时探测）
    """
    return MODEL_DIMENSIONS.get(model_short_name(name), 0)


def should_normalize(name: str) -> bool:
    """判断模型是否需要对输出向量做 L2 归一化.

    :param name: 模型名（短名或完整名）
    :return: True 表示需要归一化
    """
    return MODEL_NORMALIZE.get(model_short_name(name), True)


class EmbeddingSettings(BaseSettings):
    """Embedding 全局配置（环境变量驱动，前缀 CHUNKER_EMBEDDING_）.

    支持的环境变量：
        CHUNKER_EMBEDDING_MODEL              默认模型短名
        CHUNKER_EMBEDDING_DEVICE             推理设备（cpu/cuda/mps）
        CHUNKER_EMBEDDING_BATCH_SIZE         批量计算分块大小
        CHUNKER_EMBEDDING_ASYNC_CHUNK        异步分块大小
        CHUNKER_EMBEDDING_MAX_TEXTS          单次最大文本数
        CHUNKER_EMBEDDING_OPENAI_API_KEY     OpenAI API Key
        CHUNKER_EMBEDDING_OPENAI_BASE_URL    OpenAI API 基址
        CHUNKER_EMBEDDING_OPENAI_TIMEOUT     OpenAI 请求超时秒
        CHUNKER_EMBEDDING_CACHE_DIR          模型缓存目录
        CHUNKER_EMBEDDING_OFFLINE            是否离线模式（不下载模型）
    """

    model_config = SettingsConfigDict(
        env_prefix="CHUNKER_EMBEDDING_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    model: str = Field(default=DEFAULT_MODEL, description="默认模型短名")
    device: str = Field(default="cpu", description="推理设备")
    batchSize: int = Field(
        default=DEFAULT_BATCH_SIZE, gt=0, description="批量计算分块大小"
    )
    asyncChunk: int = Field(
        default=DEFAULT_ASYNC_CHUNK, gt=0, description="异步分块大小"
    )
    maxTexts: int = Field(
        default=DEFAULT_MAX_TEXTS, gt=0, description="单次最大文本数"
    )

    # ---- OpenAI ----
    openaiApiKey: Optional[str] = Field(
        default=None, description="OpenAI API Key"
    )
    openaiBaseUrl: str = Field(
        default=DEFAULT_OPENAI_BASE_URL, description="OpenAI API 基址"
    )
    openaiTimeout: float = Field(
        default=30.0, gt=0, description="OpenAI 请求超时秒"
    )

    # ---- 通用 ----
    cacheDir: Optional[str] = Field(
        default=None, description="模型缓存目录"
    )
    offline: bool = Field(
        default=False, description="是否离线模式（不下载模型）"
    )

    @field_validator("model")
    @classmethod
    def _validate_model(cls, v: str) -> str:
        try:
            resolve_model_name(v)
        except InvalidModelError as ex:
            raise ValueError(str(ex)) from ex
        return v

    @field_validator("device")
    @classmethod
    def _validate_device(cls, v: str) -> str:
        allowed = {"cpu", "cuda", "mps", "auto"}
        lv = v.lower()
        if lv not in allowed:
            raise ValueError(f"device 必须为 {allowed} 之一，得到 {v}")
        return lv

    def to_dict(self) -> dict[str, Any]:
        """转为字典（便于传递给适配器构造参数）."""
        return self.model_dump()


@lru_cache(maxsize=1)
def get_embedding_settings() -> EmbeddingSettings:
    """获取全局 embedding 配置单例（带缓存）."""
    return EmbeddingSettings()


def reset_embedding_settings() -> None:
    """重置配置缓存（测试用）."""
    get_embedding_settings.cache_clear()