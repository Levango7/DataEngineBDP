"""训练相关数据模型."""

from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field

from ml_platform.models.base import (
    AlgorithmType,
    TimestampMixin,
    TrainingStatus,
)


class TrainingConfig(BaseModel):
    """训练配置.

    Attributes:
        algorithm:        算法类型
        experimentId:     所属实验 ID
        dataset:          训练数据集标识
        features:         特征列名列表
        target:           目标列名（监督学习）
        params:           算法超参（如 numTrees、maxDepth）
        validationSplit:  验证集比例（0~1）
        randomState:      随机种子
        outputModelName:  产出模型名
        description:      描述
    """

    algorithm: AlgorithmType = Field(..., description="算法类型")
    experimentId: Optional[str] = Field(default=None, description="所属实验 ID")
    dataset: str = Field(..., description="训练数据集标识")
    features: list[str] = Field(default_factory=list, description="特征列名列表")
    target: Optional[str] = Field(default=None, description="目标列名")
    params: dict[str, Any] = Field(default_factory=dict, description="算法超参")
    validationSplit: float = Field(default=0.2, ge=0.0, le=1.0, description="验证集比例")
    randomState: int = Field(default=42, description="随机种子")
    outputModelName: str = Field(..., description="产出模型名")
    description: Optional[str] = Field(default=None, description="描述")


class TrainingResult(TimestampMixin):
    """训练结果.

    Attributes:
        modelId:       产出模型 ID
        modelName:     产出模型名
        status:        训练状态
        metrics:       训练过程指标（如 train_loss、val_auc）
        artifactUri:   模型产物 URI
        durationMs:    训练耗时（毫秒）
        errorMessage:  失败原因（status=failed 时填）
    """

    modelId: str = Field(..., description="产出模型 ID")
    modelName: str = Field(..., description="产出模型名")
    status: TrainingStatus = Field(default=TrainingStatus.SUCCEEDED, description="训练状态")
    metrics: dict[str, float] = Field(default_factory=dict, description="训练指标")
    artifactUri: Optional[str] = Field(default=None, description="模型产物 URI")
    durationMs: Optional[int] = Field(default=None, description="训练耗时(ms)")
    errorMessage: Optional[str] = Field(default=None, description="失败原因")


class TrainingJob(TimestampMixin):
    """训练任务.

    Attributes:
        id:      任务 ID
        config:  训练配置
        status:  任务状态
        result:  训练结果（完成后填充）
    """

    id: str = Field(..., description="任务 ID")
    config: TrainingConfig = Field(..., description="训练配置")
    status: TrainingStatus = Field(default=TrainingStatus.PENDING, description="任务状态")
    result: Optional[TrainingResult] = Field(default=None, description="训练结果")
