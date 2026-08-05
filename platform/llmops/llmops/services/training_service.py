"""训练管理业务逻辑."""
from __future__ import annotations

from typing import Optional

from llmops.interfaces.store import ModelStore
from llmops.interfaces.trainer import ModelTrainer
from llmops.models.base import ModelStatus, ModelType, utc_now
from llmops.models.model import ModelInfo, ModelParams, ModelVersion
from llmops.models.training import (
    EvalMetrics,
    TrainingConfig,
    TrainingJob,
)
from llmops.repositories import ModelNotFoundError


class TrainingService:
    """训练管理服务（编排 Trainer + Store）."""

    def __init__(self, trainer: ModelTrainer, store: ModelStore) -> None:
        self._trainer = trainer
        self._store = store

    async def create_training_job(self, config: TrainingConfig) -> TrainingJob:
        # 业务校验：基座模型必须存在
        try:
            base = await self._store.get_model(config.baseModelId)
        except ModelNotFoundError:
            raise ValueError(f"基座模型不存在: {config.baseModelId}")
        if base.type != ModelType.BASE:
            raise ValueError(f"基座模型 {config.baseModelId} 不是 base 类型")
        job_id = await self._trainer.create_training_job(config)
        return await self._trainer.get_training_status(job_id)

    async def get_training_status(self, job_id: str) -> TrainingJob:
        return await self._trainer.get_training_status(job_id)

    async def cancel_training(self, job_id: str) -> None:
        await self._trainer.cancel_training(job_id)

    async def list_training_jobs(self) -> list[TrainingJob]:
        return await self._trainer.list_training_jobs()

    async def evaluate_model(
        self, job_id: str, eval_dataset: Optional[str] = None
    ) -> EvalMetrics:
        return await self._trainer.evaluate_model(job_id, eval_dataset)

    async def complete_and_register(
        self, job_id: str
    ) -> tuple[ModelInfo, ModelVersion]:
        """训练完成后将产出模型注册到 Store（编排逻辑）.

        Returns:
            (新注册的微调模型, 模型版本)
        """
        job = await self._trainer.get_training_status(job_id)
        if job.status.outputModelId is None:
            raise ValueError(f"训练任务 {job_id} 未产出模型")
        # 注册微调模型
        ft_model = ModelInfo(
            id=job.status.outputModelId,
            name=job.config.outputModelName,
            type=ModelType.FT,
            baseModelId=job.config.baseModelId,
            params=ModelParams(
                finetuneMethod=job.config.finetuneMethod,
                extra={
                    "epochs": job.config.epochs,
                    "learningRate": job.config.learningRate,
                    "dataset": job.config.dataset,
                },
            ),
            status=ModelStatus.READY,
            tags={"sourceJob": job_id},
        )
        model_id = await self._store.register_model(ft_model)
        # 新增版本
        version = ModelVersion(
            version=1,
            modelId=model_id,
            sourceRunId=job_id,
            artifactUri=f"mlflow-artifact:///{job_id}/model",
        )
        await self._store.add_model_version(model_id, version)
        registered = await self._store.get_model(model_id)
        return registered, version