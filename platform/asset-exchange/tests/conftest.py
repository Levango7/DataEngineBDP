"""pytest 共享 fixtures."""

from __future__ import annotations

import os

from fastapi.testclient import TestClient
import pytest

# 强制 Mock 模式
os.environ.setdefault("ASSET_EXCHANGE_STORE_TYPE", "mock")

from asset_exchange.api.app import create_app  # noqa: E402
from asset_exchange.config.settings import Settings, reset_settings  # noqa: E402
from asset_exchange.repositories.mock import (  # noqa: E402
    MockAllocationRepository,
    MockAssetRepository,
    MockAuditRepository,
    MockBillingRepository,
    MockDeliveryRepository,
    MockSettlementRepository,
    MockSubscriptionRepository,
)
from asset_exchange.services.allocation_service import AllocationService  # noqa: E402
from asset_exchange.services.asset_service import AssetService  # noqa: E402
from asset_exchange.services.audit_service import AuditService  # noqa: E402
from asset_exchange.services.billing_service import BillingService  # noqa: E402
from asset_exchange.services.delivery_service import DeliveryService  # noqa: E402
from asset_exchange.services.registry import ServiceRegistry  # noqa: E402
from asset_exchange.services.settlement_service import SettlementService  # noqa: E402
from asset_exchange.services.subscription_service import SubscriptionService  # noqa: E402


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
def mock_audit_repo() -> MockAuditRepository:
    return MockAuditRepository()


@pytest.fixture
def mock_settlement_repo() -> MockSettlementRepository:
    return MockSettlementRepository()


@pytest.fixture
def mock_allocation_repo() -> MockAllocationRepository:
    return MockAllocationRepository()


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
    mock_audit_repo: MockAuditRepository,
    mock_settlement_repo: MockSettlementRepository,
    mock_allocation_repo: MockAllocationRepository,
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
    audit_service = AuditService(
        mock_audit_repo,
        audit_facade_url=settings.auditFacadeUrl,
    )
    settlement_service = SettlementService(
        mock_settlement_repo,
        mock_billing_repo,
        asset_service,
        provider_share=settings.providerShare,
        platform_share=settings.platformShare,
    )
    allocation_service = AllocationService(
        mock_allocation_repo,
        mock_settlement_repo,
        platform_account_id=settings.platformAccountId,
    )
    return ServiceRegistry(
        settings=settings,
        assetRepo=mock_asset_repo,
        subRepo=mock_sub_repo,
        deliveryRepo=mock_delivery_repo,
        billingRepo=mock_billing_repo,
        auditRepo=mock_audit_repo,
        settlementRepo=mock_settlement_repo,
        allocationRepo=mock_allocation_repo,
        assetService=asset_service,
        subscriptionService=subscription_service,
        deliveryService=delivery_service,
        billingService=billing_service,
        auditService=audit_service,
        settlementService=settlement_service,
        allocationService=allocation_service,
    )


@pytest.fixture
def app(registry: ServiceRegistry):
    return create_app(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient（FastAPI 自动处理 async 路由）."""
    return TestClient(app)
