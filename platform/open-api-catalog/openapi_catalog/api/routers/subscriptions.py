"""订阅管理路由.

对应详细设计 §7 接口契约：
    POST /api/l5/v1/apis/{apiId}/subscribe { purpose, quotaExpect } → subscriptionId
    POST /api/l5/v1/subscriptions/{subId}/approve { approve, reason } → { ak, sk }
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from openapi_catalog.api.routers.deps import get_registry, status_for_error
from openapi_catalog.models import (
    APISubscription,
    ApproveRequest,
    SubscribeRequest,
    SubscriptionFilter,
    SubscriptionStatus,
)
from openapi_catalog.repositories import CatalogError
from openapi_catalog.services.registry import ServiceRegistry

router = APIRouter(prefix="/apis", tags=["subscriptions"])


@router.post(
    "/{api_id}/subscribe",
    response_model=APISubscription,
    status_code=status.HTTP_201_CREATED,
    summary="申请订阅",
)
async def subscribe_api(
    api_id: str,
    req: SubscribeRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> APISubscription:
    """申请订阅 API.

    消费者浏览目录 → 选中 API → 提交订阅申请(含用途/配额期望)
    → 提供方审批 → 经 Keycloak 发放 AK/SK(范围限定该 API)
    """
    try:
        # 获取 API 以填充 providerTenantId
        api = await registry.apiRegistryService.get_api(api_id)
        sub = APISubscription(
            id="",
            apiId=api_id,
            subscriberId=req.subscriberId,
            subscriberTenantId=req.subscriberTenantId,
            providerTenantId=api.providerTenantId,
            purpose=req.purpose,
            quotaExpect=req.quotaExpect,
        )
        return await registry.subscriptionService.apply_subscription(api_id, sub)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{api_id}/subscribers",
    response_model=list[APISubscription],
    summary="订阅者列表",
)
async def list_subscribers(
    api_id: str,
    status_: SubscriptionStatus | None = Query(default=None, alias="status", description="状态过滤"),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[APISubscription]:
    """列出某 API 的所有订阅者."""
    try:
        if status_ is not None:
            filter_ = SubscriptionFilter(apiId=api_id, status=status_, limit=1000)
            return await registry.subscriptionService.list_subscriptions(filter_)
        return await registry.subscriptionService.list_subscribers(api_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


# ---------- 订阅审批与管理 ----------

subscriptions_router = APIRouter(prefix="/subscriptions", tags=["subscriptions"])


@subscriptions_router.get(
    "/{subscription_id}",
    response_model=APISubscription,
    summary="获取订阅详情",
)
async def get_subscription(
    subscription_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APISubscription:
    """获取订阅详情."""
    try:
        return await registry.subscriptionService.get_subscription(subscription_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@subscriptions_router.get(
    "",
    response_model=list[APISubscription],
    summary="列出订阅",
)
async def list_subscriptions(
    apiId: str | None = Query(default=None),
    subscriberId: str | None = Query(default=None),
    subscriberTenantId: str | None = Query(default=None),
    status_: SubscriptionStatus | None = Query(default=None, alias="status"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[APISubscription]:
    """按条件列出订阅."""
    filter_ = SubscriptionFilter(
        apiId=apiId,
        subscriberId=subscriberId,
        subscriberTenantId=subscriberTenantId,
        status=status_,
        limit=limit,
        offset=offset,
    )
    return await registry.subscriptionService.list_subscriptions(filter_)


@subscriptions_router.post(
    "/{subscription_id}/approve",
    response_model=APISubscription,
    summary="审批订阅",
)
async def approve_subscription(
    subscription_id: str,
    req: ApproveRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> APISubscription:
    """审批订阅申请（通过则发放 AK/SK）."""
    try:
        sub = await registry.subscriptionService.approve_subscription(subscription_id, req)
        # 配置订阅级限流
        if sub.grantedQuota > 0:
            registry.rateLimiter.configure_subscription(sub.id, sub.grantedQuota)
        return sub
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@subscriptions_router.post(
    "/{subscription_id}/suspend",
    response_model=APISubscription,
    summary="暂停订阅",
)
async def suspend_subscription(
    subscription_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APISubscription:
    """暂停订阅."""
    try:
        return await registry.subscriptionService.suspend_subscription(subscription_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@subscriptions_router.post(
    "/{subscription_id}/resume",
    response_model=APISubscription,
    summary="恢复订阅",
)
async def resume_subscription(
    subscription_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APISubscription:
    """恢复订阅."""
    try:
        return await registry.subscriptionService.resume_subscription(subscription_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@subscriptions_router.post(
    "/{subscription_id}/revoke",
    response_model=APISubscription,
    summary="吊销订阅",
)
async def revoke_subscription(
    subscription_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APISubscription:
    """吊销订阅（清空 AK/SK）."""
    try:
        return await registry.subscriptionService.revoke_subscription(subscription_id)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
