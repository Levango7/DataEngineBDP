"""pytest 共享 fixtures."""
from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient

# 强制 Mock 模式
os.environ.setdefault("ASSET_EXCHANGE_STORE_TYPE", "mock")

from asset_exchange.api.app import create_app
from asset_exchange.config.settings import Settings, reset_settings
from asset_exchange.repositories.mock import (
    MockAssetRepository,
    MockBillingRepository,
    MockDeliveryRepository,
    MockSubscriptionRepository,
)
from asset_exchange.services.asset_service import AssetService
from asset_exchange.services.billing_service import BillingService
from asset_exchange.services.delivery_service import DeliveryService
from asset_exchange.services.registry import ServiceRegistry
from asset_exchange.services.subscription_service import SubscriptionService


@pytest.fixture
def mock_asset_repo() -> MockAssetRepository:
    return MockAssetRepository()


@pytest.fixture
def mock_sub_repo() -> MockSubscriptionRepository:
    return MockSubscriptionRepository()


@pytest.fixture
def mock_delivery_repo() -> MockDeliveryRepository:
    return MockDeliveryRepository()


@pytest.fixture
def mock_billing_repo() -> MockBillingRepository:
    return MockBillingRepository()


@pytest.fixture
def settings() -> Settings:
    reset_settings()
    return Settings(storeType="mock")


@pytest.fixture
def registry(
    mock_asset_repo: MockAssetRepository,
    mock_sub_repo: MockSubscriptionRepository,
    mock_delivery_repo: MockDeliveryRepository,
    mock_billing_repo: MockBillingRepository,
    settings: Settings,
) -> ServiceRegistry:
    """构建使用独立 Mock 实例的 registry（每个测试隔离）."""
    asset_service = AssetService(mock_asset_repo, mock_sub_repo)
    subscription_service = SubscriptionService(mock_sub_repo, asset_service)
    delivery_service = DeliveryService(mock_delivery_repo, mock_sub_repo)
    billing_service = BillingService(
        mock_billing_repo,
        asset_service,
        mock_sub_repo,
        provider_share=settings.providerShare,
        platform_share=settings.platformShare,
        internal_factor=settings.internalFactor,
    )
    return ServiceRegistry(
        settings=settings,
        assetRepo=mock_asset_repo,
        subRepo=mock_sub_repo,
        deliveryRepo=mock_delivery_repo,
        billingRepo=mock_billing_repo,
        assetService=asset_service,
        subscriptionService=subscription_service,
        deliveryService=delivery_service,
        billingService=billing_service,
    )


@pytest.fixture
def app(registry: ServiceRegistry):
    return create_app(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient（FastAPI 自动处理 async 路由）."""
    return TestClient(app)