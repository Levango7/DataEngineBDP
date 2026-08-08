"""多模态切片器数据模型.

模型层次：
    Modality           模态枚举（文本/表格/图像/语音/视频/代码）
    ChunkMetadata      切片元数据（模态/来源/位置信息）
    Chunk              单个切片
    ChunkConfig        切片配置
    ChunkResult        切片结果聚合

对齐设计文档 T008-1：多模态切片器框架与接口抽象。
所有模型基于 pydantic v2 BaseModel，启用严格校验。
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field, field_validator


def utc_now() -> datetime:
    """返回当前 UTC 时间（带 tzinfo），便于测试 mock."""
    return datetime.now(timezone.utc)


class Modality(str, Enum):
    """模态类型枚举.

    - TEXT:   文本（Markdown / 纯文本 / HTML）
    - TABLE:  表格（CSV / DataFrame / Excel）
    - IMAGE:  图像（PNG / JPEG / TIFF / 多页文档扫描件）
    - AUDIO:  语音（WAV / MP3 / FLAC）
    - VIDEO:  视频（MP4 / WebM）
    - CODE:   代码（按 AST 切分）
    """

    TEXT = "text"
    TABLE = "table"
    IMAGE = "image"
    AUDIO = "audio"
    VIDEO = "video"
    CODE = "code"


class ChunkMetadata(BaseModel):
    """切片元数据.

    携带模态类型、来源引用、位置信息及扩展属性，
    用于切片溯源、检索过滤与下游融合。
    """

    modality: Modality = Field(..., description="模态类型")
    source: str = Field(default="", description="来源标识（如文件路径 / 表名 / URL）")
    # 位置信息：start/end 表示在源内容中的偏移（字符/字节/行/帧）
    start: int = Field(default=0, ge=0, description="起始位置")
    end: int = Field(default=0, ge=0, description="结束位置")
    index: int = Field(default=0, ge=0, description="切片序号（从 0 开始）")
    # 扩展属性：模态专属信息（如 image 的 bbox、audio 的 startTime/endTime）
    extra: dict[str, Any] = Field(default_factory=dict, description="扩展属性")

    @field_validator("end")
    @classmethod
    def _validate_end(cls, v: int, info) -> int:
        start = info.data.get("start", 0)
        if v < start:
            raise ValueError(f"end({v}) 不能小于 start({start})")
        return v


class Chunk(BaseModel):
    """单个切片.

    字段：
        id:         切片全局唯一 ID
        content:    切片内容（文本字符串 / 图像 bytes / 表格 dict 等）
        metadata:   切片元数据
        embedding:  向量嵌入（Optional，由下游 embedding 服务填充）
        tokens:     token 计数（Optional，由切片器填充）
        createdAt:  创建时间戳
    """

    id: str = Field(..., min_length=1, description="切片 ID")
    content: Any = Field(..., description="切片内容")
    metadata: ChunkMetadata = Field(..., description="切片元数据")
    embedding: Optional[list[float]] = Field(default=None, description="向量嵌入")
    tokens: Optional[int] = Field(default=None, ge=0, description="token 计数")
    createdAt: datetime = Field(default_factory=utc_now, description="创建时间")

    def with_embedding(self, embedding: list[float]) -> "Chunk":
        """返回带 embedding 的副本（不可变更新）."""
        return self.model_copy(update={"embedding": embedding})

    def with_tokens(self, tokens: int) -> "Chunk":
        """返回带 tokens 的副本（不可变更新）."""
        return self.model_copy(update={"tokens": tokens})


class ChunkConfig(BaseModel):
    """切片配置.

    通用字段：
        modality:       模态类型
        windowSize:     窗口大小（文本: 字符数 / 表格: 行数 / 图像: 像素 / 音频: 毫秒）
        overlap:        重叠率（0.0 ~ 1.0），表示 overlap/windowSize
        maxTokens:      单切片最大 token 数（硬截断）
        minChunkSize:   最小切片大小（小于此值不切分）
        language:       语言提示（文本/代码模态）
        extra:          模态专属配置扩展
    """

    modality: Modality = Field(..., description="模态类型")
    windowSize: int = Field(default=512, gt=0, description="窗口大小")
    overlap: float = Field(default=0.1, ge=0.0, lt=1.0, description="重叠率 [0, 1)")
    maxTokens: int = Field(default=8192, gt=0, description="单切片最大 token 数")
    minChunkSize: int = Field(default=1, gt=0, description="最小切片大小")
    language: str = Field(default="auto", description="语言提示")
    extra: dict[str, Any] = Field(default_factory=dict, description="模态专属配置")

    @field_validator("overlap")
    @classmethod
    def _validate_overlap(cls, v: float) -> float:
        if not 0.0 <= v < 1.0:
            raise ValueError(f"overlap 必须满足 0 <= overlap < 1，得到 {v}")
        return v

    def overlap_size(self) -> int:
        """计算重叠的绝对大小（基于 windowSize）."""
        return int(self.windowSize * self.overlap)

    def stride(self) -> int:
        """计算步长 = windowSize - overlap_size，至少为 1."""
        return max(1, self.windowSize - self.overlap_size())


class ChunkResult(BaseModel):
    """切片结果聚合.

    字段：
        chunks:         切片列表
        totalTokens:    总 token 数
        durationMs:     处理耗时（毫秒）
        modality:       模态类型（冗余字段，便于聚合查询）
        source:         来源标识
    """

    chunks: list[Chunk] = Field(default_factory=list, description="切片列表")
    totalTokens: int = Field(default=0, ge=0, description="总 token 数")
    durationMs: float = Field(default=0.0, ge=0.0, description="处理耗时（毫秒）")
    modality: Optional[Modality] = Field(default=None, description="模态类型")
    source: str = Field(default="", description="来源标识")

    @property
    def count(self) -> int:
        """切片数量."""
        return len(self.chunks)
