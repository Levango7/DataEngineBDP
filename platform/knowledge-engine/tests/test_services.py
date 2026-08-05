"""服务层单元测试."""
from __future__ import annotations

import pytest

from knowledge_engine.models.entity import Entity
from knowledge_engine.models.graph import GraphSchema
from knowledge_engine.models.relation import Relation
from knowledge_engine.repositories import SpaceNotFoundError
from knowledge_engine.services.knowledge_service import KnowledgeService


@pytest.fixture
def knowledge_service(
    mock_store,
    mock_entity_extractor,
    mock_relation_extractor,
) -> KnowledgeService:
    return KnowledgeService(
        store=mock_store,
        entity_extractor=mock_entity_extractor,
        relation_extractor=mock_relation_extractor,
    )


class TestKnowledgeService:
    """知识图谱构建服务测试."""

    @pytest.mark.asyncio
    async def test_create_and_list_space(self, knowledge_service) -> None:
        await knowledge_service.create_space("kg1")
        spaces = await knowledge_service.list_spaces()
        assert "kg1" in spaces

    @pytest.mark.asyncio
    async def test_drop_space(self, knowledge_service) -> None:
        await knowledge_service.create_space("kg1")
        await knowledge_service.drop_space("kg1")
        assert "kg1" not in await knowledge_service.list_spaces()

    @pytest.mark.asyncio
    async def test_extract(self, knowledge_service) -> None:
        await knowledge_service.create_space("kg1")
        result = await knowledge_service.extract("kg1", "张三在北京工作")
        assert len(result.entities) > 0
        # 关系取决于规则是否触发
        assert isinstance(result.relations, list)

    @pytest.mark.asyncio
    async def test_build(self, knowledge_service) -> None:
        await knowledge_service.create_space("kg1")
        result = await knowledge_service.build("kg1", "张三在北京工作")
        assert result.space == "kg1"
        assert result.insertedVertices > 0
        assert result.insertedVertices == len(result.entities)
        # 关系数应等于边数
        assert result.insertedEdges == len(result.relations)

    @pytest.mark.asyncio
    async def test_build_writes_to_store(self, knowledge_service, mock_store) -> None:
        await knowledge_service.create_space("kg1")
        result = await knowledge_service.build("kg1", "张三在北京工作")
        # 验证顶点已写入
        for ent in result.entities:
            v = await mock_store.get_vertex("kg1", ent.id)
            assert v.id == ent.id

    @pytest.mark.asyncio
    async def test_build_dedup_entities(self, knowledge_service) -> None:
        """同一实体多次出现应去重."""
        await knowledge_service.create_space("kg1")
        result = await knowledge_service.build("kg1", "张三和张三和张三")
        ids = [e.id for e in result.entities]
        assert len(ids) == len(set(ids))

    @pytest.mark.asyncio
    async def test_insert_entities(self, knowledge_service) -> None:
        await knowledge_service.create_space("kg1")
        entities = [
            Entity(id="e1", name="x", type="Person"),
            Entity(id="e2", name="y", type="City"),
        ]
        count = await knowledge_service.insert_entities("kg1", entities)
        assert count == 2

    @pytest.mark.asyncio
    async def test_insert_relations(self, knowledge_service) -> None:
        await knowledge_service.create_space("kg1")
        rels = [Relation(srcId="a", dstId="b", type="r")]
        count = await knowledge_service.insert_relations("kg1", rels)
        assert count == 1

    @pytest.mark.asyncio
    async def test_build_unknown_space(self, knowledge_service) -> None:
        with pytest.raises(SpaceNotFoundError):
            await knowledge_service.build("nope", "任意文本")

    @pytest.mark.asyncio
    async def test_extract_with_type_filter(self, knowledge_service) -> None:
        await knowledge_service.create_space("kg1")
        result = await knowledge_service.extract(
            "kg1", "张三在北京工作", entity_types=["Person"]
        )
        assert all(e.type == "Person" for e in result.entities)