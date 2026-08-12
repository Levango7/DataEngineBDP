"""API 注册管理路由.

对应详细设计 §7 接口契约：
    POST /api/l5/v1/apis/publish { spec, runtime, sla, billing } → apiId
    GET  /api/l5/v1/catalog { category?, keyword?, page } → [ApiEntry]
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from openapi_catalog.api.routers.deps import get_registry, status_for_error
from openapi_catalog.models import (
    APIDefinition,
    APIFilter,
    APIParam,
    APIResponse,
    APIStatus,
    APIUpdateRequest,
    APIUpstream,
    AuthType,
    CostStrategy,
    HttpMethod,
    SLALevel,
)
from openapi_catalog.repositories import CatalogError
from openapi_catalog.services.registry import ServiceRegistry
from pydantic import BaseModel, Field

router = APIRouter(prefix="/apis", tags=["apis"])


# ---------- 请求模型 ----------


class RegisterAPIRequest(BaseModel):
    """注册 API 请求."""

    name: str = Field(..., min_length=1, max_length=128)
    version: str = Field(..., pattern=r"^\d+\.\d+\.\d+$")
    description: str | None = None
    category: str = "default"
    tags: list[str] = Field(default_factory=list)
    method: HttpMethod
    path: str = Field(..., pattern=r"^/")
    params: list[APIParam] = Field(default_factory=list)
    responses: list[APIResponse] | None = None
    authType: AuthType = AuthType.API_KEY
    upstream: APIUpstream
    sla: SLALevel = SLALevel.SILVER
    costStrategy: CostStrategy = CostStrategy.BY_CALL
    costUnitPrice: float = Field(default=0.0, ge=0)
    monthlyQuota: int | None = Field(default=None, ge=0)
    status: APIStatus = APIStatus.DRAFT
    providerTenantId: str


# ---------- 路由 ----------


@router.post(
    "",
    response_model=APIDefinition,
    status_code=status.HTTP_201_CREATED,
    summary="注册 API",
)
async def register_api(
    req: RegisterAPIRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """注册一个新 API（创建服务目录条目）."""
    try:
        data = req.model_dump()
        if data.get("responses") is None:
            data.pop("responses", None)
        api = APIDefinition(**data)
        return await registry.apiRegistryService.register_api(api)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "",
    response_model=list[APIDefinition],
    summary="列出 API",
)
async def list_apis(
    name: str | None = Query(default=None, description="名称模糊匹配"),
    category: str | None = Query(default=None, description="分类过滤"),
    tag: str | None = Query(default=None, description="标签过滤"),
    status_: APIStatus | None = Query(default=None, alias="status", description="状态过滤"),
    providerTenantId: str | None = Query(default=None, description="提供方租户过滤"),
    keyword: str | None = Query(default=None, description="全文搜索"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[APIDefinition]:
    """按条件列出 API（服务目录浏览）."""
    filter_ = APIFilter(
        name=name,
        category=category,
        tag=tag,
        status=status_,
        providerTenantId=providerTenantId,
        keyword=keyword,
        limit=limit,
        offset=offset,
    )
    return await registry.apiRegistryService.list_apis(filter_)


@router.get(
    "/{api_id}",
    response_model=APIDefinition,
    summary="获取 API 详情",
)
async def get_api(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """根据 ID 获取 API 详情."""
    try:
        return await registry.apiRegistryService.get_api(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.put(
    "/{api_id}",
    response_model=APIDefinition,
    summary="更新 API",
)
async def update_api(
    api_id: str,
    req: APIUpdateRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """更新 API（部分字段）."""
    try:
        return await registry.apiRegistryService.update_api(api_id, req)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/{api_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="注销 API",
)
async def delete_api(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    """注销 API（仅允许在 DRAFT/REJECTED/ARCHIVED 状态）."""
    try:
        await registry.apiRegistryService.delete_api(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


# ---------- 状态转换 ----------


class StatusTransitionRequest:
    """状态转换请求（query 参数形式）."""


@router.post(
    "/{api_id}/submit-review",
    response_model=APIDefinition,
    summary="提交安全审核",
)
async def submit_for_review(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """提交安全审核."""
    try:
        return await registry.apiRegistryService.submit_for_review(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{api_id}/approve",
    response_model=APIDefinition,
    summary="审核通过",
)
async def approve_api(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """审核通过."""
    try:
        return await registry.apiRegistryService.approve(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{api_id}/reject",
    response_model=APIDefinition,
    summary="审核驳回",
)
async def reject_api(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """审核驳回."""
    try:
        return await registry.apiRegistryService.reject(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{api_id}/publish",
    response_model=APIDefinition,
    summary="发布 API",
)
async def publish_api(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """发布 API 到网关."""
    try:
        api = await registry.apiRegistryService.publish(api_id)
        # 配置限流
        registry.rateLimiter.configure_api(api.id, registry.settings.defaultRateLimit)
        return api
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{api_id}/deprecate",
    response_model=APIDefinition,
    summary="废弃 API",
)
async def deprecate_api(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """废弃 API（进入宽限期）."""
    try:
        return await registry.apiRegistryService.deprecate(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{api_id}/archive",
    response_model=APIDefinition,
    summary="归档 API",
)
async def archive_api(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """归档下线 API."""
    try:
        return await registry.apiRegistryService.archive(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
