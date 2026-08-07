"""RAG 管道配置 (T008-6).

支持三种加载方式（优先级从高到低）：
    1. 显式构造参数
    2. 环境变量（前缀 CHUNKER_RAG_）
    3. YAML 配置文件

对齐设计文档 T008-6。
"""
from __future__ import annotations

from functools import lru_cache
from typing import Any, Optional

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 默认向量度量类型
DEFAULT_METRIC_TYPE = "COSINE"

#: 默认索引类型
DEFAULT_INDEX_TYPE = "HNSW"

#: 默认检索 topK
DEFAULT_TOP_K = 10

#: 默认融合方法
DEFAULT_FUSION_METHOD = "rrf"  # rrf / weighted

#: RRF 默认参数 k
DEFAULT_RRF_K = 60

#: 默认多模态权重（text/table/image/audio）
DEFAULT_MODALITY_WEIGHTS: dict[str, float] = {
    "text": 1.0,
    "table": 1.0,
    "image": 0.8,
    "audio": 0.8,
    "video": 0.6,
    "code": 1.0,
}

#: 支持的度量类型
SUPPORTED_METRIC_TYPES = frozenset({"L2", "IP", "COSINE"})

#: 支持的索引类型
SUPPORTED_INDEX_TYPES = frozenset({"FLAT", "IVF_FLAT", "HNSW", "IVF_PQ"})

#: 支持的融合方法
SUPPORTED_FUSION_METHODS = frozenset({"rrf", "weighted"})


class RAGSettings(BaseSettings):
    """RAG 全局配置（环境变量驱动，前缀 CHUNKER_RAG_）.

    支持的环境变量：
        CHUNKER_RAG_DEFAULT_COLLECTION     默认集合名
        CHUNKER_RAG_EMBEDDING_MODEL        默认 embedding 模型短名
        CHUNKER_RAG_METRIC_TYPE            默认度量类型
        CHUNKER_RAG_INDEX_TYPE             默认索引类型
        CHUNKER_RAG_TOP_K                  默认检索 topK
        CHUNKER_RAG_FUSION_METHOD          默认融合方法
        CHUNKER_RAG_RRF_K                  RRF k 参数
        CHUNKER_RAG_MILVUS_HOST            Milvus 主机
        CHUNKER_RAG_MILVUS_PORT            Milvus 端口
        CHUNKER_RAG_MILVUS_DATABASE        Milvus 数据库
        CHUNKER_RAG_MILVUS_USERNAME        Milvus 用户名
        CHUNKER_RAG_MILVUS_PASSWORD        Milvus 密码
        CHUNKER_RAG_STORE_TYPE             存储类型（milvus/mock）
    """

    model_config = SettingsConfigDict(
        env_prefix="CHUNKER_RAG_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    defaultCollection: str = Field(
        default="chunks", description="默认集合名"
    )
    embeddingModel: str = Field(
        default="bge-large-zh", description="默认 embedding 模型短名"
    )
    metricType: str = Field(
        default=DEFAULT_METRIC_TYPE, description="默认度量类型"
    )
    indexType: str = Field(
        default=DEFAULT_INDEX_TYPE, description="默认索引类型"
    )
    topK: int = Field(default=DEFAULT_TOP_K, gt=0, description="默认检索 topK")
    fusionMethod: str = Field(
        default=DEFAULT_FUSION_METHOD, description="默认融合方法"
    )
    rrfK: int = Field(default=DEFAULT_RRF_K, gt=0, description="RRF k 参数")

    # ---- Milvus ----
    storeType: str = Field(
        default="mock", description="存储类型（milvus/mock）"
    )
    milvusHost: str = Field(default="127.0.0.1", description="Milvus 主机")
    milvusPort: int = Field(default=19530, gt=0, le=65535, description="Milvus 端口")
    milvusDatabase: str = Field(default="default", description="Milvus 数据库")
    milvusUsername: Optional[str] = Field(default=None, description="Milvus 用户名")
    milvusPassword: Optional[str] = Field(default=None, description="Milvus 密码")

    # ---- 多模态权重 ----
    modalityWeights: dict[str, float] = Field(
        default_factory=lambda: dict(DEFAULT_MODALITY_WEIGHTS),
        description="多模态融合权重",
    )

    @field_validator("metricType")
    @classmethod
    def _validate_metric(cls, v: str) -> str:
        uv = v.upper()
        if uv not in SUPPORTED_METRIC_TYPES:
            raise ValueError(
                f"metricType 必须为 {SUPPORTED_METRIC_TYPES} 之一，得到 {v}"
            )
        return uv

    @field_validator("indexType")
    @classmethod
    def _validate_index(cls, v: str) -> str:
        uv = v.upper()
        if uv not in SUPPORTED_INDEX_TYPES:
            raise ValueError(
                f"indexType 必须为 {SUPPORTED_INDEX_TYPES} 之一，得到 {v}"
            )
        return uv

    @field_validator("fusionMethod")
    @classmethod
    def _validate_fusion(cls, v: str) -> str:
        lv = v.lower()
        if lv not in SUPPORTED_FUSION_METHODS:
            raise ValueError(
                f"fusionMethod 必须为 {SUPPORTED_FUSION_METHODS} 之一，得到 {v}"
            )
        return lv

    @field_validator("storeType")
    @classmethod
    def _validate_store(cls, v: str) -> str:
        lv = v.lower()
        if lv not in {"milvus", "mock"}:
            raise ValueError(f"storeType 必须为 milvus/mock，得到 {v}")
        return lv

    def to_dict(self) -> dict[str, Any]:
        """转为字典."""
        return self.model_dump()


@lru_cache(maxsize=1)
def get_rag_settings() -> RAGSettings:
    """获取全局 RAG 配置单例（带缓存）."""
    return RAGSettings()


def reset_rag_settings() -> None:
    """重置配置缓存（测试用）."""
    get_rag_settings.cache_clear()