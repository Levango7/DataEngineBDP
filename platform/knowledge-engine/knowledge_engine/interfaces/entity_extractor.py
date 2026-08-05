"""实体抽取抽象接口."""
from __future__ import annotations

from abc import ABC, abstractmethod

from knowledge_engine.models.entity import Entity


class EntityExtractor(ABC):
    """实体抽取抽象接口.

    职责：从文本中识别命名实体。
    实现：
        - MockEntityExtractor: 基于规则匹配（测试与无 LLM 场景）
        - LLMEntityExtractor: 调用大模型网关进行 NER
    """

    @abstractmethod
    async def extract(
        self, text: str, entity_types: list[str] | None = None
    ) -> list[Entity]:
        """从文本中抽取实体.

        Args:
            text: 输入文本。
            entity_types: 限定实体类型；None 表示抽取所有支持的类型。

        Returns:
            实体列表。
        """
        ...