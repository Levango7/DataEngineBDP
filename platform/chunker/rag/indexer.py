"""RAG 索引器 (T008-6).

将多模态切片器输出的 Chunk 列表写入向量存储：
    chunks -> (自动生成 embedding) -> VectorStore.insert

特性：
1. **自动 embedding**：切片未携带 embedding 时，自动调用 EmbeddingAdapter 生成
2. **多模态支持**：文本/表格/图像/语音切片均可索引
3. **元数据保留**：切片 ID、模态、来源、位置信息写入 metadata
4. **批量写入**：支持批量索引，避免单条写入开销
5. **幂等性**：同一切片重复索引时覆盖（按 ID）

对齐设计文档 T008-6。
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, Optional

from chunker.embedding.base import EmbeddingAdapter
from chunker.models import Chunk, Modality
from chunker.rag.exceptions import EmbeddingMissingError, IndexError
from chunker.rag.vector_store import (
    INDEX_HNSW,
    METRIC_COSINE,
    VectorRecord,
    VectorStore,
)

logger = logging.getLogger(__name__)


class Indexer:
    """RAG 索引器.

    将 Chunk 列表写入向量存储，自动生成 embedding（若缺失）。

    用法::

        indexer = Indexer(store, adapter)
        await indexer.ensure_collection("chunks", dimension=1024)
        await indexer.index("chunks", chunks)
    """

    def __init__(
        self,
        store: VectorStore,
        adapter: Optional[EmbeddingAdapter] = None,
        *,
        default_metric: str = METRIC_COSINE,
        default_index: str = INDEX_HNSW,
        batch_size: int = 100,
    ) -> None:
        """初始化索引器.

        :param store: 向量存储
        :param adapter: embedding 适配器，None 表示不自动生成 embedding
        :param default_metric: 默认度量类型
        :param default_index: 默认索引类型
        :param batch_size: 批量写入大小
        """
        self.store = store
        self.adapter = adapter
        self.defaultMetric = default_metric
        self.defaultIndex = default_index
        self.batchSize = batch_size
        # 已创建的集合缓存：name -> dimension
        self._collections: dict[str, int] = {}

    async def ensure_collection(
        self,
        name: str,
        dimension: int,
        metric_type: Optional[str] = None,
        index_type: Optional[str] = None,
    ) -> None:
        """确保集合存在，不存在则创建.

        :param name: 集合名
        :param dimension: 向量维度
        :param metric_type: 度量类型
        :param index_type: 索引类型
        """
        if name in self._collections:
            return
        try:
            await self.store.create_collection(
                name,
                dimension,
                metric_type or self.defaultMetric,
                index_type or self.defaultIndex,
            )
        except Exception:  # noqa: BLE001
            # 集合已存在：忽略
            pass
        self._collections[name] = dimension

    async def index(
        self,
        collection_name: str,
        chunks: list[Chunk],
        *,
        auto_embed: bool = True,
    ) -> int:
        """索引切片列表.

        :param collection_name: 集合名
        :param chunks: 切片列表
        :param auto_embed: 是否自动生成缺失的 embedding
        :return: 成功索引的切片数
        :raises EmbeddingMissingError: 切片缺少 embedding 且未配置自动生成
        :raises IndexError: 索引失败
        """
        if not chunks:
            return 0

        # 准备 embedding
        chunks_with_emb = await self._prepare_embeddings(chunks, auto_embed)

        # 确定维度
        dim = self._infer_dimension(chunks_with_emb)
        if dim > 0:
            await self.ensure_collection(collection_name, dim)

        # 构造 VectorRecord
        records = [
            VectorRecord(
                id=c.id,
                vector=c.embedding,  # type: ignore[arg-type]
                metadata=self._chunk_to_metadata(c),
            )
            for c in chunks_with_emb
            if c.embedding
        ]

        # 批量写入
        total = 0
        for i in range(0, len(records), self.batchSize):
            batch = records[i : i + self.batchSize]
            try:
                await self.store.insert(collection_name, batch)
                total += len(batch)
            except Exception as ex:  # noqa: BLE001
                raise IndexError(
                    f"写入批次 {i // self.batchSize} 失败: {ex}", cause=ex
                ) from ex
        logger.info("索引完成: %d/%d 切片写入 %s", total, len(chunks), collection_name)
        return total

    async def _prepare_embeddings(
        self,
        chunks: list[Chunk],
        auto_embed: bool,
    ) -> list[Chunk]:
        """为缺少 embedding 的切片生成 embedding.

        :param chunks: 切片列表
        :param auto_embed: 是否自动生成
        :return: 带 embedding 的切片列表
        :raises EmbeddingMissingError: 缺少 embedding 且未配置自动生成
        """
        missing = [c for c in chunks if c.embedding is None]
        if not missing:
            return list(chunks)

        if not auto_embed or self.adapter is None:
            # 找第一个缺少 embedding 的切片
            raise EmbeddingMissingError(missing[0].id)

        # 批量生成 embedding
        texts = [self._chunk_to_text(c) for c in missing]
        try:
            vecs = await self.adapter.embed(texts)
        except Exception as ex:  # noqa: BLE001
            raise IndexError(
                f"生成 embedding 失败: {ex}", cause=ex
            ) from ex

        # 填充 embedding
        emb_map = {c.id: v for c, v in zip(missing, vecs)}
        result: list[Chunk] = []
        for c in chunks:
            if c.embedding is not None:
                result.append(c)
            elif c.id in emb_map:
                result.append(c.with_embedding(emb_map[c.id]))
            else:
                result.append(c)
        return result

    @staticmethod
    def _chunk_to_text(chunk: Chunk) -> str:
        """将切片内容转为可嵌入的文本.

        - 文本/代码切片：直接使用 content
        - 表格切片：序列化为文本
        - 图像/语音切片：使用 metadata 中的描述（如 OCR 文本/ASR 文本）

        :param chunk: 切片
        :return: 文本
        """
        content = chunk.content
        if isinstance(content, str):
            return content
        if isinstance(content, (bytes, bytearray)):
            # 二进制内容：使用 metadata 中的描述
            return chunk.metadata.extra.get("text", "") or chunk.metadata.source or ""
        if isinstance(content, dict):
            # 表格/结构化内容：简单序列化
            import json

            try:
                return json.dumps(content, ensure_ascii=False)
            except Exception:  # noqa: BLE001
                return str(content)
        if isinstance(content, list):
            # 表格行列表
            import json

            try:
                return json.dumps(content, ensure_ascii=False)
            except Exception:  # noqa: BLE001
                return str(content)
        return str(content)

    @staticmethod
    def _chunk_to_metadata(chunk: Chunk) -> dict[str, Any]:
        """将切片元数据转为向量存储 metadata.

        :param chunk: 切片
        :return: metadata 字典
        """
        meta = chunk.metadata
        return {
            "chunkId": chunk.id,
            "modality": meta.modality.value if isinstance(meta.modality, Modality) else str(meta.modality),
            "source": meta.source,
            "start": meta.start,
            "end": meta.end,
            "index": meta.index,
            "tokens": chunk.tokens or 0,
            "createdAt": chunk.createdAt.isoformat() if chunk.createdAt else "",
            "extra": meta.extra,
        }

    @staticmethod
    def _infer_dimension(chunks: list[Chunk]) -> int:
        """从切片列表推断向量维度.

        :return: 维度；无法推断返回 0
        """
        for c in chunks:
            if c.embedding:
                return len(c.embedding)
        return 0