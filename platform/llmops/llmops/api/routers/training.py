"""训练任务路由."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status

from llmops.api.jwt_auth import AuthContext, getAuthContext
from llmops.api.routers.deps import get_registry, status_for_error
from llmops.models.training import EvalMetrics, TrainingConfig, TrainingJob
from llmops.repositories import LlmopsError
from llmops.services.registry import ServiceRegistry

router = APIRouter(prefix="/training", tags=["training"])


def _require_job_owner(job: TrainingJob, ctx: AuthContext) -> None:
    """对象级授权：校验 ctx 对 job 有操作权限.

    admin 或任务所属租户可操作；其他返回 404（避免泄露存在性）。
    """
    if ctx.role == "admin":
        return
    if ctx.tenantId and getattr(job, "tenantId", None) != ctx.tenantId:
        raise HTTPException(status_code=404, detail="训练任务不存在")


@router.post(
    "/jobs",
    response_model=TrainingJob,
    status_code=status.HTTP_201_CREATED,
    summary="创建训练任务",
)
async def create_training_job(
    config: TrainingConfig,
    registry: ServiceRegistry = Depends(get_registry),
    ctx: AuthContext = Depends(getAuthContext),
) -> TrainingJob:
    """创建训练/微调任务.

    训练数据预处理由 LLMOps 内置轻量流水线完成
    （tokenization / packing / chat template 渲染）。

    租户隔离：任务归属当前请求租户 ctx.tenantId。
    """
    # 注入租户 ID 到 config，由 trainer 写入 TrainingJob 实体
    config.tenantId = ctx.tenantId
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
    ctx: AuthContext = Depends(getAuthContext),
) -> list[TrainingJob]:
    """列出训练任务（按租户隔离：普通用户仅见本租户任务，admin 可见全部）."""
    jobs = await registry.trainingService.list_training_jobs()
    if ctx.role != "admin" and ctx.tenantId:
        jobs = [j for j in jobs if getattr(j, "tenantId", None) == ctx.tenantId]
    return jobs


@router.get(
    "/jobs/{job_id}",
    response_model=TrainingJob,
    summary="训练状态",
)
async def get_training_status(
    job_id: str,
    registry: ServiceRegistry = Depends(get_registry),
    ctx: AuthContext = Depends(getAuthContext),
) -> TrainingJob:
    """获取训练任务详情（含状态与进度）.

    租户隔离：非 admin 仅可查看本租户任务。
    """
    try:
        job = await registry.trainingService.get_training_status(job_id)
        _require_job_owner(job, ctx)
        return job
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
    ctx: AuthContext = Depends(getAuthContext),
) -> None:
    """取消训练任务（已结束的任务不可取消）.

    租户隔离：非 admin 仅可取消本租户任务。
    """
    try:
        # 先校验归属
        job = await registry.trainingService.get_training_status(job_id)
        _require_job_owner(job, ctx)
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
    ctx: AuthContext = Depends(getAuthContext),
) -> EvalMetrics:
    """对训练产出模型进行评估.

    返回大模型特有指标：accuracy / hallucinationRate / upliftVsBase。

    租户隔离：非 admin 仅可评估本租户任务。
    """
    try:
        # 先校验归属
        job = await registry.trainingService.get_training_status(job_id)
        _require_job_owner(job, ctx)
        return await registry.trainingService.evaluate_model(job_id, eval_dataset)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
