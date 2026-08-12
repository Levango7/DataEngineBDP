"""模型相关数据模型."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field, model_validator

from llmops.models.base import ModelStatus, ModelType, TimestampMixin, utc_now


class ModelParams(BaseModel):
    """模型参数（基座参数 / 微调超参等）."""

    # 基座参数规模（如 "7B", "1.3B"）
    paramSize: Optional[str] = Field(default=None, description="参数规模，如 7B/1.3B")
    # 上下文长度
    contextLength: Optional[int] = Field(default=None, ge=1, description="上下文长度")
    # 模型架构（如 "qwen2", "llama"）
    architecture: Optional[str] = Field(default=None)
    # 微调方法（如 "lora", "qlora", "full"）
    finetuneMethod: Optional[str] = Field(default=None)
    # 任意扩展字段
    extra: dict[str, Any] = Field(default_factory=dict)


class ModelVersion(BaseModel):
    """模型版本（对齐 MLflow Model Registry 版本概念）."""

    version: int = Field(..., ge=1, description="版本号，从 1 开始递增")
    modelId: str = Field(..., description="所属模型 ID")
    # 版本对应的运行/训练任务 ID（如有）
    sourceRunId: Optional[str] = Field(default=None, description="来源训练任务 ID")
    # artifact 路径（MLflow 中为 model artifact uri）
    artifactUri: Optional[str] = Field(default=None)
    # 版本说明
    description: Optional[str] = Field(default=None)
    # 是否为当前生产版本
    isProduction: bool = Field(default=False)
    createdAt: datetime = Field(default_factory=utc_now)


class ModelInfo(TimestampMixin):
    """模型信息（注册表条目）.

    对齐设计：{ name, type(base|ft), base?, params }
    """

    id: str = Field(..., description="模型 ID（UUID）")
    name: str = Field(..., min_length=1, max_length=128, description="模型名称")
    type: ModelType = Field(..., description="模型类型: base/ft")
    # 微调模型的基座模型 ID（type=ft 时必填）
    baseModelId: Optional[str] = Field(default=None, description="基座模型 ID（仅 type=ft 时有效）")
    params: ModelParams = Field(default_factory=ModelParams, description="模型参数")
    status: ModelStatus = Field(default=ModelStatus.DRAFT, description="模型状态")
    description: Optional[str] = Field(default=None, description="模型描述")
    # 标签（用于过滤与资产化）
    tags: dict[str, str] = Field(default_factory=dict)
    # 版本列表（内存态冗余，便于 API 直接返回）
    versions: list[ModelVersion] = Field(default_factory=list)
    # 当前生产版本号
    currentVersion: Optional[int] = Field(default=None, description="当前生产版本号")

    @model_validator(mode="after")
    def _validate_base_model(self) -> "ModelInfo":
        """微调模型必须指定基座模型，基座模型不应指定基座."""
        if self.type == ModelType.FT and not self.baseModelId:
            raise ValueError("微调模型(type=ft)必须指定 baseModelId")
        if self.type == ModelType.BASE and self.baseModelId is not None:
            raise ValueError("基座模型(type=base)不应指定 baseModelId")
        return self


class ModelFilter(BaseModel):
    """模型列表过滤条件."""

    name: Optional[str] = Field(default=None, description="按名称模糊匹配")
    type: Optional[ModelType] = Field(default=None, description="按类型过滤")
    status: Optional[ModelStatus] = Field(default=None, description="按状态过滤")
    tag: Optional[str] = Field(default=None, description="按标签过滤，格式 key=value")
    limit: int = Field(default=100, ge=1, le=1000, description="返回上限")
    offset: int = Field(default=0, ge=0, description="偏移量")
