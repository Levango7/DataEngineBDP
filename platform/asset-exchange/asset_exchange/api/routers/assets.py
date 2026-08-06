"""资产管理路由.

端点：
    POST   /assets                  上架资产
    GET    /assets                   浏览资产市场
    GET    /assets/{id}              资产详情
    PUT    /assets/{id}              更新资产
    DELETE /assets/{id}              下架资产
    POST   /assets/{id}/subscribe    订阅资产
    GET    /assets/{id}/subscriptions 资产订阅列表
    GET    /assets/{id}/billing      计费记录
    GET    /assets/{id}/usage        使用统计
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import AliasChoices, BaseModel, Field

from asset_exchange.api.routers.deps import get_registry, status_for_error
from asset_exchange.models.asset import (
    Asset,
    AssetFilter,
    AssetPricing,
    AssetSchema,
    AssetUsage,
)
from asset_exchange.models.base import (
    AssetStatus,
    AssetType,
    SecurityLevel,
)
from asset_exchange.models.billing import BillingSummary
from asset_exchange.models.subscription import (
    SubscribeRequest,
    Subscription,
)
from asset_exchange.repositories import AssetExchangeError
from asset_exchange.services.registry import ServiceRegistry

router = APIRouter(prefix="/assets", tags=["assets"])


# ---------- 请求模型 ----------

class ListAssetRequest(BaseModel):
    """上架资产请求."""

    name: str
    type: AssetType
    # 租户标识字段统一为 tenantId（MODEL-2）；
    # 同时接受 owner 作为输入别名，保持向后兼容。
    tenantId: str = Field(
        ..., validation_alias=AliasChoices("tenantId", "owner")
    )
    description: str | None = None
    securityLevel: SecurityLevel = SecurityLevel.INTERNAL
    qualityScore: float = 0.0
    schema: AssetSchema | None = None
    sample: list[dict] | None = None
    updateFrequency: str = "static"
    tags: dict[str, str] = {}
    pricing: AssetPricing | None = None
    sourceRef: str | None = None


class UpdateAssetRequest(BaseModel):
    """更新资产请求."""

    name: str | None = None
    description: str | None = None
    qualityScore: float | None = None
    updateFrequency: str | None = None
    tags: dict[str, str] | None = None
    pricing: AssetPricing | None = None


# ---------- 路由 ----------

@router.post(
    "",
    response_model=Asset,
    status_code=status.HTTP_201_CREATED,
    summary="上架资产",
)
async def list_asset(
    req: ListAssetRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Asset:
    """上架一个新资产到流通市场."""
    import uuid

    asset = Asset(
        id=str(uuid.uuid4()),
        name=req.name,
        type=req.type,
        tenantId=req.tenantId,
        description=req.description,
        securityLevel=req.securityLevel,
        qualityScore=req.qualityScore,
        schema=req.schema or AssetSchema(),
        sample=req.sample,
        updateFrequency=req.updateFrequency,
        tags=req.tags,
        pricing=req.pricing or AssetPricing(),
        sourceRef=req.sourceRef,
    )
    try:
        return await registry.assetService.list_asset(asset)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "",
    response_model=list[Asset],
    summary="浏览资产市场",
)
async def list_assets(
    name: str | None = Query(default=None, description="名称模糊匹配"),
    type: AssetType | None = Query(default=None, description="按类型过滤"),
    status_: AssetStatus | None = Query(
        default=None, alias="status", description="按状态过滤"
    ),
    securityLevel: SecurityLevel | None = Query(
        default=None, description="按安全分级过滤"
    ),
    tenantId: str | None = Query(
        default=None, description="按租户 ID 过滤（推荐）"
    ),
    owner: str | None = Query(
        default=None,
        description="按提供方过滤（已废弃，请使用 tenantId；为兼容旧客户端保留）",
        deprecated=True,
    ),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Asset]:
    """浏览资产市场（默认只返回已上架资产）.

    租户过滤参数支持 ``tenantId``（推荐）与 ``owner``（向后兼容），
    二者同时提供时以 ``tenantId`` 为准。
    """
    # 租户标识统一为 tenantId（MODEL-2）：优先使用 tenantId，回退到 owner 以保持向后兼容
    effective_tenant_id = tenantId if tenantId is not None else owner
    filter_ = AssetFilter(
        name=name,
        type=type,
        status=status_,
        securityLevel=securityLevel,
        tenantId=effective_tenant_id,
        limit=limit,
        offset=offset,
    )
    return await registry.assetService.list_assets(filter_)


@router.get(
    "/{asset_id}",
    response_model=Asset,
    summary="资产详情",
)
async def get_asset(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> Asset:
    """获取资产详情."""
    try:
        return await registry.assetService.get_asset(asset_id)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.put(
    "/{asset_id}",
    response_model=Asset,
    summary="更新资产",
)
async def update_asset(
    asset_id: str,
    req: UpdateAssetRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Asset:
    """更新资产信息."""
    fields = {k: v for k, v in req.model_dump().items() if v is not None}
    try:
        return await registry.assetService.update_asset(asset_id, **fields)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/{asset_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="下架资产",
)
async def offline_asset(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    """下架资产（下架后状态置为 offline，可重新上架）."""
    try:
        await registry.assetService.offline_asset(asset_id)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{asset_id}/subscribe",
    response_model=Subscription,
    status_code=status.HTTP_201_CREATED,
    summary="订阅资产",
)
async def subscribe_asset(
    asset_id: str,
    req: SubscribeRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Subscription:
    """订阅资产（提交后进入待审批状态）."""
    try:
        # 把 durationDays 放入 pullConfig 便于审批时使用
        pull_config = dict(req.pullConfig)
        pull_config["_durationDays"] = req.durationDays
        req.pullConfig = pull_config
        return await registry.subscriptionService.subscribe(asset_id, req)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{asset_id}/subscriptions",
    response_model=list[Subscription],
    summary="资产订阅列表",
)
async def list_asset_subscriptions(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Subscription]:
    """列出某资产的所有订阅."""
    return await registry.subscriptionService.list_by_asset(asset_id)


@router.get(
    "/{asset_id}/billing",
    response_model=BillingSummary,
    summary="计费记录",
)
async def get_asset_billing(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> BillingSummary:
    """获取资产的计费记录汇总."""
    return await registry.billingService.list_by_asset(asset_id)


@router.get(
    "/{asset_id}/usage",
    response_model=AssetUsage,
    summary="使用统计",
)
async def get_asset_usage(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> AssetUsage:
    """获取资产使用统计."""
    try:
        return await registry.assetService.get_usage(asset_id)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
