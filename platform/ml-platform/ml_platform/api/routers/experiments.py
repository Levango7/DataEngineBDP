"""实验管理路由."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

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


@router.post(
    "",
    response_model=ExperimentInfo,
    status_code=status.HTTP_201_CREATED,
    summary="创建实验",
)
async def createExperiment(
    body: CreateExperimentRequest,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        config = ExperimentConfig(
            name=body.name,
            workspaceId=body.workspaceId,
            projectId=body.projectId,
            description=body.description,
            tags=body.tags,
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
):
    return await registry.experimentService.listExperiments()


@router.get(
    "/{experimentId}",
    response_model=ExperimentInfo,
    summary="实验详情",
)
async def getExperiment(
    experimentId: str,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        return await registry.experimentService.getExperiment(experimentId)
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
):
    try:
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
):
    try:
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
):
    try:
        return await registry.experimentService.logParams(experimentId, body.params)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))
