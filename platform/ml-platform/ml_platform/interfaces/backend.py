"""机器学习后端抽象接口（MLBackend）.

定义训练 / 预测 / 评估 / 模型管理的统一契约。
实现：
    - MockMLBackend:    内存模拟，固定结果，用于测试
    - SklearnMLBackend: 调用 scikit-learn 进行真实训练/预测
    - SparkMLBackend:   （可选）调用 Spark MLlib 分布式训练
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Optional

from ml_platform.models import (
    EvalConfig,
    EvalResult,
    ModelInfo,
    PredictionResult,
    TrainingConfig,
    TrainingResult,
)


class MLBackend(ABC):
    """机器学习后端抽象接口.

    职责：训练、预测、评估、模型管理。
    所有方法均为 async，便于底层对接 Spark MLlib / sklearn 异步客户端。
    """

    # ---------- 训练 ----------

    @abstractmethod
    async def train(self, config: TrainingConfig) -> TrainingResult:
        """执行训练，返回训练结果.

        Args:
            config: 训练配置（算法、数据集、超参等）。

        Returns:
            训练结果（含模型 ID、指标、产物 URI）。
        """
        ...

    # ---------- 预测 ----------

    @abstractmethod
    async def predict(
        self, modelId: str, data: dict
    ) -> PredictionResult:
        """使用指定模型进行预测.

        Args:
            modelId: 模型 ID。
            data:    输入数据。

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    # ---------- 评估 ----------

    @abstractmethod
    async def evaluate(
        self, modelId: str, evalConfig: EvalConfig
    ) -> EvalResult:
        """评估模型.

        Args:
            modelId:    模型 ID。
            evalConfig: 评估配置。

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    # ---------- 模型管理 ----------

    @abstractmethod
    async def get_model(self, modelId: str) -> ModelInfo:
        """获取模型信息.

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    @abstractmethod
    async def list_models(self) -> list[ModelInfo]:
        """列出所有模型."""
        ...

    @abstractmethod
    async def delete_model(self, modelId: str) -> None:
        """删除模型.

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    async def find_model_by_name(self, name: str) -> Optional[ModelInfo]:
        """按名称查找模型（可选实现，默认基于 list_models）.

        Returns:
            模型信息或 None。
        """
        models = await self.list_models()
        for m in models:
            if m.name == name:
                return m
        return None