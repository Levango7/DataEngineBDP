"""部署管理路由."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status

from llmops.api.jwt_auth import AuthContext, getAuthContext
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
    ctx: AuthContext = Depends(getAuthContext),
) -> Deployment:
    """部署模型到推理端点（注册到 L4.5.6 大模型网关）.

    租户隔离：部署归属当前请求租户 ctx.tenantId。
    """
    # 注入租户 ID 到 config，由 deployer 写入 Deployment 实体
    config.tenantId = ctx.tenantId
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
    ctx: AuthContext = Depends(getAuthContext),
) -> list[Deployment]:
    """列出部署（按租户隔离：普通用户仅见本租户部署，admin 可见全部）."""
    deployments = await registry.deploymentService.list_deployments()
    # 租户隔离：非 admin 仅返回本租户部署
    if ctx.role != "admin" and ctx.tenantId:
        deployments = [d for d in deployments if getattr(d, "tenantId", "") == ctx.tenantId]
    return deployments


@router.get(
    "/{deployment_id}",
    response_model=Deployment,
    summary="部署状态",
)
async def get_deployment_status(
    deployment_id: str,
    registry: ServiceRegistry = Depends(get_registry),
    ctx: AuthContext = Depends(getAuthContext),
) -> Deployment:
    """获取部署详情（含状态与端点 URL）."""
    try:
        deployment = await registry.deploymentService.get_deployment_status(deployment_id)
        # 租户隔离：非 admin 仅可查看本租户部署
        if ctx.role != "admin" and ctx.tenantId:
            if getattr(deployment, "tenantId", "") != ctx.tenantId:
                raise HTTPException(status_code=404, detail="部署不存在")
        return deployment
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
    ctx: AuthContext = Depends(getAuthContext),
) -> None:
    """卸载部署端点."""
    try:
        # 租户隔离：非 admin 仅可卸载本租户部署
        if ctx.role != "admin" and ctx.tenantId:
            deployment = await registry.deploymentService.get_deployment_status(deployment_id)
            if getattr(deployment, "tenantId", "") != ctx.tenantId:
                raise HTTPException(status_code=404, detail="部署不存在")
        await registry.deploymentService.undeploy_model(deployment_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
