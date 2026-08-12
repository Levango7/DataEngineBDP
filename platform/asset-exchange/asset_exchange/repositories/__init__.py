"""Asset Exchange 仓储层异常定义."""

from __future__ import annotations


class AssetExchangeError(Exception):
    """Asset Exchange 基础异常."""


class AssetNotFoundError(AssetExchangeError):
    """资产不存在."""

    def __init__(self, asset_id: str):
        self.assetId = asset_id
        super().__init__(f"资产不存在: {asset_id}")


class AssetAlreadyExistsError(AssetExchangeError):
    """资产已存在（同名）."""

    def __init__(self, name: str):
        self.name = name
        super().__init__(f"资产已存在: {name}")


class AssetNotListedError(AssetExchangeError):
    """资产未上架，不可订阅."""

    def __init__(self, asset_id: str, status: str):
        self.assetId = asset_id
        self.status = status
        super().__init__(f"资产当前状态 {status} 不可订阅: {asset_id}")


class SubscriptionNotFoundError(AssetExchangeError):
    """订阅不存在."""

    def __init__(self, subscription_id: str):
        self.subscriptionId = subscription_id
        super().__init__(f"订阅不存在: {subscription_id}")


class SubscriptionNotApprovableError(AssetExchangeError):
    """订阅不可审批（非待审批状态）."""

    def __init__(self, subscription_id: str, status: str):
        self.subscriptionId = subscription_id
        self.status = status
        super().__init__(f"订阅不可审批（当前状态 {status}）: {subscription_id}")


class SubscriptionNotDeliverableError(AssetExchangeError):
    """订阅不可交付（非已批准/生效状态）."""

    def __init__(self, subscription_id: str, status: str):
        self.subscriptionId = subscription_id
        self.status = status
        super().__init__(f"订阅不可交付（当前状态 {status}）: {subscription_id}")


class DeliveryNotFoundError(AssetExchangeError):
    """交付不存在."""

    def __init__(self, delivery_id: str):
        self.deliveryId = delivery_id
        super().__init__(f"交付不存在: {delivery_id}")


class DeliveryFailedError(AssetExchangeError):
    """交付失败."""

    def __init__(self, delivery_id: str, reason: str):
        self.deliveryId = delivery_id
        self.reason = reason
        super().__init__(f"交付失败: {delivery_id} - {reason}")


class ValidationError(AssetExchangeError):
    """业务校验失败."""


class StoreUnavailableError(AssetExchangeError):
    """存储后端不可用."""
