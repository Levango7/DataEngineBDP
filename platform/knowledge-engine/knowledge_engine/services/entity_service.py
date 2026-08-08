"""实体抽取服务."""

from __future__ import annotations

from knowledge_engine.interfaces.entity_extractor import EntityExtractor
from knowledge_engine.models.entity import Entity


class EntityService:
    """实体抽取服务（薄封装，便于上层注入与测试）."""

    def __init__(self, extractor: EntityExtractor) -> None:
        self.extractor = extractor

    async def extract(self, text: str, entity_types: list[str] | None = None) -> list[Entity]:
        """从文本抽取实体."""
        return await self.extractor.extract(text, entity_types)
