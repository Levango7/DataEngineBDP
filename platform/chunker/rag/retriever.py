"""RAG 检索器 (T008-6).

提供基于向量存储的检索能力：
    query -> embedding -> VectorStore.search -> RetrievalResult

特性：
1. **自动 embedding**：查询文本自动调用 EmbeddingAdapter 生成向量
2. **元数据还原**：检索结果携带完整 metadata，可还原为 Chunk
3. **标量过滤**：支持按模态/来源等元数据过滤
4. **分数阈值**：支持 min_score 过滤低质量结果
5. **混合检索**：向量 + 标量过滤

对齐设计文档 T008-6。
"""
from __future__ import annotations

import logging
from typing import Any, Optional

from chunker.embedding.base import EmbeddingAdapter
from chunker.models import Chunk, ChunkMetadata, Modality
from chunker.rag.exceptions import RetrieveError
from chunker.rag.vector_store import SearchResult, VectorStore

logger = logging.getLogger(__name__)


class RetrievalResult:
    """单次检索结果.

    封装命中切片及其分数，可还原为 Chunk。
    """

    def __init__(
        self,
        chunk_id: str,
        score: float,
        metadata: dict[str, Any],
    ) -> None:
        self.chunkId = chunk_id
        self.score = score
        self.metadata = metadata

    def to_chunk(self) -> Chunk:
        """将检索结果还原为 Chunk（不含原始 content，仅元数据）.

        :return: Chunk 实例（content 为 metadata 中的文本占位）
        """
        meta = self.metadata or {}
        modality_str = meta.get("modality", "text")
        try:
            modality = Modality(modality_str)
        except ValueError:
            modality = Modality.TEXT
        chunk_meta = ChunkMetadata(
            modality=modality,
            source=meta.get("source", ""),
            start=meta.get("start", 0),
            end=meta.get("end", 0),
            index=meta.get("index", 0),
            extra=meta.get("extra", {}),
        )
        return Chunk(
            id=self.chunkId,
            content=meta.get("content", ""),
            metadata=chunk_meta,
            tokens=meta.get("tokens"),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "chunkId": self.chunkId,
            "score": self.score,
            "metadata": self.metadata,
        }


class Retriever:
    """RAG 检索器.

    用法::

        retriever = Retriever(store, adapter)
        results = await retriever.retrieve("chunks", "查询文本", top_k=10)
        for r in results:
            print(r.score, r.chunkId, r.metadata)
    """

    def __init__(
        self,
        store: VectorStore,
        adapter: EmbeddingAdapter,
        *,
        default_top_k: int = 10,
    ) -> None:
        """初始化检索器.

        :param store: 向量存储
        :param adapter: embedding 适配器
        :param default_top_k: 默认 topK
        """
        self.store = store
        self.adapter = adapter
        self.defaultTopK = default_top_k

    async def retrieve(
        self,
        collection_name: str,
        query: str,
        *,
        top_k: Optional[int] = None,
        filter: Optional[str] = None,
        min_score: Optional[float] = None,
        query_vector: Optional[list[float]] = None,
    ) -> list[RetrievalResult]:
        """检索.

        :param collection_name: 集合名
        :param query: 查询文本（当 query_vector 未提供时使用）
        :param top_k: topK，None 使用默认值
        :param filter: 标量过滤表达式
        :param min_score: 分数阈值
        :param query_vector: 预计算的查询向量，提供时跳过 embedding
        :return: 检索结果列表
        :raises RetrieveError: 检索失败
        """
        top_k = top_k or self.defaultTopK

        # 获取查询向量
        if query_vector is None:
            if not query:
                return []
            try:
                query_vector = await self.adapter.embed_query(query)
            except Exception as ex:  # noqa: BLE001
                raise RetrieveError(
                    f"生成查询 embedding 失败: {ex}", cause=ex
                ) from ex
        if not query_vector:
            return []

        # 检索
        try:
            if filter or min_score is not None:
                results = await self.store.hybrid_search(
                    collection_name,
                    query_vector,
                    top_k=top_k,
                    filter=filter,
                    min_score=min_score,
                )
            else:
                results = await self.store.search(
                    collection_name,
                    query_vector,
                    top_k=top_k,
                )
        except Exception as ex:  # noqa: BLE001
            raise RetrieveError(
                f"检索失败: {ex}", cause=ex
            ) from ex

        return [RetrievalResult(r.id, r.score, r.metadata) for r in results]

    async def retrieve_by_modality(
        self,
        collection_name: str,
        query: str,
        modality: Modality | str,
        *,
        top_k: Optional[int] = None,
        min_score: Optional[float] = None,
        query_vector: Optional[list[float]] = None,
    ) -> list[RetrievalResult]:
        """按模态检索.

        :param collection_name: 集合名
        :param query: 查询文本
        :param modality: 模态类型
        :param top_k: topK
        :param min_score: 分数阈值
        :param query_vector: 预计算的查询向量
        :return: 检索结果列表
        """
        mod_str = modality.value if isinstance(modality, Modality) else str(modality)
        filter_expr = f'modality == "{mod_str}"'
        return await self.retrieve(
            collection_name,
            query,
            top_k=top_k,
            filter=filter_expr,
            min_score=min_score,
            query_vector=query_vector,
        )

    async def retrieve_multi(
        self,
        collection_name: str,
        queries: list[str],
        *,
        top_k: Optional[int] = None,
        filter: Optional[str] = None,
    ) -> list[list[RetrievalResult]]:
        """多查询检索.

        :param collection_name: 集合名
        :param queries: 查询文本列表
        :param top_k: topK
        :param filter: 标量过滤
        :return: 每个查询的检索结果列表
        """
        import asyncio

        tasks = [
            self.retrieve(collection_name, q, top_k=top_k, filter=filter)
            for q in queries
        ]
        return await asyncio.gather(*tasks)