"""训练/微调相关数据模型."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field, model_validator

from llmops.models.base import TimestampMixin, utc_now


class TrainingConfig(BaseModel):
    """训练任务配置.

    对齐设计：{ baseModel, dataset, epochs, gpu, lr }
    """

    # 基座模型 ID（必填）
    baseModelId: str = Field(..., description="基座模型 ID")
    # 输出模型名称（微调后注册的新模型名）
    outputModelName: str = Field(..., min_length=1, description="输出模型名称")
    # 训练数据集 ID 或 URI
    dataset: str = Field(..., description="训练数据集 ID 或 URI")
    # 训练轮数
    epochs: int = Field(default=3, ge=1, le=100, description="训练轮数")
    # 学习率
    learningRate: float = Field(default=2e-5, gt=0, lt=1, description="学习率")
    # GPU 资源（卡数）
    gpu: int = Field(default=1, ge=1, le=64, description="GPU 卡数")
    # 批大小
    batchSize: int = Field(default=8, ge=1, le=1024, description="批大小")
    # 微调方法
    finetuneMethod: str = Field(default="lora", description="微调方法: lora/qlora/full")
    # 最大序列长度
    maxSeqLength: int = Field(default=2048, ge=32, le=32768)
    # 任意扩展超参
    extra: dict[str, Any] = Field(default_factory=dict)


class TrainingJobStatus(BaseModel):
    """训练任务状态快照."""

    status: str = Field(..., description="当前状态")
    progress: float = Field(default=0.0, ge=0.0, le=1.0, description="进度 0.0~1.0")
    currentEpoch: int = Field(default=0, ge=0)
    totalEpochs: int = Field(default=0, ge=0)
    # 失败原因（status=failed 时）
    errorMessage: Optional[str] = Field(default=None)
    # 输出模型 ID（status=succeeded 时）
    outputModelId: Optional[str] = Field(default=None)
    # 输出模型版本号
    outputModelVersion: Optional[int] = Field(default=None)


class TrainingJob(TimestampMixin):
    """训练任务."""

    id: str = Field(..., description="任务 ID（UUID）")
    config: TrainingConfig
    status: TrainingJobStatus = Field(default_factory=lambda: TrainingJobStatus(status="pending", totalEpochs=0))
    # 训练开始/结束时间
    startedAt: Optional[datetime] = Field(default=None)
    finishedAt: Optional[datetime] = Field(default=None)

    @model_validator(mode="after")
    def _sync_total_epochs(self) -> "TrainingJob":
        """同步 totalEpochs 与 config.epochs."""
        if self.status.totalEpochs == 0:
            self.status.totalEpochs = self.config.epochs
        return self


class EvalMetrics(BaseModel):
    """评估指标（对齐 L4.5.5 评估指标）.

    包含大模型特有指标：
        - accuracy:        准确率
        - hallucinationRate: 幻觉率
        - upliftVsBase:    对比基座提升（百分点，如 +11.3 表示 +11.3pt）
    """

    accuracy: float = Field(..., ge=0.0, le=1.0, description="准确率")
    hallucinationRate: float = Field(..., ge=0.0, le=1.0, description="幻觉率")
    upliftVsBase: float = Field(..., description="对比基座提升（百分点）")
    # 评估数据集
    evalDataset: Optional[str] = Field(default=None)
    # 评估样本数
    evalSamples: Optional[int] = Field(default=None, ge=0)
    # 任意扩展指标
    extra: dict[str, float] = Field(default_factory=dict)
    evaluatedAt: datetime = Field(default_factory=utc_now)
