"""图查询服务."""

from __future__ import annotations

from knowledge_engine.interfaces.graph_store import GraphStore
from knowledge_engine.models.graph import QueryResult, Vertex


class QueryService:
    """图查询服务（顶点 / 邻居 / 原生查询 / 最短路径）."""

    def __init__(self, store: GraphStore) -> None:
        self.store = store

    async def get_vertex(self, space: str, vid: str) -> Vertex:
        """获取顶点."""
        return await self.store.get_vertex(space, vid)

    async def get_neighbors(self, space: str, vid: str, edge_types: list[str] | None = None) -> list[Vertex]:
        """获取邻居."""
        return await self.store.get_neighbors(space, vid, edge_types)

    async def query(self, space: str, nql: str) -> QueryResult:
        """执行原生图查询."""
        return await self.store.query(space, nql)

    async def shortest_path(self, space: str, src_id: str, dst_id: str) -> list[Vertex]:
        """最短路径查询."""
        return await self.store.shortest_path(space, src_id, dst_id)
