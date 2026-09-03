"""订阅管理路由.

端点：
    GET  /subscriptions                       列出订阅（Sprint 3.2 补齐——前端 listSubscriptions 调用）
    POST /subscriptions/{id}/approve        审批订阅
    POST /subscriptions/{id}/deliver        交付数据
    GET  /subscriptions/{id}/delivery-status 交付状态
    POST /subscriptions/{id}/charge         计费（辅助端点）
    GET  /subscriptions/{id}/billing        订阅计费记录（Sprint 2.2 补齐）

注意：与 open-api-catalog 的 /subscriptions 共享前缀（语义不同——本服务承载资产交付订阅，
open-api-catalog 承载 API 访问订阅审批）。前端通过 SUB_BASE + 路径级操作明确分流：
assetMarket.ts 的 list/deliver/billing 走本服务，apiCatalog.ts 的 approve/suspend/resume/revoke
走 open-api-catalog；两个服务的 GET 根路由均返回 200 空列表。vite proxy 按子路径自动匹配：
具体操作（/{id}/approve 等）按最长前缀，根路由 GET 在 open-api-catalog 端可正常返回。
本服务补 GET 根路由以支持 assetMarket.listSubscriptions 的契约完整性（即使前端此刻
没有调用，但契约文档要求 /subscriptions 端点可路由）。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel

from asset_exchange.api.jwt_auth import AuthContext, getAuthContext
from asset_exchange.api.routers.deps import get_registry, status_for_error
from asset_exchange.models.base import AuditAction, SubscriptionStatus
from asset_exchange.models.delivery import (
    Delivery,
    DeliveryRequest,
    DeliveryStatusResponse,
)
from asset_exchange.models.billing import BillingRecord
from asset_exchange.models.subscription import (
    ApprovalRequest,
    SubscribeRequest,
    Subscription,
    SubscriptionFilter,
)
from asset_exchange.repositories import AssetExchangeError
from asset_exchange.services.registry import ServiceRegistry

router = APIRouter(prefix="/asset-subscriptions", tags=["subscriptions"])


# ---------- 请求模型 ----------


class ChargeRequest(BaseModel):
    """计费请求（交付成功后调用）."""

    usage: float = 1.0
    period: str | None = None


# ---------- 路由 ----------


@router.get(
    "",
    response_model=list[Subscription],
    summary="列出订阅",
)
async def list_subscriptions(
    assetId: str | None = Query(default=None, description="按资产过滤"),
    subscriberId: str | None = Query(default=None, description="按订阅方过滤"),
    status_: SubscriptionStatus | None = Query(default=None, alias="status", description="按状态过滤"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Subscription]:
    """按条件列出订阅（Sprint 3.2 补齐）。

    契约与 open-api-catalog 同步（均用 /subscriptions 根），但本服务专注资产交付订阅，
    供 assetMarket.ts.listSubscriptions 调用。前端通过 SUB_BASE 路径操作明确分流。
    """
    try:
        return await registry.subscriptionService.list_subscriptions(
            SubscriptionFilter(
                assetId=assetId,
                subscriberId=subscriberId,
                status=status_,
                limit=limit,
                offset=offset,
            )
        )
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{subscription_id}/approve",
    response_model=Subscription,
    summary="审批订阅",
)
async def approve_subscription(
    subscription_id: str,
    req: ApprovalRequest,
    registry: ServiceRegistry = Depends(get_registry),
    ctx: AuthContext = Depends(getAuthContext),
) -> Subscription:
    """审批订阅（通过或驳回）. 审批人身份一律取 JWT（approverId 自报不再采信）."""
    try:
        if req.action == "approve":
            result = await registry.subscriptionService.approve(subscription_id, ctx.userId)
        elif req.action == "reject":
            result = await registry.subscriptionService.reject(
                subscription_id,
                ctx.userId,
                req.reason or "未提供驳回原因",
            )
        else:
            raise HTTPException(
                status_code=422,
                detail=f"action 必须为 approve 或 reject，得到 {req.action}",
            )
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.SUBSCRIBE,
            actor_id=ctx.userId,
            asset_id=result.assetId,
            subscription_id=subscription_id,
            detail={"action": req.action, "reason": req.reason},
        )
        return result
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{subscription_id}/deliver",
    response_model=Delivery,
    status_code=status.HTTP_201_CREATED,
    summary="交付数据",
)
async def deliver_data(
    subscription_id: str,
    req: DeliveryRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Delivery:
    """交付数据（支持 API / 文件 / 数据库直连三种方式）."""
    try:
        result = await registry.deliveryService.deliver(subscription_id, req)
        # 审计留痕
        await registry.auditService.log(
            action=AuditAction.DELIVER,
            actor_id="system",
            subscription_id=subscription_id,
            detail={
                "method": req.method.value,
                "status": result.status.value,
                "dataRows": result.dataRows,
            },
        )
        return result
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{subscription_id}/delivery-status",
    response_model=DeliveryStatusResponse,
    summary="交付状态",
)
async def get_delivery_status(
    subscription_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> DeliveryStatusResponse:
    """获取交付状态（按订阅 ID 查最新交付）."""
    try:
        return await registry.deliveryService.get_delivery_status(subscription_id)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{subscription_id}/charge",
    response_model=dict,
    summary="计费（辅助端点）",
)
async def charge_subscription(
    subscription_id: str,
    req: ChargeRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """对订阅进行计费（辅助端点，便于测试触发计费）."""
    try:
        record = await registry.billingService.charge(
            subscription_id=subscription_id,
            usage=req.usage,
            period=req.period,
        )
        return record.model_dump()
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{subscription_id}/billing",
    response_model=list[BillingRecord],
    summary="订阅计费记录",
)
async def list_subscription_billing(
    subscription_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> list[BillingRecord]:
    """列出某订阅的计费记录（Sprint 2.2：service 层 list_by_subscription 补 HTTP 路由）."""
    try:
        return await registry.billingService.list_by_subscription(subscription_id)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
