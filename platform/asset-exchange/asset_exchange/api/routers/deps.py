"""通用依赖与错误处理."""

from __future__ import annotations

from fastapi import HTTPException, Request

from asset_exchange.api.jwt_auth import AuthContext
from asset_exchange.models.asset import Asset
from asset_exchange.repositories import (
    AssetAlreadyExistsError,
    AssetExchangeError,
    AssetNotFoundError,
    AssetNotListedError,
    DeliveryFailedError,
    DeliveryNotFoundError,
    InvalidAssetStateError,
    NoActiveSubscriptionError,
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
        HTTPException: 403 租户越权；401 拿不到任何租户上下文。
    """
    if requestedTenantId:
        if ctx.role == "admin" or requestedTenantId == ctx.tenantId:
            return requestedTenantId
        raise HTTPException(status_code=403, detail=f"tenantId {requestedTenantId} 与当前身份不一致")
    if not ctx.tenantId:
        # AUTH_MODE=none 下 claim 是空串，而 Asset.tenantId 有 min_length=1：
        # 以前直接把 '' 交给模型构造，pydantic 抛 ValidationError 被全局兜到 500
        # 且回显内部校验文本；这里按 settlements.py 既有口径 fail-closed 成 401。
        raise HTTPException(status_code=401, detail="缺少租户上下文")
    return ctx.tenantId


async def require_asset_owner(
    registry: ServiceRegistry,
    asset_id: str,
    ctx: AuthContext,
) -> Asset:
    """对象级授权：校验 ctx 对 asset_id 有操作权限.

    admin 或资产所属租户可操作；其他返回 403。
    返回资产记录供后续使用（避免重复查询）。

    Raises:
        HTTPException: 404 资产不存在；403 无权操作。
    """
    try:
        asset = await registry.assetService.get_asset(asset_id)
    except AssetExchangeError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
    if ctx.role != "admin" and ctx.tenantId != asset.tenantId:
        raise HTTPException(status_code=403, detail="无权操作此资产")
    return asset


# HTTP 状态码映射
_ERROR_STATUS: dict[type[AssetExchangeError], int] = {
    AssetNotFoundError: 404,
    SubscriptionNotFoundError: 404,
    DeliveryNotFoundError: 404,
    AssetAlreadyExistsError: 409,
    AssetNotListedError: 409,
    InvalidAssetStateError: 409,
    SubscriptionNotApprovableError: 409,
    SubscriptionNotDeliverableError: 409,
    DeliveryFailedError: 409,
    NoActiveSubscriptionError: 403,
    ValidationError: 422,
}


def status_for_error(exc: AssetExchangeError) -> int:
    """根据异常类型返回 HTTP 状态码."""
    return _ERROR_STATUS.get(type(exc), 400)
