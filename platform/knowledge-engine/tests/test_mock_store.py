"""Mock 图存储单元测试."""

from __future__ import annotations

import pytest

from knowledge_engine.models.graph import (
    GraphSchema,
    VertexLabelDefinition,
)
from knowledge_engine.repositories import (
    SpaceAlreadyExistsError,
    SpaceNotFoundError,
    VertexNotFoundError,
)
from knowledge_engine.repositories.mock import MockGraphStore


@pytest.fixture
def store_with_space(mock_store: MockGraphStore) -> MockGraphStore:
    """预创建一个空间的 store."""
    import asyncio

    asyncio.run(
        mock_store.create_space(
            "test",
            GraphSchema(vertexLabels=[VertexLabelDefinition(name="Person", properties={})]),
        )
    )
    return mock_store


class TestMockGraphStoreSpace:
    """空间管理测试."""

    @pytest.mark.asyncio
    async def test_create_space(self, mock_store: MockGraphStore) -> None:
        schema = GraphSchema(vertexLabels=[VertexLabelDefinition(name="Person")])
        await mock_store.create_space("kg1", schema)
        assert "kg1" in await mock_store.list_spaces()

    @pytest.mark.asyncio
    async def test_create_duplicate_space(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg1", GraphSchema())
        with pytest.raises(SpaceAlreadyExistsError):
            await mock_store.create_space("kg1", GraphSchema())

    @pytest.mark.asyncio
    async def test_drop_space(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg1", GraphSchema())
        await mock_store.drop_space("kg1")
        assert "kg1" not in await mock_store.list_spaces()

    @pytest.mark.asyncio
    async def test_drop_nonexistent_space(self, mock_store: MockGraphStore) -> None:
        with pytest.raises(SpaceNotFoundError):
            await mock_store.drop_space("nope")

    @pytest.mark.asyncio
    async def test_get_schema(self, mock_store: MockGraphStore) -> None:
        schema = GraphSchema(vertexLabels=[VertexLabelDefinition(name="Person")])
        await mock_store.create_space("kg1", schema)
        got = await mock_store.get_schema("kg1")
        assert len(got.vertexLabels) == 1
        assert got.vertexLabels[0].name == "Person"


class TestMockGraphStoreVertex:
    """顶点写入与查询测试."""

    @pytest.mark.asyncio
    async def test_insert_and_get_vertex(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "Person", "p1", {"name": "张三"})
        v = await mock_store.get_vertex("kg", "p1")
        assert v.id == "p1"
        assert v.label == "Person"
        assert v.properties["name"] == "张三"

    @pytest.mark.asyncio
    async def test_upsert_vertex(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "Person", "p1", {"name": "张三", "age": 20})
        await mock_store.insert_vertex("kg", "Person", "p1", {"age": 30})
        v = await mock_store.get_vertex("kg", "p1")
        assert v.properties["name"] == "张三"  # 保留
        assert v.properties["age"] == 30  # 更新

    @pytest.mark.asyncio
    async def test_get_vertex_not_found(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        with pytest.raises(VertexNotFoundError):
            await mock_store.get_vertex("kg", "nope")

    @pytest.mark.asyncio
    async def test_insert_vertex_unknown_space(self, mock_store: MockGraphStore) -> None:
        with pytest.raises(SpaceNotFoundError):
            await mock_store.insert_vertex("nope", "Person", "p1", {})


class TestMockGraphStoreEdge:
    """边写入与邻居查询测试."""

    @pytest.mark.asyncio
    async def test_insert_edge_and_neighbors(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "Person", "p1", {"name": "张三"})
        await mock_store.insert_vertex("kg", "City", "c1", {"name": "北京"})
        await mock_store.insert_edge("kg", "lives_in", "p1", "c1", {"since": 2020})

        neighbors = await mock_store.get_neighbors("kg", "p1")
        assert len(neighbors) == 1
        assert neighbors[0].id == "c1"

    @pytest.mark.asyncio
    async def test_neighbors_with_edge_type_filter(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "Person", "p1", {})
        await mock_store.insert_vertex("kg", "City", "c1", {})
        await mock_store.insert_vertex("kg", "Org", "o1", {})
        await mock_store.insert_edge("kg", "lives_in", "p1", "c1", {})
        await mock_store.insert_edge("kg", "works_for", "p1", "o1", {})

        all_neighbors = await mock_store.get_neighbors("kg", "p1")
        assert len(all_neighbors) == 2

        filtered = await mock_store.get_neighbors("kg", "p1", ["lives_in"])
        assert len(filtered) == 1
        assert filtered[0].id == "c1"

    @pytest.mark.asyncio
    async def test_neighbors_unknown_vertex(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        with pytest.raises(VertexNotFoundError):
            await mock_store.get_neighbors("kg", "nope")


class TestMockGraphStoreShortestPath:
    """最短路径测试."""

    @pytest.mark.asyncio
    async def test_shortest_path_direct(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "P", "a", {})
        await mock_store.insert_vertex("kg", "P", "b", {})
        await mock_store.insert_edge("kg", "r", "a", "b", {})
        path = await mock_store.shortest_path("kg", "a", "b")
        assert [v.id for v in path] == ["a", "b"]

    @pytest.mark.asyncio
    async def test_shortest_path_two_hops(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "P", "a", {})
        await mock_store.insert_vertex("kg", "P", "b", {})
        await mock_store.insert_vertex("kg", "P", "c", {})
        await mock_store.insert_edge("kg", "r", "a", "b", {})
        await mock_store.insert_edge("kg", "r", "b", "c", {})
        path = await mock_store.shortest_path("kg", "a", "c")
        assert [v.id for v in path] == ["a", "b", "c"]

    @pytest.mark.asyncio
    async def test_shortest_path_unreachable(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "P", "a", {})
        await mock_store.insert_vertex("kg", "P", "b", {})
        path = await mock_store.shortest_path("kg", "a", "b")
        assert path == []

    @pytest.mark.asyncio
    async def test_shortest_path_self(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "P", "a", {})
        path = await mock_store.shortest_path("kg", "a", "a")
        assert [v.id for v in path] == ["a"]


class TestMockGraphStoreQuery:
    """原生查询测试（Mock 子集）."""

    @pytest.mark.asyncio
    async def test_query_match_vertices(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "P", "a", {"name": "x"})
        await mock_store.insert_vertex("kg", "P", "b", {"name": "y"})
        result = await mock_store.query("kg", "MATCH (v) RETURN v")
        assert result.columns == ["v"]
        assert len(result.rows) == 2

    @pytest.mark.asyncio
    async def test_query_match_edges(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "P", "a", {})
        await mock_store.insert_vertex("kg", "P", "b", {})
        await mock_store.insert_edge("kg", "r", "a", "b", {})
        result = await mock_store.query("kg", "MATCH ()-[e]->() RETURN e")
        assert result.columns == ["e"]
        assert len(result.rows) == 1

    @pytest.mark.asyncio
    async def test_query_unknown_returns_empty(self, mock_store: MockGraphStore) -> None:
        await mock_store.create_space("kg", GraphSchema())
        result = await mock_store.query("kg", "UNKNOWN STATEMENT")
        assert result.rows == []
