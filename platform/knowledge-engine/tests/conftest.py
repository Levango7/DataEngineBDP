"""pytest 共享 fixtures."""

from __future__ import annotations

import os

from fastapi.testclient import TestClient
import pytest

# 强制 Mock 模式
os.environ.setdefault("KE_STORE_TYPE", "mock")
os.environ.setdefault("KE_EXTRACTOR_TYPE", "mock")

from knowledge_engine.api.app import create_app  # noqa: E402
from knowledge_engine.config.settings import Settings, reset_settings  # noqa: E402
from knowledge_engine.repositories.mock import (  # noqa: E402
    MockEntityExtractor,
    MockGraphStore,
    MockRelationExtractor,
)
from knowledge_engine.services.entity_service import EntityService  # noqa: E402
from knowledge_engine.services.knowledge_service import KnowledgeService  # noqa: E402
from knowledge_engine.services.query_service import QueryService  # noqa: E402
from knowledge_engine.services.registry import ServiceRegistry  # noqa: E402
from knowledge_engine.services.relation_service import RelationService  # noqa: E402


@pytest.fixture
def mock_store() -> MockGraphStore:
    return MockGraphStore()


@pytest.fixture
def mock_entity_extractor() -> MockEntityExtractor:
    return MockEntityExtractor()


@pytest.fixture
def mock_relation_extractor() -> MockRelationExtractor:
    return MockRelationExtractor()


@pytest.fixture
def settings() -> Settings:
    reset_settings()
    return Settings(storeType="mock", extractorType="mock")


@pytest.fixture
def registry(
    mock_store: MockGraphStore,
    mock_entity_extractor: MockEntityExtractor,
    mock_relation_extractor: MockRelationExtractor,
    settings: Settings,
) -> ServiceRegistry:
    """构建使用独立 Mock 实例的 registry（每个测试隔离）."""
    knowledge_service = KnowledgeService(
        store=mock_store,
        entity_extractor=mock_entity_extractor,
        relation_extractor=mock_relation_extractor,
    )
    return ServiceRegistry(
        settings=settings,
        store=mock_store,
        entityExtractor=mock_entity_extractor,
        relationExtractor=mock_relation_extractor,
        knowledgeService=knowledge_service,
        entityService=EntityService(mock_entity_extractor),
        relationService=RelationService(mock_relation_extractor),
        queryService=QueryService(mock_store),
    )


@pytest.fixture
def app(registry: ServiceRegistry):
    return create_app(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient（FastAPI 自动处理 async 路由）."""
    return TestClient(app)
