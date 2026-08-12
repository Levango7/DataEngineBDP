"""Mock 图存储 - 内存邻接表实现.

设计要点：
    - 每个空间维护独立的顶点表、边表、邻接表、Schema。
    - 邻接表同时维护出边与入边，支持邻居查询与最短路径。
    - 最短路径使用 BFS（无权图），与 NebulaGraph 的 FIND ALL PATH 风格对齐。
    - 线程安全：单进程内存态 + asyncio 单线程事件循环，无需加锁。
"""

from __future__ import annotations

from collections import deque
import time

from knowledge_engine.interfaces.graph_store import GraphStore
from knowledge_engine.models.graph import (
    Edge,
    GraphSchema,
    QueryResult,
    Vertex,
)
from knowledge_engine.repositories import (
    SpaceAlreadyExistsError,
    SpaceNotFoundError,
    VertexNotFoundError,
)


class _SpaceData:
    """单个图空间的内存数据."""

    def __init__(self, schema: GraphSchema) -> None:
        self.schema = schema
        # vid -> Vertex
        self.vertices: dict[str, Vertex] = {}
        # (src_id, edge_type, dst_id) -> Edge
        self.edges: dict[tuple[str, str, str], Edge] = {}
        # vid -> list[(edge_type, dst_id)] 出边
        self.out_adj: dict[str, list[tuple[str, str]]] = {}
        # vid -> list[(edge_type, src_id)] 入边
        self.in_adj: dict[str, list[tuple[str, str]]] = {}


class MockGraphStore(GraphStore):
    """内存图存储."""

    def __init__(self) -> None:
        self._spaces: dict[str, _SpaceData] = {}

    # ---------- 空间管理 ----------

    async def create_space(self, space_name: str, schema: GraphSchema) -> None:
        if space_name in self._spaces:
            raise SpaceAlreadyExistsError(space_name)
        self._spaces[space_name] = _SpaceData(schema=schema)

    async def drop_space(self, space_name: str) -> None:
        if space_name not in self._spaces:
            raise SpaceNotFoundError(space_name)
        del self._spaces[space_name]

    async def get_schema(self, space_name: str) -> GraphSchema:
        return self._require_space(space_name).schema

    async def list_spaces(self) -> list[str]:
        return list(self._spaces.keys())

    # ---------- 顶点 / 边写入 ----------

    async def insert_vertex(self, space: str, label: str, vid: str, props: dict) -> None:
        data = self._require_space(space)
        # upsert：保留并更新属性
        existing = data.vertices.get(vid)
        if existing is not None:
            existing.label = label
            existing.properties.update(props)
        else:
            data.vertices[vid] = Vertex(id=vid, label=label, properties=dict(props))
            data.out_adj.setdefault(vid, [])
            data.in_adj.setdefault(vid, [])

    async def insert_edge(
        self,
        space: str,
        edge_type: str,
        src_id: str,
        dst_id: str,
        props: dict,
    ) -> None:
        data = self._require_space(space)
        # 自动创建占位顶点（与 NebulaGraph 行为对齐：边引用的 VID 应存在，
        # Mock 模式宽松处理以简化测试）
        if src_id not in data.vertices:
            data.vertices[src_id] = Vertex(id=src_id, label="_auto", properties={})
            data.out_adj.setdefault(src_id, [])
            data.in_adj.setdefault(src_id, [])
        if dst_id not in data.vertices:
            data.vertices[dst_id] = Vertex(id=dst_id, label="_auto", properties={})
            data.out_adj.setdefault(dst_id, [])
            data.in_adj.setdefault(dst_id, [])

        key = (src_id, edge_type, dst_id)
        if key in data.edges:
            data.edges[key].properties.update(props)
        else:
            data.edges[key] = Edge(srcId=src_id, dstId=dst_id, type=edge_type, properties=dict(props))
            data.out_adj[src_id].append((edge_type, dst_id))
            data.in_adj[dst_id].append((edge_type, src_id))

    async def get_vertex(self, space: str, vid: str) -> Vertex:
        data = self._require_space(space)
        if vid not in data.vertices:
            raise VertexNotFoundError(vid)
        return data.vertices[vid]

    # ---------- 查询 ----------

    async def query(self, space: str, nql: str) -> QueryResult:
        """Mock 查询：仅支持极小子集.

        支持的语句（大小写不敏感）：
            - `MATCH (v) RETURN v`            返回所有顶点
            - `MATCH ()-[e]->() RETURN e`     返回所有边
            - `YIELD <vid>`                   返回指定顶点（简化）

        其他语句返回空结果（不抛错，便于容错测试）。
        """
        self._require_space(space)
        data = self._spaces[space]
        start = time.perf_counter()
        stmt = nql.strip().lower()

        if stmt.startswith("match (v)") and "return v" in stmt:
            rows = [v.model_dump() for v in data.vertices.values()]
            return QueryResult(
                columns=["v"],
                rows=rows,
                latencyMs=(time.perf_counter() - start) * 1000,
            )
        if stmt.startswith("match ()-[e]->()") and "return e" in stmt:
            rows = [e.model_dump() for e in data.edges.values()]
            return QueryResult(
                columns=["e"],
                rows=rows,
                latencyMs=(time.perf_counter() - start) * 1000,
            )
        if stmt.startswith("yield "):
            vid = nql.strip()[6:].strip().strip('"').strip("'")
            v = data.vertices.get(vid)
            rows = [v.model_dump()] if v else []
            return QueryResult(
                columns=["v"],
                rows=rows,
                latencyMs=(time.perf_counter() - start) * 1000,
            )
        # 默认：空结果
        return QueryResult(
            columns=[],
            rows=[],
            latencyMs=(time.perf_counter() - start) * 1000,
        )

    async def get_neighbors(self, space: str, vid: str, edge_types: list[str] | None = None) -> list[Vertex]:
        data = self._require_space(space)
        if vid not in data.vertices:
            raise VertexNotFoundError(vid)
        type_filter = set(edge_types) if edge_types else None
        result: list[Vertex] = []
        seen: set[str] = set()
        for edge_type, dst_id in data.out_adj.get(vid, []):
            if type_filter and edge_type not in type_filter:
                continue
            if dst_id in seen:
                continue
            seen.add(dst_id)
            result.append(data.vertices[dst_id])
        return result

    async def shortest_path(self, space: str, src_id: str, dst_id: str) -> list[Vertex]:
        data = self._require_space(space)
        if src_id not in data.vertices or dst_id not in data.vertices:
            return []
        if src_id == dst_id:
            return [data.vertices[src_id]]

        # BFS
        visited: dict[str, str | None] = {src_id: None}
        queue: deque[str] = deque([src_id])
        while queue:
            cur = queue.popleft()
            for _edge_type, nxt in data.out_adj.get(cur, []):
                if nxt in visited:
                    continue
                visited[nxt] = cur
                if nxt == dst_id:
                    # 回溯路径
                    path: list[str] = []
                    node: str | None = dst_id
                    while node is not None:
                        path.append(node)
                        node = visited[node]
                    path.reverse()
                    return [data.vertices[vid] for vid in path]
                queue.append(nxt)
        return []

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空所有空间（测试用）."""
        self._spaces.clear()

    def __len__(self) -> int:
        return len(self._spaces)

    # ---------- 内部 ----------

    def _require_space(self, space: str) -> _SpaceData:
        if space not in self._spaces:
            raise SpaceNotFoundError(space)
        return self._spaces[space]
