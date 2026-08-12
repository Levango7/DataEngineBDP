"""部署管理路由."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status

from llmops.api.routers.deps import get_registry, status_for_error
from llmops.models.deployment import DeployConfig, Deployment
from llmops.repositories import LlmopsError
from llmops.services.registry import ServiceRegistry

router = APIRouter(prefix="/deployments", tags=["deployments"])


@router.post(
    "",
    response_model=Deployment,
    status_code=status.HTTP_201_CREATED,
    summary="部署模型",
)
async def deploy_model(
    config: DeployConfig,
    registry: ServiceRegistry = Depends(get_registry),
) -> Deployment:
    """部署模型到推理端点（注册到 L4.5.6 大模型网关）."""
    try:
        return await registry.deploymentService.deploy_model(config.modelId, config)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))


@router.get(
    "",
    response_model=list[Deployment],
    summary="列出部署",
)
async def list_deployments(
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Deployment]:
    """列出所有部署."""
    return await registry.deploymentService.list_deployments()


@router.get(
    "/{deployment_id}",
    response_model=Deployment,
    summary="部署状态",
)
async def get_deployment_status(
    deployment_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> Deployment:
    """获取部署详情（含状态与端点 URL）."""
    try:
        return await registry.deploymentService.get_deployment_status(deployment_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/{deployment_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="卸载部署",
)
async def undeploy_model(
    deployment_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    """卸载部署端点."""
    try:
        await registry.deploymentService.undeploy_model(deployment_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
