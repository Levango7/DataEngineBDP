"""通用依赖与错误处理."""

from __future__ import annotations

from fastapi import HTTPException, Request

from asset_exchange.api.jwt_auth import AuthContext
from asset_exchange.repositories import (
    AssetAlreadyExistsError,
    AssetExchangeError,
    AssetNotFoundError,
    AssetNotListedError,
    DeliveryFailedError,
    DeliveryNotFoundError,
    SubscriptionNotApprovableError,
    SubscriptionNotDeliverableError,
    SubscriptionNotFoundError,
    ValidationError,
)
from asset_exchange.services.registry import ServiceRegistry


def get_registry(request: Request) -> ServiceRegistry:
    """从 app.state 获取服务注册表."""
    return request.app.state.registry


def resolve_tenant(ctx: AuthContext, requestedTenantId: str | None) -> str:
    """租户来源裁决：一律以 JWT claim 为准.

    请求未提供时回填 claim；admin 角色可指定任意租户；
    普通用户提供的 tenantId 与 claim 不一致时拒绝（403）。

    Raises:
        HTTPException: 403 租户越权。
    """
    if requestedTenantId:
        if ctx.role == "admin" or requestedTenantId == ctx.tenantId:
            return requestedTenantId
        raise HTTPException(status_code=403, detail=f"tenantId {requestedTenantId} 与当前身份不一致")
    return ctx.tenantId


# HTTP 状态码映射
_ERROR_STATUS: dict[type[AssetExchangeError], int] = {
    AssetNotFoundError: 404,
    SubscriptionNotFoundError: 404,
    DeliveryNotFoundError: 404,
    AssetAlreadyExistsError: 409,
    AssetNotListedError: 409,
    SubscriptionNotApprovableError: 409,
    SubscriptionNotDeliverableError: 409,
    DeliveryFailedError: 409,
    ValidationError: 422,
}


def status_for_error(exc: AssetExchangeError) -> int:
    """根据异常类型返回 HTTP 状态码."""
    return _ERROR_STATUS.get(type(exc), 400)
