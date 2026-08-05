"""知识工程引擎接口抽象层.

对外暴露三个核心接口：
    - GraphStore: 图存储（复用 lineage-analyzer 的图谱基础设施思路）
    - EntityExtractor: 实体抽取
    - RelationExtractor: 关系抽取
"""
from __future__ import annotations

from knowledge_engine.interfaces.entity_extractor import EntityExtractor
from knowledge_engine.interfaces.graph_store import GraphStore
from knowledge_engine.interfaces.relation_extractor import RelationExtractor

__all__ = ["EntityExtractor", "GraphStore", "RelationExtractor"]