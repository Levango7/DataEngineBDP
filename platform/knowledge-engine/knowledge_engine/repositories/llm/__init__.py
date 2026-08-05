"""LLM 抽取器实现.

调用 platform/llm-gateway 进行 NER 与关系抽取。
仅在 KE_EXTRACTOR_TYPE=llm 时加载。
"""
from __future__ import annotations

from knowledge_engine.repositories.llm.entity_extractor import LLMEntityExtractor
from knowledge_engine.repositories.llm.relation_extractor import (
    LLMRelationExtractor,
)

__all__ = ["LLMEntityExtractor", "LLMRelationExtractor"]