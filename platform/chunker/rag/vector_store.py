"""向量存储抽象与实现 (T008-6).

提供统一的向量存储接口，支持：
    - MockVectorStore：基于内存 dict 的实现，用于单元测试与本地开发
    - MilvusVectorStore：基于 pymilvus 的生产实现

接口设计对齐 platform/vector-engine 的 Go 实现（store.VectorStore），
确保 Python RAG 管道与 Go 向量引擎语义一致。

对齐设计文档 T008-6。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
import asyncio
import math
import threading
from typing import Any, Optional

from chunker.rag.exceptions import (
    CollectionAlreadyExistsError,
    CollectionNotFoundError,
    VectorStoreError,
)

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 支持的度量类型
METRIC_L2 = "L2"
METRIC_IP = "IP"
METRIC_COSINE = "COSINE"

#: 支持的索引类型
INDEX_FLAT = "FLAT"
INDEX_IVF_FLAT = "IVF_FLAT"
INDEX_HNSW = "HNSW"
INDEX_IVF_PQ = "IVF_PQ"


# ----------------------------------------------------------------------
# 数据模型
# ----------------------------------------------------------------------


class CollectionInfo:
    """向量集合元信息."""

    def __init__(
        self,
        name: str,
        dimension: int,
        metric_type: str = METRIC_COSINE,
        index_type: str = INDEX_HNSW,
        vector_count: int = 0,
    ) -> None:
        self.name = name
        self.dimension = dimension
        self.metricType = metric_type
        self.indexType = index_type
        self.vectorCount = vector_count

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "dimension": self.dimension,
            "metricType": self.metricType,
            "indexType": self.indexType,
            "vectorCount": self.vectorCount,
        }


class VectorRecord:
    """一条向量记录."""

    def __init__(
        self,
        id: str,
        vector: list[float],
        metadata: Optional[dict[str, Any]] = None,
    ) -> None:
        self.id = id
        self.vector = list(vector)
        self.metadata = metadata or {}

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "vector": self.vector, "metadata": self.metadata}


class SearchResult:
    """一次检索命中结果."""

    def __init__(
        self,
        id: str,
        score: float,
        metadata: Optional[dict[str, Any]] = None,
    ) -> None:
        self.id = id
        self.score = score
        self.metadata = metadata or {}

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "score": self.score, "metadata": self.metadata}


# ----------------------------------------------------------------------
# 抽象基类
# ----------------------------------------------------------------------


class VectorStore(ABC):
    """向量存储抽象接口.

    封装向量集合管理与检索能力，对齐 Go 端 store.VectorStore。
    实现方需保证线程安全。
    """

    @abstractmethod
    async def create_collection(
        self,
        name: str,
        dimension: int,
        metric_type: str = METRIC_COSINE,
        index_type: str = INDEX_HNSW,
    ) -> None:
        """创建向量集合.

        :raises CollectionAlreadyExistsError: 集合已存在
        """

    @abstractmethod
    async def drop_collection(self, name: str) -> None:
        """删除集合.

        :raises CollectionNotFoundError: 集合不存在
        """

    @abstractmethod
    async def insert(
        self,
        collection_name: str,
        records: list[VectorRecord],
    ) -> None:
        """插入向量.

        :raises CollectionNotFoundError: 集合不存在
        :raises VectorStoreError: 维度不匹配等
        """

    @abstractmethod
    async def search(
        self,
        collection_name: str,
        vector: list[float],
        top_k: int = 10,
        filter: Optional[str] = None,
    ) -> list[SearchResult]:
        """向量检索.

        :raises CollectionNotFoundError: 集合不存在
        """

    @abstractmethod
    async def hybrid_search(
        self,
        collection_name: str,
        vector: list[float],
        top_k: int = 10,
        filter: Optional[str] = None,
        min_score: Optional[float] = None,
    ) -> list[SearchResult]:
        """混合检索（向量 + 标量过滤）.

        :raises CollectionNotFoundError: 集合不存在
        """

    @abstractmethod
    async def delete(
        self,
        collection_name: str,
        ids: list[str],
    ) -> None:
        """按 ID 删除向量."""

    @abstractmethod
    async def get_stats(self, collection_name: str) -> CollectionInfo:
        """获取集合统计信息.

        :raises CollectionNotFoundError: 集合不存在
        """

    async def close(self) -> None:
        """关闭连接（默认空实现）."""
        return None


# ----------------------------------------------------------------------
# Mock 实现
# ----------------------------------------------------------------------


class MockVectorStore(VectorStore):
    """基于内存 dict 的 Mock 向量存储.

    用于单元测试与本地开发，零外部依赖。
    暴力检索（计算与所有向量的相似度），结果精确。
    """

    def __init__(self) -> None:
        self._lock = threading.RLock()
        # name -> CollectionInfo
        self._collections: dict[str, CollectionInfo] = {}
        # name -> {id -> VectorRecord}
        self._vectors: dict[str, dict[str, VectorRecord]] = {}

    async def create_collection(
        self,
        name: str,
        dimension: int,
        metric_type: str = METRIC_COSINE,
        index_type: str = INDEX_HNSW,
    ) -> None:
        with self._lock:
            if name in self._collections:
                raise CollectionAlreadyExistsError(name)
            self._collections[name] = CollectionInfo(name, dimension, metric_type, index_type, 0)
            self._vectors[name] = {}

    async def drop_collection(self, name: str) -> None:
        with self._lock:
            if name not in self._collections:
                raise CollectionNotFoundError(name)
            del self._collections[name]
            self._vectors.pop(name, None)

    async def insert(
        self,
        collection_name: str,
        records: list[VectorRecord],
    ) -> None:
        with self._lock:
            if collection_name not in self._collections:
                raise CollectionNotFoundError(collection_name)
            info = self._collections[collection_name]
            store = self._vectors[collection_name]
            for rec in records:
                if len(rec.vector) != info.dimension:
                    raise VectorStoreError(
                        f"向量维度不匹配，期望 {info.dimension}，" f"实际 {len(rec.vector)}（id={rec.id}）"
                    )
                # 深拷贝避免外部修改污染
                store[rec.id] = VectorRecord(rec.id, list(rec.vector), dict(rec.metadata))
            info.vectorCount = len(store)

    async def search(
        self,
        collection_name: str,
        vector: list[float],
        top_k: int = 10,
        filter: Optional[str] = None,
    ) -> list[SearchResult]:
        with self._lock:
            if collection_name not in self._collections:
                raise CollectionNotFoundError(collection_name)
            info = self._collections[collection_name]
            store = self._vectors[collection_name]

        # 计算相似度
        scored: list[tuple[float, VectorRecord]] = []
        for rec in store.values():
            score = _compute_similarity(vector, rec.vector, info.metricType)
            if filter and not _eval_filter(filter, rec.metadata):
                continue
            scored.append((score, rec))
        # 排序：距离越小越相似（L2），相似度越大越相似（IP/COSINE）
        reverse = info.metricType in (METRIC_IP, METRIC_COSINE)
        scored.sort(key=lambda x: x[0], reverse=reverse)
        return [SearchResult(rec.id, score, dict(rec.metadata)) for score, rec in scored[:top_k]]

    async def hybrid_search(
        self,
        collection_name: str,
        vector: list[float],
        top_k: int = 10,
        filter: Optional[str] = None,
        min_score: Optional[float] = None,
    ) -> list[SearchResult]:
        results = await self.search(collection_name, vector, top_k * 2, filter)
        if min_score is not None:
            results = [r for r in results if r.score >= min_score]
        return results[:top_k]

    async def delete(
        self,
        collection_name: str,
        ids: list[str],
    ) -> None:
        with self._lock:
            if collection_name not in self._collections:
                raise CollectionNotFoundError(collection_name)
            store = self._vectors[collection_name]
            for id_ in ids:
                store.pop(id_, None)
            self._collections[collection_name].vectorCount = len(store)

    async def get_stats(self, collection_name: str) -> CollectionInfo:
        with self._lock:
            if collection_name not in self._collections:
                raise CollectionNotFoundError(collection_name)
            info = self._collections[collection_name]
            # 返回副本避免外部修改
            return CollectionInfo(
                info.name,
                info.dimension,
                info.metricType,
                info.indexType,
                info.vectorCount,
            )


# ----------------------------------------------------------------------
# 辅助函数
# ----------------------------------------------------------------------


def _compute_similarity(a: list[float], b: list[float], metric: str) -> float:
    """计算向量相似度/距离.

    :param a: 向量 A
    :param b: 向量 B
    :param metric: 度量类型（L2/IP/COSINE）
    :return: L2 返回距离（越小越相似），IP/COSINE 返回相似度（越大越相似）
    """
    if not a or not b:
        return 0.0
    if metric == METRIC_L2:
        return -sum((x - y) ** 2 for x, y in zip(a, b))  # 取负使越大越相似
    if metric == METRIC_IP:
        return sum(x * y for x, y in zip(a, b))
    # COSINE
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


def _eval_filter(expr: str, metadata: dict[str, Any]) -> bool:
    """简单标量过滤表达式求值（Mock 用）.

    支持子集 Milvus 表达式语法：
        - ``key == "value"``
        - ``key == 123``
        - ``key > 123`` / ``key >= 123`` / ``key < 123`` / ``key <= 123``
        - ``expr1 && expr2``

    复杂表达式默认返回 True（不过滤）。

    :param expr: 过滤表达式
    :param metadata: 元数据
    :return: 是否满足
    """
    if not expr:
        return True
    # 处理 && 连接
    if "&&" in expr:
        parts = expr.split("&&")
        return all(_eval_filter(p.strip(), metadata) for p in parts)
    # 处理比较运算符
    for op in ("==", ">=", "<=", ">", "<"):
        if op in expr:
            key, _, value = expr.partition(op)
            key = key.strip()
            value = value.strip()
            # 去引号
            if value.startswith('"') and value.endswith('"'):
                value = value[1:-1]
            elif value.startswith("'") and value.endswith("'"):
                value = value[1:-1]
            else:
                # 尝试转数字
                try:
                    value = int(value)
                except ValueError:
                    try:
                        value = float(value)
                    except ValueError:
                        pass
            actual = metadata.get(key)
            if actual is None:
                return False
            try:
                if op == "==":
                    return actual == value
                if op == ">=":
                    return actual >= value
                if op == "<=":
                    return actual <= value
                if op == ">":
                    return actual > value
                if op == "<":
                    return actual < value
            except TypeError:
                return False
    # 未知表达式：不过滤
    return True


# ----------------------------------------------------------------------
# Milvus 实现
# ----------------------------------------------------------------------


def is_pymilvus_available() -> bool:
    """检查 pymilvus 是否已安装."""
    try:
        import pymilvus  # noqa: F401

        return True
    except ImportError:
        return False


class MilvusVectorStore(VectorStore):
    """基于 pymilvus 的 Milvus 向量存储.

    生产实现，需安装 pymilvus 并启动 Milvus 服务。
    未安装 pymilvus 时，所有操作抛出 VectorStoreError。

    用法::

        store = MilvusVectorStore(host="127.0.0.1", port=19530)
        await store.create_collection("chunks", 1024)
        await store.insert("chunks", [VectorRecord("id1", vec)])
        results = await store.search("chunks", query_vec, top_k=10)
    """

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 19530,
        database: str = "default",
        username: Optional[str] = None,
        password: Optional[str] = None,
    ) -> None:
        self.host = host
        self.port = port
        self.database = database
        self.username = username
        self.password = password
        self._lock = threading.RLock()
        self._client: Any = None
        self._connected: bool = False

    def _ensure_client(self) -> Any:
        """懒加载 Milvus 客户端."""
        if self._client is not None:
            return self._client
        with self._lock:
            if self._client is not None:
                return self._client
            if not is_pymilvus_available():
                raise VectorStoreError("pymilvus 未安装，请 pip install pymilvus")
            try:
                from pymilvus import MilvusClient

                uri = f"http://{self.host}:{self.port}"
                kwargs: dict[str, Any] = {"uri": uri}
                if self.username:
                    kwargs["user"] = self.username
                if self.password:
                    kwargs["password"] = self.password
                if self.database and self.database != "default":
                    kwargs["db_name"] = self.database
                client = MilvusClient(**kwargs)
                self._client = client
                self._connected = True
                return client
            except Exception as ex:  # noqa: BLE001
                raise VectorStoreError(f"连接 Milvus 失败: {ex}", cause=ex) from ex

    async def create_collection(
        self,
        name: str,
        dimension: int,
        metric_type: str = METRIC_COSINE,
        index_type: str = INDEX_HNSW,
    ) -> None:
        loop = asyncio.get_running_loop()

        def _work() -> None:
            client = self._ensure_client()
            try:
                if client.has_collection(name):
                    raise CollectionAlreadyExistsError(name)
                from pymilvus import CollectionSchema, DataType, FieldSchema

                fields = [
                    FieldSchema("id", DataType.VARCHAR, max_length=65535, is_primary=True),
                    FieldSchema("vector", DataType.FLOAT_VECTOR, dim=dimension),
                    FieldSchema("metadata", DataType.JSON),
                ]
                schema = CollectionSchema(fields)
                client.create_collection(name, schema, metric_type=metric_type, index_type=index_type)
            except CollectionAlreadyExistsError:
                raise
            except Exception as ex:  # noqa: BLE001
                raise VectorStoreError(f"创建集合 {name} 失败: {ex}", cause=ex) from ex

        await loop.run_in_executor(None, _work)

    async def drop_collection(self, name: str) -> None:
        loop = asyncio.get_running_loop()

        def _work() -> None:
            client = self._ensure_client()
            try:
                if not client.has_collection(name):
                    raise CollectionNotFoundError(name)
                client.drop_collection(name)
            except CollectionNotFoundError:
                raise
            except Exception as ex:  # noqa: BLE001
                raise VectorStoreError(f"删除集合 {name} 失败: {ex}", cause=ex) from ex

        await loop.run_in_executor(None, _work)

    async def insert(
        self,
        collection_name: str,
        records: list[VectorRecord],
    ) -> None:
        loop = asyncio.get_running_loop()

        def _work() -> None:
            client = self._ensure_client()
            try:
                if not client.has_collection(collection_name):
                    raise CollectionNotFoundError(collection_name)
                data = [{"id": r.id, "vector": r.vector, "metadata": r.metadata} for r in records]
                client.insert(collection_name, data)
            except CollectionNotFoundError:
                raise
            except Exception as ex:  # noqa: BLE001
                raise VectorStoreError(f"插入向量失败: {ex}", cause=ex) from ex

        await loop.run_in_executor(None, _work)

    async def search(
        self,
        collection_name: str,
        vector: list[float],
        top_k: int = 10,
        filter: Optional[str] = None,
    ) -> list[SearchResult]:
        loop = asyncio.get_running_loop()

        def _work() -> list[SearchResult]:
            client = self._ensure_client()
            try:
                if not client.has_collection(collection_name):
                    raise CollectionNotFoundError(collection_name)
                kwargs: dict[str, Any] = {
                    "data": [vector],
                    "limit": top_k,
                    "output_fields": ["metadata"],
                }
                if filter:
                    kwargs["filter"] = filter
                results = client.search(collection_name, **kwargs)
                out: list[SearchResult] = []
                for hits in results:
                    for hit in hits:
                        entity = hit.get("entity", {})
                        out.append(
                            SearchResult(
                                id=hit.get("id", ""),
                                score=float(hit.get("distance", 0.0)),
                                metadata=entity.get("metadata", {}),
                            )
                        )
                return out
            except CollectionNotFoundError:
                raise
            except Exception as ex:  # noqa: BLE001
                raise VectorStoreError(f"检索失败: {ex}", cause=ex) from ex

        return await loop.run_in_executor(None, _work)

    async def hybrid_search(
        self,
        collection_name: str,
        vector: list[float],
        top_k: int = 10,
        filter: Optional[str] = None,
        min_score: Optional[float] = None,
    ) -> list[SearchResult]:
        results = await self.search(collection_name, vector, top_k * 2, filter)
        if min_score is not None:
            results = [r for r in results if r.score >= min_score]
        return results[:top_k]

    async def delete(
        self,
        collection_name: str,
        ids: list[str],
    ) -> None:
        loop = asyncio.get_running_loop()

        def _work() -> None:
            client = self._ensure_client()
            try:
                if not client.has_collection(collection_name):
                    raise CollectionNotFoundError(collection_name)
                client.delete(collection_name, ids)
            except CollectionNotFoundError:
                raise
            except Exception as ex:  # noqa: BLE001
                raise VectorStoreError(f"删除向量失败: {ex}", cause=ex) from ex

        await loop.run_in_executor(None, _work)

    async def get_stats(self, collection_name: str) -> CollectionInfo:
        loop = asyncio.get_running_loop()

        def _work() -> CollectionInfo:
            client = self._ensure_client()
            try:
                if not client.has_collection(collection_name):
                    raise CollectionNotFoundError(collection_name)
                stats = client.get_collection_stats(collection_name)
                desc = client.describe_collection(collection_name)
                dim = 0
                for f in desc.get("fields", []):
                    if f.get("name") == "vector":
                        dim = f.get("params", {}).get("dim", 0)
                return CollectionInfo(
                    name=collection_name,
                    dimension=dim,
                    metric_type=stats.get("metric_type", METRIC_COSINE),
                    index_type=stats.get("index_type", INDEX_HNSW),
                    vector_count=int(stats.get("row_count", 0)),
                )
            except CollectionNotFoundError:
                raise
            except Exception as ex:  # noqa: BLE001
                raise VectorStoreError(f"获取统计失败: {ex}", cause=ex) from ex

        return await loop.run_in_executor(None, _work)

    async def close(self) -> None:
        if self._client is not None:
            try:
                self._client.close()
            except Exception:  # noqa: BLE001
                pass
            self._client = None
            self._connected = False


# ----------------------------------------------------------------------
# 工厂函数
# ----------------------------------------------------------------------


def create_vector_store(
    store_type: str = "mock",
    **kwargs: Any,
) -> VectorStore:
    """创建向量存储实例.

    :param store_type: ``"mock"`` 或 ``"milvus"``
    :param kwargs: 构造参数
    :return: VectorStore 实例
    :raises ValueError: 未知 store_type
    """
    st = store_type.lower()
    if st == "mock":
        return MockVectorStore()
    if st == "milvus":
        return MilvusVectorStore(**kwargs)
    raise ValueError(f"未知 store_type: {store_type}，支持 mock/milvus")
