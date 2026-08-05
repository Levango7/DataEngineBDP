"""pytest 共享 fixtures."""
from __future__ import annotations

import os
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from fastapi.testclient import TestClient

# 强制 Mock 模式
os.environ.setdefault("OPENAPI_CATALOG_STORE_TYPE", "mock")

from openapi_catalog.api.app import create_app
from openapi_catalog.config.settings import Settings, reset_settings
from openapi_catalog.repositories.mock import MockCatalogStore
from openapi_catalog.services.api_call import APICallService
from openapi_catalog.services.api_registry import APIRegistryService
from openapi_catalog.services.apisix_config import APISIXConfigService
from openapi_catalog.services.doc_generator import DocGeneratorService
from openapi_catalog.services.metering import MeteringService
from openapi_catalog.services.rate_limiter import RateLimiter
from openapi_catalog.services.registry import ServiceRegistry
from openapi_catalog.services.subscription import SubscriptionService


@pytest.fixture
def mock_store() -> MockCatalogStore:
    return MockCatalogStore()


@pytest.fixture
def rate_limiter() -> RateLimiter:
    return RateLimiter()


@pytest.fixture
def settings() -> Settings:
    reset_settings()
    return Settings(storeType="mock")


@pytest.fixture
def registry(
    mock_store: MockCatalogStore,
    rate_limiter: RateLimiter,
    settings: Settings,
) -> ServiceRegistry:
    """构建使用独立 Mock 实例的 registry（每个测试隔离）."""
    api_registry_service = APIRegistryService(mock_store)
    subscription_service = SubscriptionService(mock_store)
    metering_service = MeteringService(mock_store)
    apisix_config_service = APISIXConfigService(mock_store, settings)
    doc_generator_service = DocGeneratorService(mock_store)
    api_call_service = APICallService(
        mock_store, subscription_service, rate_limiter, metering_service
    )
    return ServiceRegistry(
        settings=settings,
        store=mock_store,
        rateLimiter=rate_limiter,
        apiRegistryService=api_registry_service,
        subscriptionService=subscription_service,
        meteringService=metering_service,
        apisixConfigService=apisix_config_service,
        docGeneratorService=doc_generator_service,
        apiCallService=api_call_service,
    )


@pytest.fixture
def app(registry: ServiceRegistry):
    return create_app(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient（FastAPI 自动处理 async 路由）."""
    return TestClient(app)


# ---------- 测试辅助工厂 ----------

@pytest.fixture
def make_api_def():
    """构造 APIDefinition 的工厂."""
    from openapi_catalog.models import (
        APIDefinition,
        APIParam,
        APIResponse,
        APIUpstream,
        AuthType,
        CostStrategy,
        HttpMethod,
        ParamLocation,
        ParamType,
        SLALevel,
    )

    def _make(
        name: str = "test-api",
        version: str = "1.0.0",
        method: HttpMethod = HttpMethod.GET,
        path: str = "/test",
        provider_tenant_id: str = "tenant-provider",
        upstream_type: str = "trino",
        upstream_url: str = "http://trino:8080/v1/statement",
    ) -> APIDefinition:
        return APIDefinition(
            id="",
            name=name,
            version=version,
            description="测试 API",
            category="test",
            tags=["test"],
            method=method,
            path=path,
            params=[
                APIParam(
                    name="limit",
                    location=ParamLocation.QUERY,
                    type=ParamType.INTEGER,
                    required=False,
                    description="限制条数",
                    default=100,
                )
            ],
            responses=[
                APIResponse(
                    statusCode=200,
                    description="成功",
                    schema={"type": "object"},
                    example={"code": 0, "data": []},
                )
            ],
            authType=AuthType.API_KEY,
            upstream=APIUpstream(
                type=upstream_type,
                url=upstream_url,
                method=HttpMethod.GET,
                timeout=30000,
                retries=2,
            ),
            sla=SLALevel.GOLD,
            costStrategy=CostStrategy.BY_CALL,
            costUnitPrice=0.01,
            providerTenantId=provider_tenant_id,
        )

    return _make


@pytest.fixture
def publish_api():
    """发布 API 的 helper（走完审核流程）."""
    async def _publish(registry, api_id):
        await registry.apiRegistryService.submit_for_review(api_id)
        await registry.apiRegistryService.approve(api_id)
        return await registry.apiRegistryService.publish(api_id)
    return _publish