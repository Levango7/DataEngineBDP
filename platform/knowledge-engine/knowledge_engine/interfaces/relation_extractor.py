"""关系抽取抽象接口."""
from __future__ import annotations

from abc import ABC, abstractmethod

from knowledge_engine.models.entity import Entity
from knowledge_engine.models.relation import Relation


class RelationExtractor(ABC):
    """关系抽取抽象接口.

    职责：在已识别实体集合上抽取实体间关系。
    实现：
        - MockRelationExtractor: 基于规则匹配（测试与无 LLM 场景）
        - LLMRelationExtractor: 调用大模型网关进行关系抽取
    """

    @abstractmethod
    async def extract(
        self, text: str, entities: list[Entity]
    ) -> list[Relation]:
        """在实体集合上抽取关系.

        Args:
            text: 原文（关系线索来自原文上下文）。
            entities: 已识别的实体列表。

        Returns:
            关系列表。
        """
        ...