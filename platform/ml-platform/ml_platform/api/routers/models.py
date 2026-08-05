"""模型管理、预测、评估路由."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from ml_platform.api.routers.deps import getRegistry, statusForError
from ml_platform.models import (
    EvalConfig,
    EvalResult,
    ModelInfo,
    PredictionResult,
)
from ml_platform.repositories import MlPlatformError
from ml_platform.services.registry import ServiceRegistry

router = APIRouter(prefix="/models", tags=["models"])


class PredictRequest(BaseModel):
    """预测请求."""

    data: Any = Field(..., description="输入数据")


class EvaluateRequest(BaseModel):
    """评估请求."""

    dataset: str = Field(..., description="评估数据集标识")
    metrics: list[str] = Field(
        default_factory=lambda: ["accuracy"],
        description="评估指标列表",
    )
    batchSize: int = Field(default=32, ge=1, description="批大小")
    threshold: float | None = Field(
        default=None, ge=0.0, le=1.0, description="二分类阈值"
    )


@router.get(
    "",
    response_model=list[ModelInfo],
    summary="列出模型",
)
async def listModels(
    registry: ServiceRegistry = Depends(getRegistry),
):
    return await registry.backend.list_models()


@router.get(
    "/{modelId}",
    response_model=ModelInfo,
    summary="模型详情",
)
async def getModel(
    modelId: str,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        return await registry.backend.get_model(modelId)
    except MlPlatformError as e:
        raise HTTPException(
            status_code=statusForError(e), detail=str(e)
        )


@router.delete(
    "/{modelId}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="删除模型",
)
async def deleteModel(
    modelId: str,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        await registry.backend.delete_model(modelId)
    except MlPlatformError as e:
        raise HTTPException(
            status_code=statusForError(e), detail=str(e)
        )


@router.post(
    "/{modelId}/predict",
    response_model=PredictionResult,
    summary="模型预测",
)
async def predict(
    modelId: str,
    body: PredictRequest,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        data = body.data if isinstance(body.data, dict) else {"_samples": body.data}
        return await registry.predictionService.predict(modelId, data)
    except MlPlatformError as e:
        raise HTTPException(
            status_code=statusForError(e), detail=str(e)
        )


@router.post(
    "/{modelId}/evaluate",
    response_model=EvalResult,
    summary="模型评估",
)
async def evaluate(
    modelId: str,
    body: EvaluateRequest,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        evalConfig = EvalConfig(
            dataset=body.dataset,
            metrics=body.metrics,
            batchSize=body.batchSize,
            threshold=body.threshold,
        )
        return await registry.evaluationService.evaluate(
            modelId, evalConfig
        )
    except MlPlatformError as e:
        raise HTTPException(
            status_code=statusForError(e), detail=str(e)
        )