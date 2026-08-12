"""计量与文档路由.

对应详细设计 §7 接口契约：
    GET /api/l5/v1/apis/{apiId}/stats { range } → { calls, successRate, p99, cost }
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from openapi_catalog.api.routers.deps import get_registry, status_for_error
from openapi_catalog.models import APIMetrics
from openapi_catalog.repositories import CatalogError
from openapi_catalog.services.registry import ServiceRegistry

router = APIRouter(prefix="/apis", tags=["metrics", "docs"])


# ---------- 计量 ----------


@router.get(
    "/{api_id}/metrics",
    response_model=APIMetrics,
    summary="API 调用计量",
)
async def get_metrics(
    api_id: str,
    range: str = Query(default="7d", description="时间范围: 1h/24h/7d/30d"),
    consumerTenantId: str | None = Query(default=None, description="按消费者过滤"),
    registry: ServiceRegistry = Depends(get_registry),
) -> APIMetrics:
    """获取 API 调用计量（调用量/成功率/P99延迟/费用）."""
    try:
        return await registry.meteringService.get_metrics(api_id, range, consumerTenantId)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


# ---------- 文档 ----------


@router.get(
    "/{api_id}/docs",
    summary="API 文档(OpenAPI 3.0)",
)
async def get_openapi_docs(
    api_id: str,
    format: str = Query(default="openapi", description="格式: openapi / markdown"),
    registry: ServiceRegistry = Depends(get_registry),
) -> dict | str:
    """获取 API 文档（OpenAPI 3.0 Spec 或 Markdown）."""
    try:
        if format == "markdown":
            return await registry.docGeneratorService.generate_markdown_doc(api_id)
        return await registry.docGeneratorService.generate_openapi_spec(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


# ---------- APISIX 配置 ----------


@router.get(
    "/{api_id}/apisix-config",
    summary="APISIX 路由配置",
)
async def get_apisix_config(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """获取 APISIX 路由配置."""
    try:
        route = await registry.apisixConfigService.generate_route(api_id)
        return route.to_apisix_payload()
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{api_id}/apisix-deploy",
    summary="部署 APISIX 路由",
)
async def deploy_apisix_route(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """部署 APISIX 路由到网关."""
    try:
        return await registry.apisixConfigService.deploy_route(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/{api_id}/apisix-deploy",
    summary="删除 APISIX 路由",
)
async def undeploy_apisix_route(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """从 APISIX 删除路由."""
    try:
        return await registry.apisixConfigService.undeploy_route(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
