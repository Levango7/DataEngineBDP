"""端到端 RAG 管道 (T008-6).

封装从切片 -> embedding -> 索引 -> 检索 -> 融合的完整流程：

    原始文档
      └── chunker.chunk(content, config)         # T008-1~5 切片
      └── indexer.index(collection, chunks)      # 自动 embedding + 写入向量库
      └── retriever.retrieve(collection, query)  # 向量检索
      └── fusion.retrieve_fused(...)             # 多模态融合检索

特性：
1. **一站式 API**：单次调用完成切片+索引+检索
2. **多模态支持**：文本/表格/图像/语音切片均可处理
3. **自动 embedding**：切片未携带 embedding 时自动生成
4. **融合检索**：支持 RRF / 加权融合
5. **可配置**：通过 RAGSettings 配置全流程

对齐设计文档 T008-6。
"""

from __future__ import annotations

import logging
from typing import Any, Optional

from chunker.base import BaseChunker
from chunker.embedding.base import EmbeddingAdapter
from chunker.models import Chunk, ChunkConfig, Modality
from chunker.rag.config import (
    RAGSettings,
    get_rag_settings,
)
from chunker.rag.exceptions import RAGError
from chunker.rag.fusion import MultiModalFusionRetriever
from chunker.rag.indexer import Indexer
from chunker.rag.retriever import RetrievalResult, Retriever
from chunker.rag.vector_store import VectorStore, create_vector_store

logger = logging.getLogger(__name__)


class RAGPipeline:
    """端到端 RAG 管道.

    用法::

        pipeline = RAGPipeline(
            chunker=get_chunker("text"),
            adapter=get_adapter("bge-large-zh"),
            store=MockVectorStore(),
        )
        # 索引
        await pipeline.index("chunks", "长文档文本", config)
        # 检索
        results = await pipeline.retrieve("chunks", "查询")
        # 多模态融合检索
        results = await pipeline.retrieve_fused("chunks", "查询", modalities=[...])
    """

    def __init__(
        self,
        chunker: BaseChunker,
        adapter: EmbeddingAdapter,
        store: Optional[VectorStore] = None,
        *,
        settings: Optional[RAGSettings] = None,
        collection_name: Optional[str] = None,
    ) -> None:
        """初始化 RAG 管道.

        :param chunker: 切片器
        :param adapter: embedding 适配器
        :param store: 向量存储，None 则按 settings 创建
        :param settings: RAG 配置，None 使用全局配置
        :param collection_name: 默认集合名
        """
        self.chunker = chunker
        self.adapter = adapter
        self.settings = settings or get_rag_settings()
        self.store = store or create_vector_store(
            self.settings.storeType,
            host=self.settings.milvusHost,
            port=self.settings.milvusPort,
            database=self.settings.milvusDatabase,
            username=self.settings.milvusUsername,
            password=self.settings.milvusPassword,
        )
        self.defaultCollection = collection_name or self.settings.defaultCollection

        # 构造子组件
        self.indexer = Indexer(
            self.store,
            adapter,
            default_metric=self.settings.metricType,
            default_index=self.settings.indexType,
        )
        self.retriever = Retriever(
            self.store,
            adapter,
            default_top_k=self.settings.topK,
        )
        self.fusionRetriever = MultiModalFusionRetriever(
            self.retriever,
            modality_weights=self.settings.modalityWeights,
            rrf_k=self.settings.rrfK,
        )

    # ------------------------------------------------------------------
    # 索引
    # ------------------------------------------------------------------

    async def index(
        self,
        collection_name: Optional[str],
        content: Any,
        config: Optional[ChunkConfig] = None,
        *,
        chunks: Optional[list[Chunk]] = None,
        auto_embed: bool = True,
    ) -> tuple[list[Chunk], int]:
        """切片并索引.

        :param collection_name: 集合名，None 使用默认
        :param content: 原始内容（当 chunks 未提供时使用）
        :param config: 切片配置（当 content 提供时需要）
        :param chunks: 预切片结果，提供时跳过切片步骤
        :param auto_embed: 是否自动生成 embedding
        :return: (切片列表, 索引数)
        :raises RAGError: 管道失败
        """
        coll = collection_name or self.defaultCollection

        # 切片
        if chunks is None:
            if config is None:
                raise RAGError("未提供 chunks 时必须提供 config")
            try:
                chunks = await self.chunker.chunk(content, config)
            except Exception as ex:  # noqa: BLE001
                raise RAGError(f"切片失败: {ex}", cause=ex) from ex

        if not chunks:
            return [], 0

        # 索引
        try:
            count = await self.indexer.index(coll, chunks, auto_embed=auto_embed)
        except Exception as ex:  # noqa: BLE001
            raise RAGError(f"索引失败: {ex}", cause=ex) from ex

        return chunks, count

    # ------------------------------------------------------------------
    # 检索
    # ------------------------------------------------------------------

    async def retrieve(
        self,
        collection_name: Optional[str],
        query: str,
        *,
        top_k: Optional[int] = None,
        filter: Optional[str] = None,
        min_score: Optional[float] = None,
    ) -> list[RetrievalResult]:
        """向量检索.

        :param collection_name: 集合名，None 使用默认
        :param query: 查询文本
        :param top_k: topK
        :param filter: 标量过滤
        :param min_score: 分数阈值
        :return: 检索结果列表
        """
        coll = collection_name or self.defaultCollection
        return await self.retriever.retrieve(
            coll,
            query,
            top_k=top_k,
            filter=filter,
            min_score=min_score,
        )

    async def retrieve_fused(
        self,
        collection_name: Optional[str],
        query: str,
        *,
        modalities: Optional[list[Modality | str]] = None,
        top_k: Optional[int] = None,
        method: Optional[str] = None,
        min_score: Optional[float] = None,
    ) -> list[RetrievalResult]:
        """多模态融合检索.

        :param collection_name: 集合名，None 使用默认
        :param query: 查询文本
        :param modalities: 参与融合的模态列表
        :param top_k: topK
        :param method: 融合方法（rrf/weighted）
        :param min_score: 分数阈值
        :return: 融合后的检索结果列表
        """
        coll = collection_name or self.defaultCollection
        return await self.fusionRetriever.retrieve_fused(
            coll,
            query,
            modalities=modalities,
            top_k=top_k or self.settings.topK,
            method=method or self.settings.fusionMethod,
            min_score=min_score,
        )

    # ------------------------------------------------------------------
    # 端到端
    # ------------------------------------------------------------------

    async def index_and_retrieve(
        self,
        content: Any,
        config: ChunkConfig,
        query: str,
        *,
        collection_name: Optional[str] = None,
        top_k: Optional[int] = None,
        fused: bool = False,
        modalities: Optional[list[Modality | str]] = None,
    ) -> list[RetrievalResult]:
        """端到端：切片 -> 索引 -> 检索.

        :param content: 原始内容
        :param config: 切片配置
        :param query: 查询文本
        :param collection_name: 集合名
        :param top_k: topK
        :param fused: 是否使用融合检索
        :param modalities: 融合模态列表
        :return: 检索结果列表
        """
        # 索引
        await self.index(collection_name, content, config)
        # 检索
        if fused:
            return await self.retrieve_fused(
                collection_name,
                query,
                modalities=modalities,
                top_k=top_k,
            )
        return await self.retrieve(collection_name, query, top_k=top_k)

    # ------------------------------------------------------------------
    # 生命周期
    # ------------------------------------------------------------------

    async def close(self) -> None:
        """关闭管道（释放向量存储连接）."""
        await self.store.close()
