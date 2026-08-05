"""Mock 模型训练 - 状态机模拟训练流程.

状态转换：
    PENDING -> RUNNING -> (SUCCEEDED | FAILED)
    PENDING/RUNNING -> CANCELLED

为便于测试，提供 advance() 方法手动推进状态；生产环境真实训练由
MLflowModelTrainer 调用 MLflow Projects 提交作业。
"""
from __future__ import annotations

import uuid
from typing import Optional

from llmops.interfaces.trainer import ModelTrainer
from llmops.models.base import utc_now
from llmops.models.training import (
    EvalMetrics,
    TrainingConfig,
    TrainingJob,
    TrainingJobStatus,
)
from llmops.models.base import TrainingStatus
from llmops.repositories import (
    TrainingJobNotFoundError,
    TrainingJobNotCancellableError,
    TrainingJobNotFinishedError,
)


class MockModelTrainer(ModelTrainer):
    """内存态训练任务管理，状态机模拟."""

    def __init__(self) -> None:
        self._jobs: dict[str, TrainingJob] = {}

    # ---------- ModelTrainer ----------

    async def create_training_job(self, config: TrainingConfig) -> str:
        job_id = str(uuid.uuid4())
        job = TrainingJob(
            id=job_id,
            config=config,
            status=TrainingJobStatus(
                status=TrainingStatus.PENDING,
                progress=0.0,
                currentEpoch=0,
                totalEpochs=config.epochs,
            ),
        )
        self._jobs[job_id] = job
        return job_id

    async def get_training_status(self, job_id: str) -> TrainingJob:
        if job_id not in self._jobs:
            raise TrainingJobNotFoundError(job_id)
        return self._jobs[job_id]

    async def cancel_training(self, job_id: str) -> None:
        job = await self.get_training_status(job_id)
        if job.status.status in {
            TrainingStatus.SUCCEEDED,
            TrainingStatus.FAILED,
            TrainingStatus.CANCELLED,
        }:
            raise TrainingJobNotCancellableError(job_id, job.status.status)
        job.status.status = TrainingStatus.CANCELLED
        job.finishedAt = utc_now()
        job.updatedAt = utc_now()

    async def list_training_jobs(self) -> list[TrainingJob]:
        return sorted(self._jobs.values(), key=lambda j: j.createdAt, reverse=True)

    async def evaluate_model(
        self, job_id: str, eval_dataset: str | None = None
    ) -> EvalMetrics:
        job = await self.get_training_status(job_id)
        if job.status.status != TrainingStatus.SUCCEEDED:
            raise TrainingJobNotFinishedError(job_id, job.status.status)
        # Mock 评估指标：基于训练配置生成确定性"合理"指标
        # 准确率随 epochs 增加而提升，幻觉率随 epochs 增加而下降
        epochs = job.config.epochs
        accuracy = min(0.95, 0.70 + 0.05 * epochs)
        hallucination_rate = max(0.01, 0.10 - 0.01 * epochs)
        uplift_vs_base = round((accuracy - 0.75) * 100, 1)
        return EvalMetrics(
            accuracy=round(accuracy, 4),
            hallucinationRate=round(hallucination_rate, 4),
            upliftVsBase=uplift_vs_base,
            evalDataset=eval_dataset or job.config.dataset,
            evalSamples=1000,
            extra={"loss": round(max(0.1, 1.0 - 0.1 * epochs), 4)},
        )

    # ---------- 状态机推进（测试与 Mock 模式专用） ----------

    async def advance(self, job_id: str) -> TrainingJob:
        """推进训练任务到下一状态（Mock 专用）.

        转换规则：
            PENDING -> RUNNING（开始训练，startedAt 置位）
            RUNNING -> 推进一个 epoch；epoch 达到 totalEpochs 后 -> SUCCEEDED
        """
        job = await self.get_training_status(job_id)
        st = job.status
        if st.status == TrainingStatus.PENDING:
            st.status = TrainingStatus.RUNNING
            job.startedAt = utc_now()
        elif st.status == TrainingStatus.RUNNING:
            st.currentEpoch += 1
            st.progress = st.currentEpoch / st.totalEpochs
            if st.currentEpoch >= st.totalEpochs:
                st.status = TrainingStatus.SUCCEEDED
                st.progress = 1.0
                job.finishedAt = utc_now()
                # 生成输出模型 ID（Mock）
                st.outputModelId = str(uuid.uuid4())
                st.outputModelVersion = 1
        job.updatedAt = utc_now()
        return job

    async def mark_failed(
        self, job_id: str, error_message: str
    ) -> TrainingJob:
        """标记任务失败（Mock 专用）."""
        job = await self.get_training_status(job_id)
        job.status.status = TrainingStatus.FAILED
        job.status.errorMessage = error_message
        job.finishedAt = utc_now()
        job.updatedAt = utc_now()
        return job

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        self._jobs.clear()

    def __len__(self) -> int:
        return len(self._jobs)