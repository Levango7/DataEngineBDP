"""服务注册表 - 根据配置构建 Mock / Nebula / LLM 实现并注入服务层.

设计模式：依赖注入 + 工厂。
配置开关：
    KE_STORE_TYPE=mock / nebula
    KE_EXTRACTOR_TYPE=mock / llm
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from knowledge_engine.config.settings import Settings, get_settings
from knowledge_engine.interfaces.entity_extractor import EntityExtractor
from knowledge_engine.interfaces.graph_store import GraphStore
from knowledge_engine.interfaces.relation_extractor import RelationExtractor
from knowledge_engine.services.entity_service import EntityService
from knowledge_engine.services.knowledge_service import KnowledgeService
from knowledge_engine.services.query_service import QueryService
from knowledge_engine.services.relation_service import RelationService


@dataclass
class ServiceRegistry:
    """服务注册表，聚合所有仓储与服务."""

    settings: Settings
    store: GraphStore
    entityExtractor: EntityExtractor
    relationExtractor: RelationExtractor
    knowledgeService: KnowledgeService
    entityService: EntityService
    relationService: RelationService
    queryService: QueryService


def build_services(settings: Optional[Settings] = None) -> ServiceRegistry:
    """根据配置构建服务注册表.

    Args:
        settings: 配置，不传则使用全局单例。

    Returns:
        ServiceRegistry 实例。
    """
    if settings is None:
        settings = get_settings()

    store = _build_store(settings)
    entity_extractor, relation_extractor = _build_extractors(settings)

    knowledge_service = KnowledgeService(
        store=store,
        entity_extractor=entity_extractor,
        relation_extractor=relation_extractor,
    )
    entity_service = EntityService(entity_extractor)
    relation_service = RelationService(relation_extractor)
    query_service = QueryService(store)

    return ServiceRegistry(
        settings=settings,
        store=store,
        entityExtractor=entity_extractor,
        relationExtractor=relation_extractor,
        knowledgeService=knowledge_service,
        entityService=entity_service,
        relationService=relation_service,
        queryService=query_service,
    )


def _build_store(settings: Settings) -> GraphStore:
    if settings.isMockStore:
        from knowledge_engine.repositories.mock import MockGraphStore

        return MockGraphStore()
    if settings.isNebulaStore:
        from knowledge_engine.repositories.nebula import NebulaGraphStore

        return NebulaGraphStore(
            host=settings.nebulaHost,
            port=settings.nebulaPort,
            user=settings.nebulaUser,
            password=settings.nebulaPassword,
            pool_size=settings.nebulaPoolSize,
        )
    # 不应到达
    raise ValueError(f"未知 storeType: {settings.storeType}")


def _build_extractors(
    settings: Settings,
) -> tuple[EntityExtractor, RelationExtractor]:
    if settings.isMockExtractor:
        from knowledge_engine.repositories.mock import (
            MockEntityExtractor,
            MockRelationExtractor,
        )

        return MockEntityExtractor(), MockRelationExtractor()
    if settings.isLlmExtractor:
        from knowledge_engine.repositories.llm import (
            LLMEntityExtractor,
            LLMRelationExtractor,
        )

        return (
            LLMEntityExtractor(
                gateway_url=settings.llmGatewayUrl,
                model=settings.llmModel,
                api_key=settings.llmApiKey or None,
                timeout=settings.llmTimeout,
            ),
            LLMRelationExtractor(
                gateway_url=settings.llmGatewayUrl,
                model=settings.llmModel,
                api_key=settings.llmApiKey or None,
                timeout=settings.llmTimeout,
            ),
        )
    raise ValueError(f"未知 extractorType: {settings.extractorType}")