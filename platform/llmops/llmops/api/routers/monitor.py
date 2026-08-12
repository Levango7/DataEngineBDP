"""监控路由."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from llmops.api.routers.deps import get_registry, status_for_error
from llmops.models.monitor import (
    ErrorStats,
    LatencyStats,
    ModelMetrics,
    ThroughputStats,
)
from llmops.repositories import LlmopsError
from llmops.services.registry import ServiceRegistry

router = APIRouter(prefix="/deployments", tags=["monitor"])


@router.get(
    "/{deployment_id}/metrics",
    response_model=ModelMetrics,
    summary="模型综合指标",
)
async def get_metrics(
    deployment_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> ModelMetrics:
    """获取部署的综合指标（准确率/幻觉率/提升/QPS/错误率）."""
    try:
        return await registry.monitorService.get_metrics(deployment_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{deployment_id}/latency",
    response_model=LatencyStats,
    summary="延迟统计",
)
async def get_latency(
    deployment_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> LatencyStats:
    """获取延迟统计（avg/P50/P95/P99/min/max）."""
    try:
        return await registry.monitorService.get_latency(deployment_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{deployment_id}/throughput",
    response_model=ThroughputStats,
    summary="吞吐量统计",
)
async def get_throughput(
    deployment_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> ThroughputStats:
    """获取吞吐量统计（rps/tps/totalRequests/totalTokens）."""
    try:
        return await registry.monitorService.get_throughput(deployment_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{deployment_id}/error-rate",
    response_model=ErrorStats,
    summary="错误率统计",
)
async def get_error_rate(
    deployment_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> ErrorStats:
    """获取错误率统计."""
    try:
        return await registry.monitorService.get_error_rate(deployment_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
