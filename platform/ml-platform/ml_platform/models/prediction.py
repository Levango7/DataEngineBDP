"""预测与评估数据模型."""
from __future__ import annotations

from typing import Any, Optional, Union

from pydantic import BaseModel, Field

from ml_platform.models.base import TimestampMixin


class PredictionResult(BaseModel):
    """预测结果.

    Attributes:
        modelId:   模型 ID
        predictions: 预测值列表（与输入样本一一对应）
        probabilities: 概率列表（分类任务可选）
        metadata: 额外信息（如预测耗时、模型版本）
    """

    modelId: str = Field(..., description="模型 ID")
    predictions: list[Union[float, int, str]] = Field(
        ..., description="预测值列表"
    )
    probabilities: Optional[list[list[float]]] = Field(
        default=None, description="分类概率（每样本一行）"
    )
    metadata: dict[str, Any] = Field(
        default_factory=dict, description="额外信息"
    )


class PredictionRequest(BaseModel):
    """预测请求.

    Attributes:
        data: 输入数据，键为特征名，值为样本数组（列优先）或单样本 dict 列表
    """

    data: Union[list[dict[str, Any]], dict[str, list[Any]]] = Field(
        ..., description="输入数据"
    )


class EvalConfig(BaseModel):
    """评估配置.

    Attributes:
        dataset:       评估数据集标识
        metrics:       评估指标列表（如 accuracy、auc、rmse）
        batchSize:     批大小
        threshold:     二分类阈值（可选）
    """

    dataset: str = Field(..., description="评估数据集标识")
    metrics: list[str] = Field(
        default_factory=lambda: ["accuracy"],
        description="评估指标列表",
    )
    batchSize: int = Field(default=32, ge=1, description="批大小")
    threshold: Optional[float] = Field(
        default=None, ge=0.0, le=1.0, description="二分类阈值"
    )


class EvalResult(TimestampMixin):
    """评估结果.

    Attributes:
        modelId:  模型 ID
        dataset:  评估数据集
        metrics:  指标名 -> 值
        sampleSize: 评估样本数
    """

    modelId: str = Field(..., description="模型 ID")
    dataset: str = Field(..., description="评估数据集")
    metrics: dict[str, float] = Field(
        default_factory=dict, description="指标值"
    )
    sampleSize: Optional[int] = Field(
        default=None, description="评估样本数"
    )