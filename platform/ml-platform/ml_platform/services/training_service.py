"""训练管理业务逻辑.

编排 MLBackend + ExperimentStore：训练完成后自动记录参数与指标到实验。
"""

from __future__ import annotations

from typing import Optional
import uuid

from ml_platform.interfaces.backend import MLBackend
from ml_platform.interfaces.experiment_store import ExperimentStore
from ml_platform.models import (
    TrainingConfig,
    TrainingJob,
    TrainingResult,
    TrainingStatus,
)
from ml_platform.repositories import (
    ExperimentNotFoundError,
    TrainingJobNotFoundError,
)


class TrainingService:
    """训练管理服务（编排 MLBackend + ExperimentStore）."""

    def __init__(
        self,
        backend: MLBackend,
        experimentStore: Optional[ExperimentStore] = None,
    ) -> None:
        self._backend = backend
        self._experimentStore = experimentStore
        self._jobs: dict[str, TrainingJob] = {}

    async def createTrainingJob(self, config: TrainingConfig) -> TrainingJob:
        """创建训练任务并立即执行（同步等待结果）.

        真实场景应改为异步任务队列；此处骨架为同步执行便于测试。
        """
        # 校验实验存在
        if config.experimentId and self._experimentStore is not None:
            try:
                await self._experimentStore.get_experiment(config.experimentId)
            except ExperimentNotFoundError:
                raise ValueError(f"实验不存在: {config.experimentId}")

        jobId = str(uuid.uuid4())
        job = TrainingJob(
            id=jobId,
            config=config,
            status=TrainingStatus.PENDING,
        )
        self._jobs[jobId] = job

        # 同步执行训练
        job.status = TrainingStatus.RUNNING
        try:
            result = await self._backend.train(config)
            job.status = TrainingStatus.SUCCEEDED
            job.result = result
            # 自动记录到实验
            if config.experimentId and self._experimentStore is not None:
                await self._experimentStore.log_params(
                    config.experimentId,
                    {
                        "algorithm": config.algorithm.value,
                        **config.params,
                    },
                )
                await self._experimentStore.log_metrics(config.experimentId, result.metrics)
        except Exception as e:
            job.status = TrainingStatus.FAILED
            job.result = TrainingResult(
                modelId="",
                modelName=config.outputModelName,
                status=TrainingStatus.FAILED,
                errorMessage=str(e),
            )
        return job

    async def getTrainingStatus(self, jobId: str) -> TrainingJob:
        if jobId not in self._jobs:
            raise TrainingJobNotFoundError(jobId)
        return self._jobs[jobId]

    async def listTrainingJobs(self) -> list[TrainingJob]:
        return sorted(
            self._jobs.values(),
            key=lambda j: j.createdAt,
            reverse=True,
        )

    async def cancelTraining(self, jobId: str) -> None:
        job = await self.getTrainingStatus(jobId)
        if job.status in (
            TrainingStatus.SUCCEEDED,
            TrainingStatus.FAILED,
            TrainingStatus.CANCELLED,
        ):
            raise ValueError(f"训练任务 {jobId} 已结束（状态 {job.status.value}），不可取消")
        job.status = TrainingStatus.CANCELLED
