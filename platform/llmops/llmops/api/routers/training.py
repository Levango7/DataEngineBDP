"""训练任务路由."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status

from llmops.api.routers.deps import get_registry, status_for_error
from llmops.models.training import EvalMetrics, TrainingConfig, TrainingJob
from llmops.repositories import LlmopsError
from llmops.services.registry import ServiceRegistry

router = APIRouter(prefix="/training", tags=["training"])


@router.post(
    "/jobs",
    response_model=TrainingJob,
    status_code=status.HTTP_201_CREATED,
    summary="创建训练任务",
)
async def create_training_job(
    config: TrainingConfig,
    registry: ServiceRegistry = Depends(get_registry),
) -> TrainingJob:
    """创建训练/微调任务.

    训练数据预处理由 LLMOps 内置轻量流水线完成
    （tokenization / packing / chat template 渲染）。
    """
    try:
        return await registry.trainingService.create_training_job(config)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))


@router.get(
    "/jobs",
    response_model=list[TrainingJob],
    summary="列出训练任务",
)
async def list_training_jobs(
    registry: ServiceRegistry = Depends(get_registry),
) -> list[TrainingJob]:
    """列出所有训练任务."""
    return await registry.trainingService.list_training_jobs()


@router.get(
    "/jobs/{job_id}",
    response_model=TrainingJob,
    summary="训练状态",
)
async def get_training_status(
    job_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> TrainingJob:
    """获取训练任务详情（含状态与进度）."""
    try:
        return await registry.trainingService.get_training_status(job_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/jobs/{job_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="取消训练",
)
async def cancel_training(
    job_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    """取消训练任务（已结束的任务不可取消）."""
    try:
        await registry.trainingService.cancel_training(job_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/jobs/{job_id}/eval",
    response_model=EvalMetrics,
    summary="评估训练产出模型",
)
async def evaluate_model(
    job_id: str,
    eval_dataset: str | None = None,
    registry: ServiceRegistry = Depends(get_registry),
) -> EvalMetrics:
    """对训练产出模型进行评估.

    返回大模型特有指标：accuracy / hallucinationRate / upliftVsBase。
    """
    try:
        return await registry.trainingService.evaluate_model(job_id, eval_dataset)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))