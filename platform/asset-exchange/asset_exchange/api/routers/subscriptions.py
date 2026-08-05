"""订阅管理路由.

端点：
    POST /subscriptions/{id}/approve        审批订阅
    POST /subscriptions/{id}/deliver        交付数据
    GET  /subscriptions/{id}/delivery-status 交付状态
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel

from asset_exchange.api.routers.deps import get_registry, status_for_error
from asset_exchange.models.delivery import (
    Delivery,
    DeliveryRequest,
    DeliveryStatusResponse,
)
from asset_exchange.models.subscription import ApprovalRequest, Subscription
from asset_exchange.repositories import AssetExchangeError
from asset_exchange.services.registry import ServiceRegistry

router = APIRouter(prefix="/subscriptions", tags=["subscriptions"])


# ---------- 请求模型 ----------

class ChargeRequest(BaseModel):
    """计费请求（交付成功后调用）."""

    usage: float = 1.0
    period: str | None = None


# ---------- 路由 ----------

@router.post(
    "/{subscription_id}/approve",
    response_model=Subscription,
    summary="审批订阅",
)
async def approve_subscription(
    subscription_id: str,
    req: ApprovalRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Subscription:
    """审批订阅（通过或驳回）."""
    try:
        if req.action == "approve":
            return await registry.subscriptionService.approve(
                subscription_id, req.approverId
            )
        elif req.action == "reject":
            return await registry.subscriptionService.reject(
                subscription_id,
                req.approverId,
                req.reason or "未提供驳回原因",
            )
        else:
            raise HTTPException(
                status_code=422,
                detail=f"action 必须为 approve 或 reject，得到 {req.action}",
            )
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
        return await registry.deliveryService.deliver(subscription_id, req)
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