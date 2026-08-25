"""NebulaGraph 图存储实现.

依赖：nebula3-python（可选依赖，仅在生产环境安装）。
设计原则：
    - 接口与 MockGraphStore 完全对齐，可互换。
    - SDK 调用封装在私有方法中，便于 Mock 测试。
    - 连接池复用，避免每次查询建立连接。
"""

from __future__ import annotations

import re
import threading
import time
from typing import Any

from knowledge_engine.interfaces.graph_store import GraphStore
from knowledge_engine.models.graph import (
    GraphSchema,
    QueryResult,
    Vertex,
)
from knowledge_engine.repositories import (
    QuerySyntaxError,
    StoreUnavailableError,
    VertexNotFoundError,
)

_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")


class NebulaGraphStore(GraphStore):
    """NebulaGraph 图存储.

    Args:
        host: GraphD 主机。
        port: GraphD 端口。
        user: 用户名。
        password: 密码。
        pool_size: 连接池大小。

    Raises:
        StoreUnavailableError: nebula3-python 未安装或连接失败。
    """

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 9669,
        user: str = "root",
        password: str = "nebula",
        pool_size: int = 10,
    ) -> None:
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.pool_size = pool_size
        self._session: Any = None
        self._executeLock = threading.Lock()
        # 延迟导入：仅在实例化时检查 SDK 可用性
        self._client = self._connect()

    # ---------- 连接 ----------

    def _connect(self) -> Any:
        """建立与 NebulaGraph 的连接（延迟导入 SDK）.

        Raises:
            StoreUnavailableError: SDK 未安装或连接失败。
        """
        try:
            from nebula3.Config import Config  # type: ignore
            from nebula3.gclient.net import ConnectionPool  # type: ignore
        except ImportError as exc:
            raise StoreUnavailableError("nebula3-python 未安装，请 pip install nebula3-python") from exc

        try:
            config = Config()
            config.max_connection_pool_size = self.pool_size
            pool = ConnectionPool()
            ok = pool.init([(self.host, self.port)], config)
            if not ok:
                raise StoreUnavailableError(f"NebulaGraph 连接初始化失败: {self.host}:{self.port}")
            session = pool.get_session(self.user, self.password)
            self._session = session
            return pool
        except Exception as exc:  # noqa: BLE001
            raise StoreUnavailableError(f"NebulaGraph 连接失败: {exc}") from exc

    def _ensure_session(self) -> Any:
        if self._session is None:
            raise StoreUnavailableError("NebulaGraph 会话未建立")
        return self._session

    def _execute(self, nql: str) -> Any:
        """执行 nGQL 并返回 ResultSet.

        单一共享 session 被所有协程复用，且 USE 会改变会话级空间态，
        因此以实例级锁串行化 execute，避免并发语句跨空间串写。

        Raises:
            QuerySyntaxError: 语句执行失败。
        """
        session = self._ensure_session()
        with self._executeLock:
            resp = session.execute(nql)
        if not resp.is_succeeded():
            raise QuerySyntaxError(f"nGQL 执行失败: {resp.error_msg()} :: {nql}")
        return resp

    # ---------- 空间管理 ----------

    async def create_space(self, space_name: str, schema: GraphSchema) -> None:
        self._validate_identifier(space_name)
        # 创建空间
        self._execute(
            f"CREATE SPACE IF NOT EXISTS `{space_name}` "
            f"(vid_type=FIXED_STRING(64), partition_num=10, replica_factor=1);"
        )
        # 应用 Schema：顶点标签
        for vl in schema.vertexLabels:
            self._validate_identifier(vl.name)
            for k in vl.properties:
                self._validate_identifier(k)
            props = ", ".join(f"`{k}` {self._nebula_type(v)}" for k, v in vl.properties.items())
            cols = f", {props}" if props else ""
            self._execute(f"USE `{space_name}`; CREATE TAG IF NOT EXISTS `{vl.name}`({cols});")
        # 边类型
        for et in schema.edgeTypes:
            self._validate_identifier(et.name)
            for k in et.properties:
                self._validate_identifier(k)
            props = ", ".join(f"`{k}` {self._nebula_type(v)}" for k, v in et.properties.items())
            cols = f", {props}" if props else ""
            self._execute(f"USE `{space_name}`; CREATE EDGE IF NOT EXISTS `{et.name}`({cols});")

    async def drop_space(self, space_name: str) -> None:
        self._validate_identifier(space_name)
        self._execute(f"DROP SPACE IF EXISTS `{space_name}`;")

    async def get_schema(self, space_name: str) -> GraphSchema:
        # 简化：返回空 Schema（实际应通过 SHOW TAGS/EDGES 解析）
        return GraphSchema()

    async def list_spaces(self) -> list[str]:
        resp = self._execute("SHOW SPACES;")
        return [row.values[0].get_s_val().decode() for row in resp.rows()]

    # ---------- 顶点 / 边写入 ----------

    async def insert_vertex(self, space: str, label: str, vid: str, props: dict) -> None:
        self._validate_identifier(space)
        self._validate_identifier(label)
        self._validate_identifier(vid)
        for k in props:
            self._validate_identifier(k)
        cols = ", ".join(f"`{k}`" for k in props.keys())
        vals = ", ".join(self._nebula_value(v) for v in props.values())
        col_clause = f"({cols})" if cols else "()"
        val_clause = f"({vals})" if vals else "()"
        self._execute(f"USE `{space}`; INSERT VERTEX `{label}`{col_clause} VALUES " f'"{vid}":{val_clause};')

    async def insert_edge(
        self,
        space: str,
        edge_type: str,
        src_id: str,
        dst_id: str,
        props: dict,
    ) -> None:
        self._validate_identifier(space)
        self._validate_identifier(edge_type)
        self._validate_identifier(src_id)
        self._validate_identifier(dst_id)
        for k in props:
            self._validate_identifier(k)
        cols = ", ".join(f"`{k}`" for k in props.keys())
        vals = ", ".join(self._nebula_value(v) for v in props.values())
        col_clause = f"({cols})" if cols else "()"
        val_clause = f"({vals})" if vals else "()"
        self._execute(
            f"USE `{space}`; INSERT EDGE `{edge_type}`{col_clause} VALUES " f'"{src_id}"->"{dst_id}":{val_clause};'
        )

    async def get_vertex(self, space: str, vid: str) -> Vertex:
        self._validate_identifier(space)
        self._validate_identifier(vid)
        resp = self._execute(f'USE `{space}`; FETCH PROP ON * "{vid}" YIELD vertex AS v;')
        if resp.rows() is None or len(resp.rows()) == 0:
            raise VertexNotFoundError(vid)
        # 简化：返回最小顶点（实际应解析 ResultSet）
        return Vertex(id=vid, label="_unknown", properties={})

    # ---------- 查询 ----------

    async def query(self, space: str, nql: str) -> QueryResult:
        self._validate_identifier(space)
        start = time.perf_counter()
        resp = self._execute(f"USE `{space}`; {nql}")
        columns = [col.get_name() for col in resp.keys()]
        rows: list[dict[str, Any]] = []
        for row in resp.rows():
            item: dict[str, Any] = {}
            for i, col in enumerate(columns):
                item[col] = str(row.values[i])
            rows.append(item)
        return QueryResult(
            columns=columns,
            rows=rows,
            latencyMs=(time.perf_counter() - start) * 1000,
        )

    async def get_neighbors(self, space: str, vid: str, edge_types: list[str] | None = None) -> list[Vertex]:
        self._validate_identifier(space)
        self._validate_identifier(vid)
        for e in edge_types or []:
            self._validate_identifier(e)
        edge_clause = "::" if not edge_types else ":" + "|".join(f"`{e}`" for e in edge_types) + ":"
        resp = self._execute(f'USE `{space}`; GO 1 STEPS FROM "{vid}" OVER {edge_clause} YIELD dst(edge) AS dst;')
        result: list[Vertex] = []
        for row in resp.rows():
            dst = row.values[0].get_s_val().decode()
            result.append(Vertex(id=dst, label="_unknown", properties={}))
        return result

    async def shortest_path(self, space: str, src_id: str, dst_id: str) -> list[Vertex]:
        self._validate_identifier(space)
        self._validate_identifier(src_id)
        self._validate_identifier(dst_id)
        self._execute(f'USE `{space}`; FIND SHORTEST PATH FROM "{src_id}" TO "{dst_id}" OVER * YIELD path AS p;')
        # 简化：返回空列表（实际应解析 path）
        return []

    # ---------- 内部工具 ----------

    @staticmethod
    def _validate_identifier(value: str) -> str:
        """校验 nGQL 标识符（space 名/vid/标签/边类型/属性名）.

        标识符以反引号或双引号内插进语句，无法安全转义，
        因此仅接受白名单字符集，其余一律 ValueError（路由层映射 400）。

        Raises:
            ValueError: 含非法字符或超长。
        """
        if not isinstance(value, str) or not _IDENTIFIER_PATTERN.match(value):
            raise ValueError(f"非法图标识符: {value!r}")
        return value

    @staticmethod
    def _nebula_type(py_type: str) -> str:
        """Python 类型字符串 -> NebulaGraph 类型."""
        mapping = {
            "str": "STRING",
            "string": "STRING",
            "int": "INT64",
            "int64": "INT64",
            "float": "DOUBLE",
            "double": "DOUBLE",
            "bool": "BOOL",
            "datetime": "DATETIME",
        }
        return mapping.get(py_type.lower(), "STRING")

    @staticmethod
    def _nebula_value(value: Any) -> str:
        """Python 值 -> nGQL 字面量."""
        if isinstance(value, str):
            escaped = value.replace("\\", "\\\\").replace('"', '\\"')
            return f'"{escaped}"'
        if isinstance(value, bool):
            return "true" if value else "false"
        if isinstance(value, (int, float)):
            return str(value)
        escaped = str(value).replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'
