"""图存储抽象接口（复用血缘分析的基础设施思路）.

设计原则：
    - 接口与实现解耦：Mock / NebulaGraph / 其他图库可互换。
    - 异步 API：适配 FastAPI 与高并发图查询场景。
    - 多空间隔离：每个知识空间对应一个图空间（Graph Space）。
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from knowledge_engine.models.graph import (
    Edge,
    GraphSchema,
    QueryResult,
    Vertex,
)


class GraphStore(ABC):
    """图存储抽象接口.

    职责：知识空间（Graph Space）生命周期、顶点/边写入、图查询、邻居查询、最短路径。
    实现：
        - MockGraphStore: 内存邻接表实现（测试与无外部依赖场景）
        - NebulaGraphStore: 调用 nebula-python SDK（生产）
    """

    # ---------- 空间管理 ----------

    @abstractmethod
    async def create_space(self, space_name: str, schema: GraphSchema) -> None:
        """创建图空间并应用 Schema.

        Args:
            space_name: 空间名。
            schema: 图模式（顶点标签 + 边类型定义）。

        Raises:
            SpaceAlreadyExistsError: 空间已存在。
            StoreUnavailableError: 存储后端不可用。
        """
        ...

    @abstractmethod
    async def drop_space(self, space_name: str) -> None:
        """删除图空间.

        Raises:
            SpaceNotFoundError: 空间不存在。
        """
        ...

    @abstractmethod
    async def get_schema(self, space_name: str) -> GraphSchema:
        """获取空间 Schema.

        Raises:
            SpaceNotFoundError: 空间不存在。
        """
        ...

    @abstractmethod
    async def list_spaces(self) -> list[str]:
        """列出所有空间名."""
        ...

    # ---------- 顶点 / 边写入 ----------

    @abstractmethod
    async def insert_vertex(self, space: str, label: str, vid: str, props: dict) -> None:
        """插入或更新顶点.

        Args:
            space: 空间名。
            label: 顶点标签。
            vid: 顶点 ID。
            props: 顶点属性。

        Raises:
            SpaceNotFoundError: 空间不存在。
        """
        ...

    @abstractmethod
    async def insert_edge(
        self,
        space: str,
        edge_type: str,
        src_id: str,
        dst_id: str,
        props: dict,
    ) -> None:
        """插入或更新边.

        Args:
            space: 空间名。
            edge_type: 边类型。
            src_id: 起点顶点 ID。
            dst_id: 终点顶点 ID。
            props: 边属性。

        Raises:
            SpaceNotFoundError: 空间不存在。
        """
        ...

    @abstractmethod
    async def get_vertex(self, space: str, vid: str) -> Vertex:
        """获取顶点.

        Raises:
            SpaceNotFoundError: 空间不存在。
            VertexNotFoundError: 顶点不存在。
        """
        ...

    # ---------- 查询 ----------

    @abstractmethod
    async def query(self, space: str, nql: str) -> QueryResult:
        """执行原生图查询（nGQL / GQL）.

        Args:
            space: 空间名。
            nql: 查询语句。

        Returns:
            查询结果（列 + 行）。

        Raises:
            SpaceNotFoundError: 空间不存在。
            QuerySyntaxError: 查询语法错误。
        """
        ...

    @abstractmethod
    async def get_neighbors(self, space: str, vid: str, edge_types: list[str] | None = None) -> list[Vertex]:
        """获取顶点的邻居.

        Args:
            space: 空间名。
            vid: 顶点 ID。
            edge_types: 限定边类型；None 表示所有边类型。

        Returns:
            邻居顶点列表。

        Raises:
            SpaceNotFoundError: 空间不存在。
            VertexNotFoundError: 顶点不存在。
        """
        ...

    @abstractmethod
    async def shortest_path(self, space: str, src_id: str, dst_id: str) -> list[Vertex]:
        """最短路径查询（BFS）.

        Args:
            space: 空间名。
            src_id: 起点顶点 ID。
            dst_id: 终点顶点 ID。

        Returns:
            路径上的顶点列表（含起止）；不可达时返回空列表。

        Raises:
            SpaceNotFoundError: 空间不存在。
        """
        ...

    # ---------- 批量写入（可选优化，默认逐条调用） ----------

    async def bulk_insert(
        self,
        space: str,
        vertices: list[Vertex],
        edges: list[Edge],
    ) -> None:
        """批量写入顶点与边（默认实现：逐条调用）.

        实现端可覆写以提升吞吐（如 NebulaGraph 批量 INSERT）。
        """
        for v in vertices:
            await self.insert_vertex(space, v.label, v.id, v.properties)
        for e in edges:
            await self.insert_edge(space, e.type, e.srcId, e.dstId, e.properties)
