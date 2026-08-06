"""服务注册表 - 根据配置构建 Mock / SQLite 实现并注入服务层.

设计模式：依赖注入 + 工厂。
配置开关：ASSET_EXCHANGE_STORE_TYPE=mock | sqlite
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from asset_exchange.config.settings import Settings, get_settings
from asset_exchange.interfaces.asset_repository import AssetRepository
from asset_exchange.interfaces.billing_repository import BillingRepository
from asset_exchange.interfaces.delivery_repository import DeliveryRepository
from asset_exchange.interfaces.subscription_repository import (
    SubscriptionRepository,
)
from asset_exchange.services.asset_service import AssetService
from asset_exchange.services.billing_service import BillingService
from asset_exchange.services.delivery_service import DeliveryService
from asset_exchange.services.subscription_service import SubscriptionService


@dataclass
class ServiceRegistry:
    """服务注册表，聚合所有仓储与服务."""

    settings: Settings
    assetRepo: AssetRepository
    subRepo: SubscriptionRepository
    deliveryRepo: DeliveryRepository
    billingRepo: BillingRepository
    assetService: AssetService
    subscriptionService: SubscriptionService
    deliveryService: DeliveryService
    billingService: BillingService


def build_services(settings: Optional[Settings] = None) -> ServiceRegistry:
    """根据配置构建服务注册表.

    Args:
        settings: 配置，不传则使用全局单例。

    Returns:
        ServiceRegistry 实例。
    """
    if settings is None:
        settings = get_settings()

    if settings.isMock:
        asset_repo, sub_repo, delivery_repo, billing_repo = _build_mock()
    elif settings.isSQLite:
        asset_repo, sub_repo, delivery_repo, billing_repo = _build_sqlite(
            settings.dbPath
        )
    else:
        # 兜底：未知类型回退 Mock
        asset_repo, sub_repo, delivery_repo, billing_repo = _build_mock()

    asset_service = AssetService(asset_repo, sub_repo)
    subscription_service = SubscriptionService(sub_repo, asset_service)
    delivery_service = DeliveryService(delivery_repo, sub_repo)
    billing_service = BillingService(
        billing_repo,
        asset_service,
        sub_repo,
        provider_share=settings.providerShare,
        platform_share=settings.platformShare,
        internal_factor=settings.internalFactor,
    )

    return ServiceRegistry(
        settings=settings,
        assetRepo=asset_repo,
        subRepo=sub_repo,
        deliveryRepo=delivery_repo,
        billingRepo=billing_repo,
        assetService=asset_service,
        subscriptionService=subscription_service,
        deliveryService=delivery_service,
        billingService=billing_service,
    )


def _build_mock() -> tuple[
    AssetRepository,
    SubscriptionRepository,
    DeliveryRepository,
    BillingRepository,
]:
    from asset_exchange.repositories.mock import (
        MockAssetRepository,
        MockBillingRepository,
        MockDeliveryRepository,
        MockSubscriptionRepository,
    )

    return (
        MockAssetRepository(),
        MockSubscriptionRepository(),
        MockDeliveryRepository(),
        MockBillingRepository(),
    )


def _build_sqlite(
    db_path: str,
) -> tuple[
    AssetRepository,
    SubscriptionRepository,
    DeliveryRepository,
    BillingRepository,
]:
    from asset_exchange.repositories.sqlite import (
        SQLiteAssetRepository,
        SQLiteBillingRepository,
        SQLiteConnection,
        SQLiteDeliveryRepository,
        SQLiteSubscriptionRepository,
    )

    conn = SQLiteConnection(db_path)
    conn.init_schema()
    return (
        SQLiteAssetRepository(conn),
        SQLiteSubscriptionRepository(conn),
        SQLiteDeliveryRepository(conn),
        SQLiteBillingRepository(conn),
    )