"""开放 API 服务目录 仓储层异常定义."""

from __future__ import annotations


class CatalogError(Exception):
    """开放 API 服务目录基础异常."""


class APINotFoundError(CatalogError):
    """API 不存在."""

    def __init__(self, api_id: str):
        self.apiId = api_id
        super().__init__(f"API 不存在: {api_id}")


class APIAlreadyExistsError(CatalogError):
    """API 已存在（同 name+version）."""

    def __init__(self, name: str, version: str):
        self.name = name
        self.version = version
        super().__init__(f"API 已存在: {name}@{version}")


class APIStatusTransitionError(CatalogError):
    """API 状态转换非法."""

    def __init__(self, api_id: str, current: str, target: str):
        self.apiId = api_id
        self.current = current
        self.target = target
        super().__init__(f"API 状态转换非法: {api_id} {current} -> {target}")


class SubscriptionNotFoundError(CatalogError):
    """订阅不存在."""

    def __init__(self, subscription_id: str):
        self.subscriptionId = subscription_id
        super().__init__(f"订阅不存在: {subscription_id}")


class SubscriptionAlreadyExistsError(CatalogError):
    """订阅已存在（同 apiId+subscriberId）."""

    def __init__(self, api_id: str, subscriber_id: str):
        self.apiId = api_id
        self.subscriberId = subscriber_id
        super().__init__(f"订阅已存在: api={api_id} subscriber={subscriber_id}")


class SubscriptionStatusError(CatalogError):
    """订阅状态非法."""

    def __init__(self, subscription_id: str, status: str):
        self.subscriptionId = subscription_id
        self.status = status
        super().__init__(f"订阅状态非法: {subscription_id} 当前 {status}")


class AuthError(CatalogError):
    """鉴权失败."""

    def __init__(self, reason: str = "鉴权失败"):
        self.reason = reason
        super().__init__(reason)


class InvalidAPIKeyError(AuthError):
    """API Key 无效."""

    def __init__(self):
        super().__init__("API Key 无效或已吊销")


class RateLimitExceededError(CatalogError):
    """限流触发."""

    def __init__(self, api_id: str, limit: int):
        self.apiId = api_id
        self.limit = limit
        super().__init__(f"限流触发: api={api_id} limit={limit}/s")


class QuotaExceededError(CatalogError):
    """配额超限."""

    def __init__(self, subscription_id: str, quota: int):
        self.subscriptionId = subscription_id
        self.quota = quota
        super().__init__(f"配额超限: subscription={subscription_id} quota={quota}/min")


class ValidationError(CatalogError):
    """业务校验失败."""


class StoreUnavailableError(CatalogError):
    """存储后端不可用."""
