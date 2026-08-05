"""训练任务路由."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from ml_platform.api.routers.deps import getRegistry, statusForError
from ml_platform.models import AlgorithmType, TrainingConfig, TrainingJob
from ml_platform.repositories import MlPlatformError
from ml_platform.services.registry import ServiceRegistry

router = APIRouter(prefix="/training/jobs", tags=["training"])


class CreateTrainingJobRequest(BaseModel):
    """创建训练任务请求."""

    algorithm: AlgorithmType = Field(..., description="算法类型")
    experimentId: str | None = Field(
        default=None, description="所属实验 ID"
    )
    dataset: str = Field(..., description="训练数据集标识")
    features: list[str] = Field(
        default_factory=list, description="特征列名列表"
    )
    target: str | None = Field(default=None, description="目标列名")
    params: dict[str, Any] = Field(
        default_factory=dict, description="算法超参"
    )
    validationSplit: float = Field(
        default=0.2, ge=0.0, le=1.0, description="验证集比例"
    )
    randomState: int = Field(default=42, description="随机种子")
    outputModelName: str = Field(..., description="产出模型名")
    description: str | None = Field(default=None, description="描述")


@router.post(
    "",
    response_model=TrainingJob,
    status_code=status.HTTP_201_CREATED,
    summary="创建训练任务",
)
async def createTrainingJob(
    body: CreateTrainingJobRequest,
    registry: ServiceRegistry = Depends(getRegistry),
):
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
        )
        return await registry.trainingService.createTrainingJob(config)
    except MlPlatformError as e:
        raise HTTPException(
            status_code=statusForError(e), detail=str(e)
        )
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
):
    return await registry.trainingService.listTrainingJobs()


@router.get(
    "/{jobId}",
    response_model=TrainingJob,
    summary="训练状态",
)
async def getTrainingStatus(
    jobId: str,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        return await registry.trainingService.getTrainingStatus(jobId)
    except MlPlatformError as e:
        raise HTTPException(
            status_code=statusForError(e), detail=str(e)
        )


@router.delete(
    "/{jobId}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="取消训练任务",
)
async def cancelTraining(
    jobId: str,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        await registry.trainingService.cancelTraining(jobId)
    except MlPlatformError as e:
        raise HTTPException(
            status_code=statusForError(e), detail=str(e)
        )
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=str(e),
        )