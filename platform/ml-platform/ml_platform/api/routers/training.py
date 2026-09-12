"""训练任务路由."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from ml_platform.api.jwt_auth import AuthContext, getAuthContext
from ml_platform.api.routers.deps import getRegistry, statusForError
from ml_platform.models import AlgorithmType, TrainingConfig, TrainingJob
from ml_platform.repositories import MlPlatformError
from ml_platform.services.registry import ServiceRegistry

router = APIRouter(prefix="/training/jobs", tags=["training"])


class CreateTrainingJobRequest(BaseModel):
    """创建训练任务请求."""

    algorithm: AlgorithmType = Field(..., description="算法类型")
    experimentId: str | None = Field(default=None, description="所属实验 ID")
    dataset: str = Field(..., description="训练数据集标识")
    features: list[str] = Field(default_factory=list, description="特征列名列表")
    target: str | None = Field(default=None, description="目标列名")
    params: dict[str, Any] = Field(default_factory=dict, description="算法超参")
    validationSplit: float = Field(default=0.2, ge=0.0, le=1.0, description="验证集比例")
    randomState: int = Field(default=42, description="随机种子")
    outputModelName: str = Field(..., description="产出模型名")
    description: str | None = Field(default=None, description="描述")


def _require_job_owner(job: TrainingJob, ctx: AuthContext) -> None:
    """对象级授权：校验 ctx 对 job 有操作权限.

    admin 或任务所属租户可操作；其他返回 404（避免泄露存在性）。
    """
    if ctx.role == "admin":
        return
    if not ctx.tenantId:
        raise HTTPException(status_code=403, detail="缺少租户上下文")
    if getattr(job, "tenantId", None) != ctx.tenantId:
        raise HTTPException(status_code=404, detail="训练任务不存在")


@router.post(
    "",
    response_model=TrainingJob,
    status_code=status.HTTP_201_CREATED,
    summary="创建训练任务",
)
async def createTrainingJob(
    body: CreateTrainingJobRequest,
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """创建训练任务.

    租户隔离：任务归属当前请求租户 ctx.tenantId。
    """
    try:
        config = TrainingConfig(
            algorithm=body.algorithm,
            experimentId=body.experimentId,
            dataset=body.dataset,
            features=body.features,
            target=body.target,
            params=body.params,
            validationSplit=body.validationSplit,
            randomState=body.randomState,
            outputModelName=body.outputModelName,
            description=body.description,
            tenantId=ctx.tenantId,
        )
        return await registry.trainingService.createTrainingJob(config)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=str(e),
        )


@router.get(
    "",
    response_model=list[TrainingJob],
    summary="列出训练任务",
)
async def listTrainingJobs(
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """列出训练任务（按租户隔离：普通用户仅见本租户任务，admin 可见全部）."""
    jobs = await registry.trainingService.listTrainingJobs()
    if ctx.role != "admin":
        # 非 admin 用户必须有 tenantId，否则拒绝（防止空 tenantId 绕过过滤返回全部任务）
        if not ctx.tenantId:
            raise HTTPException(status_code=403, detail="缺少租户上下文")
        jobs = [j for j in jobs if getattr(j, "tenantId", None) == ctx.tenantId]
    return jobs


@router.get(
    "/{jobId}",
    response_model=TrainingJob,
    summary="训练状态",
)
async def getTrainingStatus(
    jobId: str,
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """获取训练任务详情（含状态与进度）.

    租户隔离：非 admin 仅可查看本租户任务。
    """
    try:
        job = await registry.trainingService.getTrainingStatus(jobId)
        _require_job_owner(job, ctx)
        return job
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.delete(
    "/{jobId}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="取消训练任务",
)
async def cancelTraining(
    jobId: str,
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """取消训练任务.

    租户隔离：非 admin 仅可取消本租户任务。
    """
    try:
        job = await registry.trainingService.getTrainingStatus(jobId)
        _require_job_owner(job, ctx)
        await registry.trainingService.cancelTraining(jobId)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=str(e),
        )
