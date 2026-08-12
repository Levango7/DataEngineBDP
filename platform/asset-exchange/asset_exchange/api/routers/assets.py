"""资产管理路由.

端点：
    POST   /assets                       上架资产（兼容旧接口）
    POST   /assets/register              资产登记
    POST   /assets/{id}/audit            资产审核
    POST   /assets/{id}/publish          资产上架
    GET    /assets                        浏览资产市场
    GET    /assets/{id}                   资产详情
    PUT    /assets/{id}                   更新资产
    DELETE /assets/{id}                   下架资产
    POST   /assets/{id}/subscribe         订阅资产
    POST   /assets/{id}/download          下载资产
    POST   /assets/{id}/invoke            API 调用资产
    GET    /assets/{id}/subscriptions     资产订阅列表
    GET    /assets/{id}/billing           计费记录
    GET    /assets/{id}/usage             使用统计
    POST   /assets/{id}/settle            结算
    GET    /assets/{id}/settlements       结算列表
    POST   /assets/{id}/allocate          分账
    GET    /assets/{id}/allocations       分账列表
    GET    /assets/{id}/audit-logs        资产审计日志
"""

from __future__ import annotations

import uuid

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
from asset_exchange.models.audit import AuditLog
from asset_exchange.models.base import (
    AssetAuditResult,
    AssetStatus,
    AssetType,
    AuditAction,
    AuditResult,
    SecurityLevel,
)
from asset_exchange.models.billing import BillingSummary
from asset_exchange.models.settlement import (
    AllocateRequest,
    Allocation,
    Settlement,
    SettleRequest,
)
from asset_exchange.models.subscription import (
    SubscribeRequest,
    Subscription,
)
from asset_exchange.repositories import AssetExchangeError
from asset_exchange.services.registry import ServiceRegistry

router = APIRouter(prefix="/assets", tags=["assets"])


# ---------- 请求模型 ----------


class ListAssetRequest(BaseModel):
    """上架资产请求（兼容旧接口）."""

    name: str
    type: AssetType
    tenantId: str = Field(..., validation_alias=AliasChoices("tenantId", "owner"))
    description: str | None = None
    securityLevel: SecurityLevel = SecurityLevel.INTERNAL
    qualityScore: float = 0.0
    schema: AssetSchema | None = None
    sample: list[dict] | None = None
    updateFrequency: str = "static"
    tags: dict[str, str] = {}
    pricing: AssetPricing | None = None
    sourceRef: str | None = None


class RegisterAssetRequest(BaseModel):
    """资产登记请求."""

    name: str
    type: AssetType
    tenantId: str = Field(..., validation_alias=AliasChoices("tenantId", "owner"))
    description: str | None = None
    securityLevel: SecurityLevel = SecurityLevel.INTERNAL
    qualityScore: float = 0.0
    schema: AssetSchema | None = None
    sample: list[dict] | None = None
    updateFrequency: str = "static"
    tags: dict[str, str] = {}
    pricing: AssetPricing | None = None
    sourceRef: str | None = None


class AuditAssetRequest(BaseModel):
    """资产审核请求."""

    result: AssetAuditResult = Field(..., description="审核结果")
    auditorId: str = Field(..., description="审核人 ID")
    reason: str | None = Field(default=None, description="审核原因")


class UpdateAssetRequest(BaseModel):
    """更新资产请求."""

    name: str | None = None
    description: str | None = None
    qualityScore: float | None = None
    updateFrequency: str | None = None
    tags: dict[str, str] | None = None
    pricing: AssetPricing | None = None


class DownloadRequest(BaseModel):
    """资产下载请求."""

    subscriberId: str = Field(..., description="下载方租户 ID")
    rows: int = Field(default=100, ge=1, le=1000000, description="下载行数")


class InvokeRequest(BaseModel):
    """资产 API 调用请求."""

    subscriberId: str = Field(..., description="调用方租户 ID")
    params: dict = Field(default_factory=dict, description="调用参数")


# ---------- 路由 ----------


@router.post(
    "/register",
    response_model=Asset,
    status_code=status.HTTP_201_CREATED,
    summary="资产登记",
)
async def register_asset(
    req: RegisterAssetRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Asset:
    """资产登记（元数据登记，状态置为 DRAFT）."""
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
        result = await registry.assetService.register(asset)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.REGISTER,
            actor_id=req.tenantId,
            asset_id=result.id,
            tenant_id=req.tenantId,
            detail={"name": req.name, "type": req.type.value},
        )
        return result
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{asset_id}/audit",
    response_model=Asset,
    summary="资产审核",
)
async def audit_asset(
    asset_id: str,
    req: AuditAssetRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Asset:
    """资产审核（合规/质量/分级检查）."""
    try:
        result = await registry.assetService.audit(asset_id, req.result, req.auditorId, req.reason)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.AUDIT,
            actor_id=req.auditorId,
            asset_id=asset_id,
            tenant_id=result.tenantId,
            result=AuditResult.SUCCESS if req.result == AssetAuditResult.APPROVED else AuditResult.FAILURE,
            detail={
                "result": req.result.value,
                "reason": req.reason,
                "finalStatus": result.status.value,
            },
        )
        return result
    except AssetExchangeError as exc:
        # 审计留痕（失败）
        await registry.auditService.log(
            action=AuditAction.AUDIT,
            actor_id=req.auditorId,
            asset_id=asset_id,
            result=AuditResult.FAILURE,
            detail={"error": str(exc)},
        )
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{asset_id}/publish",
    response_model=Asset,
    summary="资产上架",
)
async def publish_asset(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> Asset:
    """资产上架（自动执行合规/质量/分级检查）."""
    try:
        a = await registry.assetService.get_asset(asset_id)
        result = await registry.assetService.publish(asset_id)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.PUBLISH,
            actor_id=a.tenantId,
            asset_id=asset_id,
            tenant_id=a.tenantId,
            detail={"status": result.status.value},
        )
        return result
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "",
    response_model=Asset,
    status_code=status.HTTP_201_CREATED,
    summary="上架资产（兼容旧接口）",
)
async def list_asset(
    req: ListAssetRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Asset:
    """上架一个新资产到流通市场（兼容旧接口，等价于 register + publish）."""
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
        result = await registry.assetService.list_asset(asset)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.PUBLISH,
            actor_id=req.tenantId,
            asset_id=result.id,
            tenant_id=req.tenantId,
            detail={"name": req.name, "type": req.type.value},
        )
        return result
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
    status_: AssetStatus | None = Query(default=None, alias="status", description="按状态过滤"),
    securityLevel: SecurityLevel | None = Query(default=None, description="按安全分级过滤"),
    tenantId: str | None = Query(default=None, description="按租户 ID 过滤（推荐）"),
    owner: str | None = Query(
        default=None,
        description="按提供方过滤（已废弃，请使用 tenantId；为兼容旧客户端保留）",
        deprecated=True,
    ),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Asset]:
    """浏览资产市场（默认只返回已上架资产）."""
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
        a = await registry.assetService.offline_asset(asset_id)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.OFFLINE,
            actor_id=a.tenantId,
            asset_id=asset_id,
            tenant_id=a.tenantId,
        )
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
        result = await registry.subscriptionService.subscribe(asset_id, req)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.SUBSCRIBE,
            actor_id=req.subscriberId,
            asset_id=asset_id,
            subscription_id=result.id,
            detail={"period": req.period, "durationDays": req.durationDays},
        )
        return result
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{asset_id}/download",
    response_model=dict,
    summary="下载资产",
)
async def download_asset(
    asset_id: str,
    req: DownloadRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """下载资产（流通方式之一）."""
    try:
        result = await registry.assetService.download(asset_id, req.subscriberId, req.rows)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.DOWNLOAD,
            actor_id=req.subscriberId,
            asset_id=asset_id,
            detail={"rows": req.rows},
        )
        return result
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{asset_id}/invoke",
    response_model=dict,
    summary="API 调用资产",
)
async def invoke_asset(
    asset_id: str,
    req: InvokeRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """API 调用资产（流通方式之一）."""
    try:
        result = await registry.assetService.invoke(asset_id, req.subscriberId, req.params)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.INVOKE,
            actor_id=req.subscriberId,
            asset_id=asset_id,
            detail={"params": req.params},
        )
        return result
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


# ---------- 结算与分账 ----------


@router.post(
    "/{asset_id}/settle",
    response_model=Settlement,
    summary="资产结算",
)
async def settle_asset(
    asset_id: str,
    req: SettleRequest | None = None,
    registry: ServiceRegistry = Depends(get_registry),
) -> Settlement:
    """对某资产执行结算（自动汇总计费记录，计算分成）."""
    try:
        result = await registry.settlementService.settle(asset_id, req)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.SETTLE,
            actor_id="system",
            asset_id=asset_id,
            settlement_id=result.id,
            tenant_id=result.tenantId,
            detail={
                "period": result.period,
                "totalAmount": result.totalAmount,
                "providerRevenue": result.providerRevenue,
                "platformRevenue": result.platformRevenue,
            },
        )
        return result
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{asset_id}/settlements",
    response_model=list[Settlement],
    summary="结算列表",
)
async def list_settlements(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Settlement]:
    """列出某资产的结算记录."""
    return await registry.settlementService.list_by_asset(asset_id)


@router.post(
    "/{asset_id}/allocate",
    response_model=Allocation,
    summary="资产分账",
)
async def allocate_asset(
    asset_id: str,
    req: AllocateRequest | None = None,
    registry: ServiceRegistry = Depends(get_registry),
) -> Allocation:
    """对某资产的最新结算执行分账.

    分账到数据提供方与平台，比例可配置。
    """
    try:
        # 取最新结算记录
        settlements = await registry.settlementService.list_by_asset(asset_id)
        if not settlements:
            raise HTTPException(
                status_code=404,
                detail=f"资产 {asset_id} 无结算记录，请先结算再分账",
            )
        latest_settlement = settlements[0]
        result = await registry.allocationService.allocate(latest_settlement.id, req)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.ALLOCATE,
            actor_id="system",
            asset_id=asset_id,
            settlement_id=latest_settlement.id,
            detail={
                "providerAmount": result.providerAmount,
                "platformAmount": result.platformAmount,
                "providerAccountId": result.providerAccountId,
                "platformAccountId": result.platformAccountId,
            },
        )
        return result
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{asset_id}/allocations",
    response_model=list[Allocation],
    summary="分账列表",
)
async def list_allocations(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Allocation]:
    """列出某资产的分账记录."""
    return await registry.allocationService.list_by_asset(asset_id)


@router.get(
    "/{asset_id}/audit-logs",
    response_model=list[AuditLog],
    summary="资产审计日志",
)
async def list_asset_audit_logs(
    asset_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> list[AuditLog]:
    """列出某资产的所有审计日志（按时间升序）."""
    return await registry.auditService.list_by_asset(asset_id)
