"""模型训练抽象接口（Training / Fine-tune Pipeline）.

对齐设计：{ baseModel, dataset, epochs, gpu, lr } → 训练任务
训练数据预处理由 LLMOps 内置轻量流水线完成（tokenization / packing / chat template）。
"""
from __future__ import annotations

from abc import ABC, abstractmethod

from llmops.models.training import EvalMetrics, TrainingConfig, TrainingJob


class ModelTrainer(ABC):
    """模型训练抽象接口.

    职责：创建训练任务、查询状态、取消、列表、评估。
    实现：MockModelTrainer（状态机模拟）/ MLflowModelTrainer（MLflow Projects）。
    """

    @abstractmethod
    async def create_training_job(self, config: TrainingConfig) -> str:
        """创建训练任务，返回 job_id.

        Args:
            config: 训练配置。

        Returns:
            训练任务 ID。
        """
        ...

    @abstractmethod
    async def get_training_status(self, job_id: str) -> TrainingJob:
        """获取训练任务详情（含状态）.

        Raises:
            TrainingJobNotFoundError: 任务不存在。
        """
        ...

    @abstractmethod
    async def cancel_training(self, job_id: str) -> None:
        """取消训练任务.

        Raises:
            TrainingJobNotFoundError: 任务不存在。
            TrainingJobNotCancellableError: 任务已结束不可取消。
        """
        ...

    @abstractmethod
    async def list_training_jobs(self) -> list[TrainingJob]:
        """列出所有训练任务."""
        ...

    @abstractmethod
    async def evaluate_model(
        self, job_id: str, eval_dataset: str | None = None
    ) -> EvalMetrics:
        """对训练产出模型进行评估，返回大模型特有指标.

        指标包含：accuracy / hallucinationRate / upliftVsBase。

        Raises:
            TrainingJobNotFoundError: 任务不存在。
            TrainingJobNotFinishedError: 任务尚未完成，无法评估。
        """
        ...