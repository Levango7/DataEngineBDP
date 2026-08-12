"""Mock 关系抽取器 - 基于规则匹配.

设计原则：
    - 不依赖外部模型，纯规则匹配。
    - 内置常见关系模式（located_in / works_for / founded_in / on_date）。
    - 关系两端实体类型约束：避免无意义组合。
"""

from __future__ import annotations

import re

from knowledge_engine.interfaces.relation_extractor import RelationExtractor
from knowledge_engine.models.entity import Entity
from knowledge_engine.models.relation import Relation

# 关系规则：(关系类型, 起点实体类型, 终点实体类型, 触发词正则)
# 触发词在原文出现即认为存在该关系（同句子/邻近简化为全文匹配）。
_RELATION_PATTERNS: list[tuple[str, str, str, re.Pattern[str]]] = [
    (
        "located_in",
        "Organization",
        "City",
        re.compile(r"位于|总部在|坐落于|在.*?有.*?分公司"),
    ),
    (
        "founded_in",
        "Organization",
        "Date",
        re.compile(r"成立于|创办于|建立于|创立于"),
    ),
    ("works_for", "Person", "Organization", re.compile(r"就职于|任职于|工作于")),
    ("on_date", "Organization", "Date", re.compile(r"于|在")),
]


class MockRelationExtractor(RelationExtractor):
    """基于规则的关系抽取器."""

    def __init__(
        self,
        patterns: list[tuple[str, str, str, re.Pattern[str]]] | None = None,
    ) -> None:
        self.patterns = patterns if patterns is not None else _RELATION_PATTERNS

    async def extract(self, text: str, entities: list[Entity]) -> list[Relation]:
        # 按类型索引实体
        by_type: dict[str, list[Entity]] = {}
        for e in entities:
            by_type.setdefault(e.type, []).append(e)

        relations: list[Relation] = []
        seen: set[tuple[str, str, str]] = set()
        for rel_type, src_type, dst_type, pattern in self.patterns:
            if pattern.search(text) is None:
                continue
            srcs = by_type.get(src_type, [])
            dsts = by_type.get(dst_type, [])
            for src in srcs:
                for dst in dsts:
                    if src.id == dst.id:
                        continue
                    key = (src.id, rel_type, dst.id)
                    if key in seen:
                        continue
                    seen.add(key)
                    relations.append(
                        Relation(
                            srcId=src.id,
                            dstId=dst.id,
                            type=rel_type,
                            properties={"source": "mock-rule"},
                            confidence=0.8,
                        )
                    )
        return relations
