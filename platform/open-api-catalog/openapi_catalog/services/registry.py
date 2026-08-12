"""服务注册表 - 聚合所有服务并注入依赖.

设计模式：依赖注入 + 工厂。
配置开关：OPENAPI_CATALOG_STORE_TYPE=mock | sqlite
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from openapi_catalog.config.settings import Settings, get_settings
from openapi_catalog.services.api_call import APICallService
from openapi_catalog.services.api_registry import APIRegistryService
from openapi_catalog.services.apisix_config import APISIXConfigService
from openapi_catalog.services.doc_generator import DocGeneratorService
from openapi_catalog.services.metering import MeteringService
from openapi_catalog.services.rate_limiter import RateLimiter
from openapi_catalog.services.subscription import SubscriptionService


@dataclass
class ServiceRegistry:
    """服务注册表，聚合所有仓储与服务."""

    settings: Settings
    store: Any  # MockCatalogStore | SQLiteCatalogStore
    rateLimiter: RateLimiter
    apiRegistryService: APIRegistryService
    subscriptionService: SubscriptionService
    meteringService: MeteringService
    apisixConfigService: APISIXConfigService
    docGeneratorService: DocGeneratorService
    apiCallService: APICallService


def build_services(settings: Optional[Settings] = None) -> ServiceRegistry:
    """根据配置构建服务注册表.

    Args:
        settings: 配置，不传则使用全局单例。

    Returns:
        ServiceRegistry 实例。
    """
    if settings is None:
        settings = get_settings()

    if settings.isSQLite:
        store = _build_sqlite_store(settings.dbPath)
    else:
        store = _build_mock_store()

    rate_limiter = RateLimiter()

    api_registry_service = APIRegistryService(store)
    subscription_service = SubscriptionService(store)
    metering_service = MeteringService(store)
    apisix_config_service = APISIXConfigService(store, settings)
    doc_generator_service = DocGeneratorService(store)
    api_call_service = APICallService(store, subscription_service, rate_limiter, metering_service)

    return ServiceRegistry(
        settings=settings,
        store=store,
        rateLimiter=rate_limiter,
        apiRegistryService=api_registry_service,
        subscriptionService=subscription_service,
        meteringService=metering_service,
        apisixConfigService=apisix_config_service,
        docGeneratorService=doc_generator_service,
        apiCallService=api_call_service,
    )


def _build_mock_store():
    from openapi_catalog.repositories.mock import MockCatalogStore

    return MockCatalogStore()


def _build_sqlite_store(db_path: str):
    from openapi_catalog.repositories.sqlite import (
        SQLiteCatalogStore,
        SQLiteConnection,
    )

    conn = SQLiteConnection(db_path)
    conn.init_schema()
    return SQLiteCatalogStore(conn)
