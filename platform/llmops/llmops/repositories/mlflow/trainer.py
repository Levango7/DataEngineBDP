"""MLflow 训练实现 - 对接 MLflow Projects / Tracking.

骨架实现：通过 MLflow Projects 提交训练作业，Tracking 记录 metrics/params/artifacts。
大模型特有的训练数据预处理（tokenization / packing / chat template）由
LLMOps 内置轻量流水线完成（设计文档明确不复用 Spark ETL）。
"""
from __future__ import annotations

import uuid
from typing import Optional

from llmops.interfaces.trainer import ModelTrainer
from llmops.models.base import TrainingStatus, utc_now
from llmops.models.training import (
    EvalMetrics,
    TrainingConfig,
    TrainingJob,
    TrainingJobStatus,
)
from llmops.repositories import (
    TrainingJobNotFoundError,
    TrainingJobNotCancellableError,
    TrainingJobNotFinishedError,
)
from llmops.repositories.mlflow.client import MLflowClient


class MLflowModelTrainer(ModelTrainer):
    """基于 MLflow Projects 的训练管理（骨架）."""

    def __init__(self, client: MLflowClient) -> None:
        self._client = client
        # MLflow 提交的作业在远端异步执行，本地维护 job_id -> run_id 映射
        self._jobs: dict[str, TrainingJob] = {}
        self._run_index: dict[str, str] = {}

    async def create_training_job(self, config: TrainingConfig) -> str:
        job_id = str(uuid.uuid4())
        job = TrainingJob(
            id=job_id,
            config=config,
            status=TrainingJobStatus(
                status=TrainingStatus.PENDING,
                progress=0.0,
                totalEpochs=config.epochs,
            ),
        )
        # 骨架：实际应通过 mlflow.projects.run() 提交训练入口
        # 此处仅记录 job，run_id 在实际提交后填充
        self._jobs[job_id] = job
        return job_id

    async def get_training_status(self, job_id: str) -> TrainingJob:
        if job_id not in self._jobs:
            raise TrainingJobNotFoundError(job_id)
        job = self._jobs[job_id]
        # 骨架：可在此通过 mlflow client.get_run(run_id) 拉取最新状态
        return job

    async def cancel_training(self, job_id: str) -> None:
        job = await self.get_training_status(job_id)
        if job.status.status in {
            TrainingStatus.SUCCEEDED,
            TrainingStatus.FAILED,
            TrainingStatus.CANCELLED,
        }:
            raise TrainingJobNotCancellableError(job_id, job.status.status)
        # 骨架：实际应通过 K8s API 终止训练 Pod
        job.status.status = TrainingStatus.CANCELLED
        job.finishedAt = utc_now()

    async def list_training_jobs(self) -> list[TrainingJob]:
        return sorted(self._jobs.values(), key=lambda j: j.createdAt, reverse=True)

    async def evaluate_model(
        self, job_id: str, eval_dataset: str | None = None
    ) -> EvalMetrics:
        job = await self.get_training_status(job_id)
        if job.status.status != TrainingStatus.SUCCEEDED:
            raise TrainingJobNotFinishedError(job_id, job.status.status)
        # 骨架：实际应通过 mlflow client.get_run(run_id).data.metrics 取评估指标
        # 大模型特有指标（幻觉率、对比基座提升）由 LLMOps 评估脚本写入 metrics
        return EvalMetrics(
            accuracy=0.0,
            hallucinationRate=0.0,
            upliftVsBase=0.0,
            evalDataset=eval_dataset or job.config.dataset,
        )