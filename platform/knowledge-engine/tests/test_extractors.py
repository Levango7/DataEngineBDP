"""实体/关系抽取器单元测试."""
from __future__ import annotations

import pytest

from knowledge_engine.repositories.llm.entity_extractor import LLMEntityExtractor
from knowledge_engine.repositories.llm.relation_extractor import (
    LLMRelationExtractor,
)
from knowledge_engine.repositories.mock import (
    MockEntityExtractor,
    MockRelationExtractor,
)


class TestMockEntityExtractor:
    """Mock 实体抽取器测试."""

    @pytest.mark.asyncio
    async def test_extract_person(self) -> None:
        extractor = MockEntityExtractor()
        entities = await extractor.extract("张三在北京工作")
        types = {e.type for e in entities}
        assert "Person" in types
        names = {e.name for e in entities if e.type == "Person"}
        assert "张三" in names

    @pytest.mark.asyncio
    async def test_extract_organization(self) -> None:
        extractor = MockEntityExtractor()
        entities = await extractor.extract("华为公司是全球领先的科技公司")
        types = {e.type for e in entities}
        assert "Organization" in types

    @pytest.mark.asyncio
    async def test_extract_city(self) -> None:
        extractor = MockEntityExtractor()
        entities = await extractor.extract("北京市是中国的首都")
        types = {e.type for e in entities}
        assert "City" in types

    @pytest.mark.asyncio
    async def test_extract_date(self) -> None:
        extractor = MockEntityExtractor()
        entities = await extractor.extract("公司成立于2020年3月15日")
        types = {e.type for e in entities}
        assert "Date" in types

    @pytest.mark.asyncio
    async def test_extract_with_type_filter(self) -> None:
        extractor = MockEntityExtractor()
        entities = await extractor.extract(
            "张三在北京工作", entity_types=["Person"]
        )
        assert all(e.type == "Person" for e in entities)
        assert len(entities) >= 1

    @pytest.mark.asyncio
    async def test_extract_dedup(self) -> None:
        extractor = MockEntityExtractor()
        entities = await extractor.extract("张三和张三是同一个人")
        persons = [e for e in entities if e.type == "Person" and e.name == "张三"]
        assert len(persons) == 1

    @pytest.mark.asyncio
    async def test_extract_empty_text(self) -> None:
        extractor = MockEntityExtractor()
        entities = await extractor.extract("")
        assert entities == []

    @pytest.mark.asyncio
    async def test_extract_confidence_range(self) -> None:
        extractor = MockEntityExtractor()
        entities = await extractor.extract("张三在北京工作")
        for e in entities:
            assert 0.0 <= e.confidence <= 1.0


class TestMockRelationExtractor:
    """Mock 关系抽取器测试."""

    @pytest.mark.asyncio
    async def test_extract_located_in(self) -> None:
        entity_extractor = MockEntityExtractor()
        relation_extractor = MockRelationExtractor()
        text = "华为公司位于深圳市"
        entities = await entity_extractor.extract(text)
        relations = await relation_extractor.extract(text, entities)
        types = {r.type for r in relations}
        assert "located_in" in types

    @pytest.mark.asyncio
    async def test_extract_founded_in(self) -> None:
        entity_extractor = MockEntityExtractor()
        relation_extractor = MockRelationExtractor()
        text = "华为公司成立于1987年"
        entities = await entity_extractor.extract(text)
        relations = await relation_extractor.extract(text, entities)
        types = {r.type for r in relations}
        assert "founded_in" in types

    @pytest.mark.asyncio
    async def test_extract_no_entities(self) -> None:
        relation_extractor = MockRelationExtractor()
        relations = await relation_extractor.extract("任意文本", [])
        assert relations == []

    @pytest.mark.asyncio
    async def test_extract_confidence_range(self) -> None:
        entity_extractor = MockEntityExtractor()
        relation_extractor = MockRelationExtractor()
        text = "华为公司位于深圳市"
        entities = await entity_extractor.extract(text)
        relations = await relation_extractor.extract(text, entities)
        for r in relations:
            assert 0.0 <= r.confidence <= 1.0


class TestLLMEntityExtractorParsing:
    """LLM 抽取器解析逻辑测试（不实际调用网关）."""

    def test_parse_entities_valid_json(self) -> None:
        payload = '[{"name": "张三", "type": "Person", "properties": {}}]'
        result = LLMEntityExtractor._parse_entities(payload, ["Person"])
        assert len(result) == 1
        assert result[0].name == "张三"
        assert result[0].type == "Person"

    def test_parse_entities_with_codeblock(self) -> None:
        payload = '```json\n[{"name": "李四", "type": "Person"}]\n```'
        result = LLMEntityExtractor._parse_entities(payload, ["Person"])
        assert len(result) == 1
        assert result[0].name == "李四"

    def test_parse_entities_invalid_json(self) -> None:
        result = LLMEntityExtractor._parse_entities("not json", ["Person"])
        assert result == []

    def test_parse_entities_filter_type(self) -> None:
        payload = (
            '[{"name": "a", "type": "Person"}, {"name": "b", "type": "Unknown"}]'
        )
        result = LLMEntityExtractor._parse_entities(payload, ["Person"])
        assert len(result) == 1
        assert result[0].name == "a"

    def test_parse_entities_missing_fields(self) -> None:
        payload = '[{"name": "a"}, {"type": "Person"}]'
        result = LLMEntityExtractor._parse_entities(payload, ["Person"])
        assert result == []


class TestLLMRelationExtractorParsing:
    """LLM 关系抽取器解析逻辑测试."""

    def test_parse_relations_valid(self) -> None:
        payload = (
            '[{"srcId": "a", "dstId": "b", "type": "r"}, '
            '{"srcId": "a", "dstId": "c", "type": "r"}]'
        )
        result = LLMRelationExtractor._parse_relations(payload, {"a", "b", "c"})
        assert len(result) == 2

    def test_parse_relations_filter_unknown_id(self) -> None:
        payload = (
            '[{"srcId": "a", "dstId": "b", "type": "r"}, '
            '{"srcId": "x", "dstId": "y", "type": "r"}]'
        )
        result = LLMRelationExtractor._parse_relations(payload, {"a", "b"})
        assert len(result) == 1
        assert result[0].srcId == "a"

    def test_parse_relations_invalid(self) -> None:
        result = LLMRelationExtractor._parse_relations("not json", {"a", "b"})
        assert result == []