"""通用依赖与错误处理."""
from __future__ import annotations

from fastapi import Request

from openapi_catalog.repositories import (
    APIAlreadyExistsError,
    APINotFoundError,
    APIStatusTransitionError,
    AuthError,
    CatalogError,
    InvalidAPIKeyError,
    QuotaExceededError,
    RateLimitExceededError,
    SubscriptionAlreadyExistsError,
    SubscriptionNotFoundError,
    SubscriptionStatusError,
    ValidationError,
)
from openapi_catalog.services.registry import ServiceRegistry


def get_registry(request: Request) -> ServiceRegistry:
    """从 app.state 获取服务注册表."""
    return request.app.state.registry


# HTTP 状态码映射
_ERROR_STATUS: dict[type[CatalogError], int] = {
    APINotFoundError: 404,
    SubscriptionNotFoundError: 404,
    APIAlreadyExistsError: 409,
    SubscriptionAlreadyExistsError: 409,
    APIStatusTransitionError: 409,
    SubscriptionStatusError: 409,
    InvalidAPIKeyError: 401,
    AuthError: 401,
    RateLimitExceededError: 429,
    QuotaExceededError: 429,
    ValidationError: 422,
}


def status_for_error(exc: CatalogError) -> int:
    """根据异常类型返回 HTTP 状态码."""
    return _ERROR_STATUS.get(type(exc), 400)