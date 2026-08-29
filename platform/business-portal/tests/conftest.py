"""pytest 共享 fixtures."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time

from fastapi.testclient import TestClient
import pytest

# 强制 Mock 模式
os.environ.setdefault("BP_STORE_TYPE", "mock")
# 测试环境显式匿名放行（生产/K8s 由 jwt_auth fail-fast 拦截）
os.environ.setdefault("AUTH_MODE", "none")

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


# ---------- JWT 签发辅助（对齐 jwt_auth.py HS256 格式） ----------

TEST_JWT_SECRET = "unit-test-secret-key-at-least-32-bytes!!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def make_jwt(
    secret: str = TEST_JWT_SECRET,
    sub: str = "admin-1",
    tenant: str = "t-1",
    role: str = "admin",
    exp: float | None = None,
) -> str:
    """签发 HS256 JWT（claims 与 Go 侧 / jwt_auth.py 兼容）."""
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "iss": "shuqing-bigdata",
        "sub": sub,
        "tenantId": tenant,
        "role": role,
        "iat": int(time.time()),
        "exp": exp if exp is not None else int(time.time()) + 600,
    }
    si = f"{_enc(header)}.{_enc(claims)}"
    sig = (
        base64.urlsafe_b64encode(hmac.new(secret.encode(), si.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    )
    return f"{si}.{sig}"


@pytest.fixture
def jwt_client(app, monkeypatch) -> TestClient:
    """AUTH_MODE=jwt 的客户端（身份强制来自 Bearer token）."""
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    return TestClient(app)
