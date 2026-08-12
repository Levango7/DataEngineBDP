"""通用依赖与错误处理."""

from __future__ import annotations

from fastapi import Request

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
