"""实验管理路由."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from ml_platform.api.jwt_auth import AuthContext, getAuthContext
from ml_platform.api.routers.deps import getRegistry, statusForError
from ml_platform.models import ExperimentConfig, ExperimentInfo
from ml_platform.repositories import MlPlatformError
from ml_platform.services.registry import ServiceRegistry

router = APIRouter(prefix="/experiments", tags=["experiments"])


class CreateExperimentRequest(BaseModel):
    """创建实验请求."""

    name: str = Field(..., description="实验名")
    workspaceId: str | None = Field(default=None, description="工作空间 ID")
    projectId: str | None = Field(default=None, description="项目 ID")
    description: str | None = Field(default=None, description="描述")
    tags: dict[str, str] = Field(default_factory=dict, description="标签")


class LogMetricsRequest(BaseModel):
    """记录指标请求."""

    metrics: dict[str, float] = Field(..., description="指标")


class LogParamsRequest(BaseModel):
    """记录参数请求."""

    params: dict = Field(..., description="参数")


def _require_experiment_owner(experiment: ExperimentInfo, ctx: AuthContext) -> None:
    """对象级授权：校验 ctx 对 experiment 有操作权限.

    admin 或实验所属租户可操作；其他返回 404（避免泄露存在性）。
    """
    if ctx.role == "admin":
        return
    if not ctx.tenantId:
        raise HTTPException(status_code=403, detail="缺少租户上下文")
    if getattr(experiment, "tenantId", None) != ctx.tenantId:
        raise HTTPException(status_code=404, detail="实验不存在")


@router.post(
    "",
    response_model=ExperimentInfo,
    status_code=status.HTTP_201_CREATED,
    summary="创建实验",
)
async def createExperiment(
    body: CreateExperimentRequest,
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """创建实验.

    租户隔离：实验归属当前请求租户 ctx.tenantId。
    """
    try:
        config = ExperimentConfig(
            name=body.name,
            workspaceId=body.workspaceId,
            projectId=body.projectId,
            description=body.description,
            tags=body.tags,
            tenantId=ctx.tenantId,
        )
        return await registry.experimentService.createExperiment(config)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.get(
    "",
    response_model=list[ExperimentInfo],
    summary="列出实验",
)
async def listExperiments(
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """列出实验（按租户隔离：普通用户仅见本租户实验，admin 可见全部）."""
    experiments = await registry.experimentService.listExperiments()
    if ctx.role != "admin":
        # 非 admin 用户必须有 tenantId，否则拒绝（防止空 tenantId 绕过过滤返回全部实验）
        if not ctx.tenantId:
            raise HTTPException(status_code=403, detail="缺少租户上下文")
        experiments = [e for e in experiments if getattr(e, "tenantId", None) == ctx.tenantId]
    return experiments


@router.get(
    "/{experimentId}",
    response_model=ExperimentInfo,
    summary="实验详情",
)
async def getExperiment(
    experimentId: str,
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """获取实验详情.

    租户隔离：非 admin 仅可查看本租户实验。
    """
    try:
        experiment = await registry.experimentService.getExperiment(experimentId)
        _require_experiment_owner(experiment, ctx)
        return experiment
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.delete(
    "/{experimentId}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="删除实验",
)
async def deleteExperiment(
    experimentId: str,
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """删除实验.

    租户隔离：非 admin 仅可删除本租户实验。
    """
    try:
        experiment = await registry.experimentService.getExperiment(experimentId)
        _require_experiment_owner(experiment, ctx)
        await registry.experimentService.deleteExperiment(experimentId)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.post(
    "/{experimentId}/metrics",
    response_model=ExperimentInfo,
    summary="记录指标",
)
async def logMetrics(
    experimentId: str,
    body: LogMetricsRequest,
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """记录指标.

    租户隔离：非 admin 仅可记录本租户实验指标。
    """
    try:
        experiment = await registry.experimentService.getExperiment(experimentId)
        _require_experiment_owner(experiment, ctx)
        return await registry.experimentService.logMetrics(experimentId, body.metrics)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.post(
    "/{experimentId}/params",
    response_model=ExperimentInfo,
    summary="记录参数",
)
async def logParams(
    experimentId: str,
    body: LogParamsRequest,
    registry: ServiceRegistry = Depends(getRegistry),
    ctx: AuthContext = Depends(getAuthContext),
):
    """记录参数.

    租户隔离：非 admin 仅可记录本租户实验参数。
    """
    try:
        experiment = await registry.experimentService.getExperiment(experimentId)
        _require_experiment_owner(experiment, ctx)
        return await registry.experimentService.logParams(experimentId, body.params)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))
