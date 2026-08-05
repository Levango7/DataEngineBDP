"""关系抽取服务."""
from __future__ import annotations

from knowledge_engine.interfaces.relation_extractor import RelationExtractor
from knowledge_engine.models.entity import Entity
from knowledge_engine.models.relation import Relation


class RelationService:
    """关系抽取服务."""

    def __init__(self, extractor: RelationExtractor) -> None:
        self.extractor = extractor

    async def extract(
        self, text: str, entities: list[Entity]
    ) -> list[Relation]:
        """在实体集合上抽取关系."""
        return await self.extractor.extract(text, entities)