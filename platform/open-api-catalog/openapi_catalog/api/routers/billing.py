"""订阅计费增强路由 - Key 颁发 / 限流配置 / 计费配置.

对应 T040 任务要求：
    POST   /api/v1/subscriptions/{id}/keys         重新颁发 AK/SK
    GET    /api/v1/subscriptions/{id}/keys         查询当前 AK（不返回 SK）
    PUT    /api/v1/subscriptions/{id}/rate-limit   配置限流（QPS/并发）
    GET    /api/v1/subscriptions/{id}/rate-limit   查询限流配置
    PUT    /api/v1/apis/{id}/billing               配置 API 计费策略
    GET    /api/v1/apis/{id}/billing               查询 API 计费策略
"""
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from openapi_catalog.api.routers.deps import get_registry, status_for_error
from openapi_catalog.models import (
    APIDefinition,
    APISubscription,
    CostStrategy,
    SubscriptionStatus,
)
from openapi_catalog.repositories import (
    CatalogError,
    SubscriptionNotFoundError,
    SubscriptionStatusError,
)
from openapi_catalog.repositories.mock import generate_ak_sk
from openapi_catalog.services.registry import ServiceRegistry

# 订阅增强路由（挂在 /subscriptions 下）
subscriptions_billing_router = APIRouter(
    prefix="/subscriptions", tags=["subscription-billing"]
)

# API 计费配置路由（挂在 /apis 下）
api_billing_router = APIRouter(prefix="/apis", tags=["billing"])


# ---------- 请求/响应模型 ----------

class IssueKeyRequest(BaseModel):
    """重新颁发 AK/SK 请求."""

    reason: str = Field(default="重新颁发", description="颁发原因")
    operator: str = Field(..., description="操作人")


class IssueKeyResponse(BaseModel):
    """颁发 AK/SK 响应."""

    subscriptionId: str
    accessKey: str
    secretKey: str
    issuedAt: datetime
    reason: str


class KeyInfoResponse(BaseModel):
    """Key 信息响应（不含 SK）."""

    subscriptionId: str
    accessKey: str | None
    hasSecretKey: bool
    status: str


class RateLimitConfig(BaseModel):
    """限流配置."""

    qps: int = Field(..., ge=1, le=100000, description="每秒请求数")
    concurrent: int = Field(default=0, ge=0, le=10000, description="并发数（0=不限）")
    burst: int = Field(default=0, ge=0, description="突发容量（0=与 qps 相同）")


class RateLimitConfigResponse(BaseModel):
    """限流配置响应."""

    subscriptionId: str
    qps: int
    concurrent: int
    burst: int
    updatedAt: datetime


class BillingConfigRequest(BaseModel):
    """计费配置请求."""

    costStrategy: str = Field(..., description="计费策略: by_call/by_bytes/monthly_package")
    costUnitPrice: float = Field(..., ge=0, description="单价")
    monthlyQuota: int | None = Field(
        default=None, ge=0, description="月配额（仅 monthly_package）"
    )


class BillingConfigResponse(BaseModel):
    """计费配置响应."""

    apiId: str
    costStrategy: str
    costUnitPrice: float
    monthlyQuota: int | None
    description: str
    updatedAt: datetime


# ---------- Key 颁发 ----------

@subscriptions_billing_router.post(
    "/{subscription_id}/keys",
    response_model=IssueKeyResponse,
    status_code=status.HTTP_201_CREATED,
    summary="重新颁发 AK/SK",
)
async def issue_key(
    subscription_id: str,
    req: IssueKeyRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> IssueKeyResponse:
    """重新颁发订阅的 AK/SK.

    场景：原 Key 泄露、定期轮换、安全事件后重置。
    仅对 ACTIVE/SUSPENDED 状态的订阅可重新颁发。
    旧 Key 立即失效。
    """
    try:
        sub = await registry.subscriptionService.get_subscription(subscription_id)
        if sub.status not in (SubscriptionStatus.ACTIVE, SubscriptionStatus.SUSPENDED):
            raise SubscriptionStatusError(subscription_id, sub.status.value)

        # 生成新 AK/SK
        ak, sk = generate_ak_sk()
        sub.accessKey = ak
        sub.secretKey = sk
        sub.updatedAt = datetime.now()
        await registry.store.save_subscription(sub)

        # 重新配置限流（保持原配额）
        if sub.grantedQuota > 0:
            registry.rateLimiter.configure_subscription(sub.id, sub.grantedQuota)

        return IssueKeyResponse(
            subscriptionId=subscription_id,
            accessKey=ak,
            secretKey=sk,
            issuedAt=sub.updatedAt,
            reason=req.reason,
        )
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@subscriptions_billing_router.get(
    "/{subscription_id}/keys",
    response_model=KeyInfoResponse,
    summary="查询 Key 信息",
)
async def get_key_info(
    subscription_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> KeyInfoResponse:
    """查询订阅的 Key 信息（出于安全考虑不返回 SK）."""
    try:
        sub = await registry.subscriptionService.get_subscription(subscription_id)
        return KeyInfoResponse(
            subscriptionId=subscription_id,
            accessKey=sub.accessKey,
            hasSecretKey=sub.secretKey is not None,
            status=sub.status.value,
        )
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


# ---------- 限流配置 ----------

# 内存中存储限流配置（实际场景持久化到 DB）
_rate_limit_configs: dict[str, RateLimitConfig] = {}


@subscriptions_billing_router.put(
    "/{subscription_id}/rate-limit",
    response_model=RateLimitConfigResponse,
    summary="配置限流",
)
async def configure_rate_limit(
    subscription_id: str,
    req: RateLimitConfig,
    registry: ServiceRegistry = Depends(get_registry),
) -> RateLimitConfigResponse:
    """配置订阅级限流.

    Args:
        subscription_id: 订阅 ID.
        req: 限流配置（QPS + 并发 + 突发）.

    Returns:
        配置后的限流信息.
    """
    try:
        # 校验订阅存在
        sub = await registry.subscriptionService.get_subscription(subscription_id)

        # 配置限流器
        # QPS 通过令牌桶实现（每秒令牌数 = qps）
        # 并发通过信号量实现（此处记录配置，由 APISIX limit-conn 插件执行）
        registry.rateLimiter.configure_subscription(
            subscription_id, req.qps * 60  # 转换为次/分钟
        )
        registry.rateLimiter.configure_subscription_rate(
            subscription_id, req.qps, burst=req.burst  # 按秒 QPS 限流
        )

        # 保存配置
        _rate_limit_configs[subscription_id] = req

        return RateLimitConfigResponse(
            subscriptionId=subscription_id,
            qps=req.qps,
            concurrent=req.concurrent,
            burst=req.burst if req.burst > 0 else req.qps,
            updatedAt=datetime.now(),
        )
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@subscriptions_billing_router.get(
    "/{subscription_id}/rate-limit",
    response_model=RateLimitConfigResponse,
    summary="查询限流配置",
)
async def get_rate_limit(
    subscription_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> RateLimitConfigResponse:
    """查询订阅的限流配置."""
    try:
        # 校验订阅存在
        await registry.subscriptionService.get_subscription(subscription_id)

        config = _rate_limit_configs.get(subscription_id)
        if config is None:
            # 返回默认配置
            return RateLimitConfigResponse(
                subscriptionId=subscription_id,
                qps=registry.settings.defaultRateLimit,
                concurrent=0,
                burst=registry.settings.defaultRateLimit,
                updatedAt=datetime.now(),
            )
        return RateLimitConfigResponse(
            subscriptionId=subscription_id,
            qps=config.qps,
            concurrent=config.concurrent,
            burst=config.burst if config.burst > 0 else config.qps,
            updatedAt=datetime.now(),
        )
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


# ---------- API 计费配置 ----------

@api_billing_router.put(
    "/{api_id}/billing",
    response_model=BillingConfigResponse,
    summary="配置 API 计费策略",
)
async def configure_billing(
    api_id: str,
    req: BillingConfigRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> BillingConfigResponse:
    """配置 API 的计费策略.

    三种计费方式：
        - by_call: 按次计费，costUnitPrice 为元/次
        - by_bytes: 按量计费，costUnitPrice 为元/KB
        - monthly_package: 订阅计费，costUnitPrice 为元/月，需指定 monthlyQuota
    """
    try:
        # 校验计费策略
        try:
            strategy = CostStrategy(req.costStrategy)
        except ValueError:
            raise HTTPException(
                status_code=400,
                detail=f"不支持的计费策略: {req.costStrategy}",
            )

        # 月包策略必须指定 monthlyQuota
        if strategy == CostStrategy.MONTHLY_PACKAGE and req.monthlyQuota is None:
            raise HTTPException(
                status_code=400,
                detail="月包计费策略必须指定 monthlyQuota",
            )

        # 获取并更新 API
        api = await registry.apiRegistryService.get_api(api_id)
        api.costStrategy = strategy
        api.costUnitPrice = req.costUnitPrice
        if req.monthlyQuota is not None:
            api.monthlyQuota = req.monthlyQuota
        api.updatedAt = datetime.now()
        await registry.store.save_api(api)

        strategy_desc = {
            CostStrategy.BY_CALL: f"按次计费 {req.costUnitPrice} 元/次",
            CostStrategy.BY_BYTES: f"按量计费 {req.costUnitPrice} 元/KB",
            CostStrategy.MONTHLY_PACKAGE: (
                f"订阅计费 {req.costUnitPrice} 元/月，"
                f"配额 {req.monthlyQuota or 0} 次"
            ),
        }[strategy]

        return BillingConfigResponse(
            apiId=api_id,
            costStrategy=strategy.value,
            costUnitPrice=req.costUnitPrice,
            monthlyQuota=req.monthlyQuota,
            description=strategy_desc,
            updatedAt=api.updatedAt,
        )
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@api_billing_router.get(
    "/{api_id}/billing",
    response_model=BillingConfigResponse,
    summary="查询 API 计费策略",
)
async def get_billing(
    api_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> BillingConfigResponse:
    """查询 API 的计费策略."""
    try:
        api = await registry.apiRegistryService.get_api(api_id)

        strategy_desc = {
            CostStrategy.BY_CALL: f"按次计费 {api.costUnitPrice} 元/次",
            CostStrategy.BY_BYTES: f"按量计费 {api.costUnitPrice} 元/KB",
            CostStrategy.MONTHLY_PACKAGE: (
                f"订阅计费 {api.costUnitPrice} 元/月，"
                f"配额 {api.monthlyQuota or 0} 次"
            ),
        }[api.costStrategy]

        return BillingConfigResponse(
            apiId=api_id,
            costStrategy=api.costStrategy.value,
            costUnitPrice=api.costUnitPrice,
            monthlyQuota=api.monthlyQuota,
            description=strategy_desc,
            updatedAt=api.updatedAt,
        )
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))