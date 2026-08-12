"""pytest 共享 fixtures."""

from __future__ import annotations

import os

from fastapi.testclient import TestClient
import pytest

# 强制 Mock 模式
os.environ.setdefault("BP_STORE_TYPE", "mock")

from business_portal.api.app import create_app  # noqa: E402
from business_portal.config.settings import Settings, reset_settings  # noqa: E402
from business_portal.repositories.mock import (  # noqa: E402
    MockBusinessLineStore,
    MockCatalogStore,
    MockDashboardStore,
    MockReportStore,
    MockWorkbenchStore,
)
from business_portal.services.business_line_service import BusinessLineService  # noqa: E402
from business_portal.services.catalog_service import CatalogService  # noqa: E402
from business_portal.services.dashboard_service import DashboardService  # noqa: E402
from business_portal.services.registry import ServiceRegistry  # noqa: E402
from business_portal.services.report_service import ReportService  # noqa: E402
from business_portal.services.workbench_service import WorkbenchService  # noqa: E402


@pytest.fixture
def mock_bl_store() -> MockBusinessLineStore:
    return MockBusinessLineStore()


@pytest.fixture
def mock_dashboard_store(mock_bl_store: MockBusinessLineStore) -> MockDashboardStore:
    return MockDashboardStore(mock_bl_store)


@pytest.fixture
def mock_workbench_store() -> MockWorkbenchStore:
    return MockWorkbenchStore()


@pytest.fixture
def mock_catalog_store() -> MockCatalogStore:
    return MockCatalogStore()


@pytest.fixture
def mock_report_store() -> MockReportStore:
    return MockReportStore()


@pytest.fixture
def settings() -> Settings:
    reset_settings()
    return Settings(storeType="mock")


@pytest.fixture
def registry(
    mock_bl_store: MockBusinessLineStore,
    mock_dashboard_store: MockDashboardStore,
    mock_workbench_store: MockWorkbenchStore,
    mock_catalog_store: MockCatalogStore,
    mock_report_store: MockReportStore,
    settings: Settings,
) -> ServiceRegistry:
    """构建使用独立 Mock 实例的 registry（每个测试隔离）."""
    return ServiceRegistry(
        settings=settings,
        blStore=mock_bl_store,
        dashboardStore=mock_dashboard_store,
        workbenchStore=mock_workbench_store,
        catalogStore=mock_catalog_store,
        reportStore=mock_report_store,
        businessLineService=BusinessLineService(mock_bl_store),
        dashboardService=DashboardService(mock_bl_store, mock_dashboard_store),
        workbenchService=WorkbenchService(mock_bl_store, mock_workbench_store),
        catalogService=CatalogService(mock_bl_store, mock_catalog_store),
        reportService=ReportService(mock_bl_store, mock_report_store),
    )


@pytest.fixture
def app(registry: ServiceRegistry):
    return create_app(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient（FastAPI 自动处理 async 路由）."""
    return TestClient(app)
