"""多模态切片器全局配置 Schema.

支持三种加载方式（优先级从高到低）：
    1. 显式构造参数
    2. 环境变量（前缀 CHUNKER_）
    3. YAML 配置文件（默认 config.yaml，可由 CHUNKER_CONFIG_FILE 指定）

各模态默认配置通过 ModalityDefaults 提供，支持运行时按模态查询。

对齐设计文档 T008-1。
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any, Optional

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from chunker.exceptions import ChunkerConfigError
from chunker.models import ChunkConfig, Modality


class ModalityDefaults(BaseSettings):
    """各模态默认切片配置.

    每个字段对应一个模态的默认 ChunkConfig 字典，
    缺省值对齐业界经验值（文本 512/0.1，表格 50 行/0 行重叠等）。
    """

    text: dict[str, Any] = Field(
        default_factory=lambda: {
            "modality": "text",
            "windowSize": 512,
            "overlap": 0.1,
            "maxTokens": 8192,
            "language": "auto",
        },
        description="文本模态默认配置",
    )
    table: dict[str, Any] = Field(
        default_factory=lambda: {
            "modality": "table",
            "windowSize": 50,
            "overlap": 0.0,
            "maxTokens": 4096,
        },
        description="表格模态默认配置",
    )
    image: dict[str, Any] = Field(
        default_factory=lambda: {
            "modality": "image",
            "windowSize": 1024,
            "overlap": 0.0,
            "maxTokens": 1024,
        },
        description="图像模态默认配置",
    )
    audio: dict[str, Any] = Field(
        default_factory=lambda: {
            "modality": "audio",
            "windowSize": 30000,
            "overlap": 0.1,
            "maxTokens": 2048,
        },
        description="语音模态默认配置（windowSize 单位毫秒）",
    )
    video: dict[str, Any] = Field(
        default_factory=lambda: {
            "modality": "video",
            "windowSize": 60000,
            "overlap": 0.1,
            "maxTokens": 2048,
        },
        description="视频模态默认配置（windowSize 单位毫秒）",
    )
    code: dict[str, Any] = Field(
        default_factory=lambda: {
            "modality": "code",
            "windowSize": 100,
            "overlap": 0.0,
            "maxTokens": 4096,
            "language": "auto",
        },
        description="代码模态默认配置（按 AST 节点切分）",
    )

    def get(self, modality: Modality | str) -> dict[str, Any]:
        """按模态获取默认配置字典.

        :param modality: 模态枚举或字符串
        :return: 配置字典
        :raises ChunkerConfigError: 模态未配置
        """
        key = modality.value if isinstance(modality, Modality) else str(modality).lower()
        try:
            return getattr(self, key)
        except AttributeError as ex:
            raise ChunkerConfigError(f"模态 {key} 未配置默认值") from ex


class ChunkerSettings(BaseSettings):
    """切片器全局配置（环境变量驱动，前缀 CHUNKER_）.

    支持的环境变量：
        CHUNKER_HOST              监听地址
        CHUNKER_PORT              监听端口
        CHUNKER_LOG_LEVEL         日志级别
        CHUNKER_CONFIG_FILE       YAML 配置文件路径
        CHUNKER_DEFAULT_MODALITY  默认模态
        CHUNKER_MAX_CHUNKS        单次切片上限
        CHUNKER_ENABLE_EMBEDDING  是否启用 embedding
        CHUNKER_TOKENIZER         tokenizer 名称
    """

    model_config = SettingsConfigDict(
        env_prefix="CHUNKER_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---- server ----
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8090, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(default="info", description="日志级别")

    # ---- chunker ----
    configFile: Optional[str] = Field(
        default=None, description="YAML 配置文件路径"
    )
    defaultModality: str = Field(
        default="text", description="默认模态"
    )
    maxChunks: int = Field(
        default=10000, gt=0, description="单次切片上限"
    )
    enableEmbedding: bool = Field(
        default=False, description="是否启用 embedding"
    )
    tokenizer: str = Field(
        default="tiktoken", description="tokenizer 名称"
    )

    # ---- 模态默认配置 ----
    modalityDefaults: ModalityDefaults = Field(
        default_factory=ModalityDefaults, description="各模态默认配置"
    )

    @field_validator("logLevel")
    @classmethod
    def _validate_log_level(cls, v: str) -> str:
        allowed = {"debug", "info", "warning", "error", "critical"}
        lv = v.lower()
        if lv not in allowed:
            raise ValueError(f"logLevel 必须为 {allowed} 之一，得到 {v}")
        return lv

    @field_validator("defaultModality")
    @classmethod
    def _validate_default_modality(cls, v: str) -> str:
        try:
            Modality(v.lower())
        except ValueError as ex:
            raise ValueError(f"defaultModality {v} 不是合法模态") from ex
        return v.lower()

    def get_default_config(self, modality: Modality | str) -> ChunkConfig:
        """按模态获取默认 ChunkConfig.

        :param modality: 模态枚举或字符串
        :return: ChunkConfig 实例
        """
        raw = self.modalityDefaults.get(modality)
        try:
            return ChunkConfig(**raw)
        except Exception as ex:
            raise ChunkerConfigError(
                f"模态 {modality} 默认配置非法", cause=ex
            ) from ex

    @classmethod
    def from_yaml(cls, path: str | Path) -> "ChunkerSettings":
        """从 YAML 文件加载配置.

        :param path: YAML 文件路径
        :return: ChunkerSettings 实例
        :raises ChunkerConfigError: 文件不存在或解析失败
        """
        try:
            import yaml  # type: ignore[import-untyped]
        except ImportError as ex:
            raise ChunkerConfigError(
                "加载 YAML 配置需要 PyYAML，请先安装：pip install pyyaml"
            ) from ex

        p = Path(path)
        if not p.exists():
            raise ChunkerConfigError(f"配置文件不存在: {path}")
        try:
            data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        except yaml.YAMLError as ex:
            raise ChunkerConfigError(f"YAML 解析失败: {ex}") from ex
        try:
            return cls(**data)
        except Exception as ex:
            raise ChunkerConfigError(f"配置校验失败: {ex}") from ex


@lru_cache(maxsize=1)
def get_settings() -> ChunkerSettings:
    """获取全局配置单例（带缓存）."""
    return ChunkerSettings()


def reset_settings() -> None:
    """重置配置缓存（测试用）."""
    get_settings.cache_clear()