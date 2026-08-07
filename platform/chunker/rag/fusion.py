"""多模态融合检索 (T008-6).

支持将多个模态/多个查询的检索结果融合为统一排序：
    - RRF（Reciprocal Rank Fusion）：倒数排名融合
    - Weighted：加权分数融合

特性：
1. **多模态并行检索**：同一查询在多个模态集合并行检索
2. **RRF 融合**：倒数排名融合，无需分数归一化
3. **加权融合**：按模态权重加权分数
4. **去重**：同一 chunkId 在多模态命中时合并
5. **元数据保留**：融合结果携带来源模态信息

对齐设计文档 T008-6。
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, Optional

from chunker.models import Modality
from chunker.rag.config import DEFAULT_MODALITY_WEIGHTS, DEFAULT_RRF_K
from chunker.rag.retriever import RetrievalResult, Retriever

logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------
# 融合方法
# ----------------------------------------------------------------------


def reciprocal_rank_fusion(
    result_lists: list[list[RetrievalResult]],
    k: int = DEFAULT_RRF_K,
) -> list[RetrievalResult]:
    """倒数排名融合（RRF）.

    RRF 公式：score(d) = sum(1 / (k + rank_i(d)))

    :param result_lists: 多个检索结果列表
    :param k: RRF k 参数（默认 60）
    :return: 融合后的结果列表（按 RRF 分数降序）
    """
    if not result_lists:
        return []
    # 累加 RRF 分数
    scores: dict[str, float] = {}
    metas: dict[str, dict[str, Any]] = {}
    for results in result_lists:
        for rank, r in enumerate(results):
            rrf_score = 1.0 / (k + rank + 1)
            scores[r.chunkId] = scores.get(r.chunkId, 0.0) + rrf_score
            if r.chunkId not in metas:
                metas[r.chunkId] = dict(r.metadata)
                metas[r.chunkId]["_sourceScores"] = []
            metas[r.chunkId]["_sourceScores"].append(r.score)

    # 构造融合结果
    fused: list[RetrievalResult] = []
    for chunk_id, score in scores.items():
        meta = dict(metas[chunk_id])
        meta["fusedScore"] = score
        fused.append(RetrievalResult(chunk_id, score, meta))
    fused.sort(key=lambda x: x.score, reverse=True)
    return fused


def weighted_fusion(
    result_lists: list[list[RetrievalResult]],
    weights: list[float],
) -> list[RetrievalResult]:
    """加权分数融合.

    :param result_lists: 多个检索结果列表
    :param weights: 各列表权重（与 result_lists 同长）
    :return: 融合后的结果列表（按加权分数降序）
    """
    if not result_lists:
        return []
    if len(weights) != len(result_lists):
        raise ValueError(
            f"weights 长度 {len(weights)} 与 result_lists 长度 {len(result_lists)} 不匹配"
        )

    scores: dict[str, float] = {}
    metas: dict[str, dict[str, Any]] = {}
    for results, weight in zip(result_lists, weights):
        for r in results:
            scores[r.chunkId] = scores.get(r.chunkId, 0.0) + weight * r.score
            if r.chunkId not in metas:
                metas[r.chunkId] = dict(r.metadata)
                metas[r.chunkId]["_sourceScores"] = []
            metas[r.chunkId]["_sourceScores"].append(r.score)

    fused: list[RetrievalResult] = []
    for chunk_id, score in scores.items():
        meta = dict(metas[chunk_id])
        meta["fusedScore"] = score
        fused.append(RetrievalResult(chunk_id, score, meta))
    fused.sort(key=lambda x: x.score, reverse=True)
    return fused


# ----------------------------------------------------------------------
# 多模态融合检索器
# ----------------------------------------------------------------------


class MultiModalFusionRetriever:
    """多模态融合检索器.

    对同一查询在多个模态（或多个集合）并行检索，融合结果。

    用法::

        fusion = MultiModalFusionRetriever(retriever)
        results = await fusion.retrieve_fused(
            collection_name="chunks",
            query="查询文本",
            modalities=[Modality.TEXT, Modality.TABLE, Modality.IMAGE],
            top_k=10,
            method="rrf",
        )
    """

    def __init__(
        self,
        retriever: Retriever,
        *,
        modality_weights: Optional[dict[str, float]] = None,
        rrf_k: int = DEFAULT_RRF_K,
    ) -> None:
        """初始化融合检索器.

        :param retriever: 基础检索器
        :param modality_weights: 模态权重（仅 weighted 融合使用）
        :param rrf_k: RRF k 参数
        """
        self.retriever = retriever
        self.modalityWeights = dict(modality_weights or DEFAULT_MODALITY_WEIGHTS)
        self.rrfK = rrf_k

    async def retrieve_fused(
        self,
        collection_name: str,
        query: str,
        *,
        modalities: Optional[list[Modality | str]] = None,
        top_k: int = 10,
        method: str = "rrf",
        min_score: Optional[float] = None,
        per_modality_top_k: Optional[int] = None,
    ) -> list[RetrievalResult]:
        """多模态融合检索.

        :param collection_name: 集合名
        :param query: 查询文本
        :param modalities: 参与融合的模态列表，None 表示全部模态
        :param top_k: 最终返回的 topK
        :param method: 融合方法（``"rrf"`` 或 ``"weighted"``）
        :param min_score: 分数阈值
        :param per_modality_top_k: 每个模态检索的 topK，None 表示 2*top_k
        :return: 融合后的检索结果列表
        """
        per_k = per_modality_top_k or (top_k * 2)

        # 确定模态列表
        mods = modalities or list(DEFAULT_MODALITY_WEIGHTS.keys())
        mod_strs = [
            m.value if isinstance(m, Modality) else str(m) for m in mods
        ]

        # 并行检索各模态
        tasks = [
            self.retriever.retrieve_by_modality(
                collection_name,
                query,
                mod_str,
                top_k=per_k,
                min_score=min_score,
            )
            for mod_str in mod_strs
        ]
        result_lists = await asyncio.gather(*tasks, return_exceptions=True)

        # 过滤异常
        valid_lists: list[list[RetrievalResult]] = []
        valid_mods: list[str] = []
        for mod_str, res in zip(mod_strs, result_lists):
            if isinstance(res, Exception):
                logger.warning("模态 %s 检索失败: %s", mod_str, res)
                continue
            valid_lists.append(res)
            valid_mods.append(mod_str)

        if not valid_lists:
            return []

        # 融合
        method_lower = method.lower()
        if method_lower == "rrf":
            fused = reciprocal_rank_fusion(valid_lists, k=self.rrfK)
        elif method_lower == "weighted":
            weights = [self.modalityWeights.get(m, 1.0) for m in valid_mods]
            fused = weighted_fusion(valid_lists, weights)
        else:
            raise ValueError(
                f"未知融合方法: {method}，支持 rrf/weighted"
            )

        # 标记来源模态
        for r in fused:
            r.metadata["fusedMethod"] = method_lower

        return fused[:top_k]

    async def retrieve_multi_query_fused(
        self,
        collection_name: str,
        queries: list[str],
        *,
        modalities: Optional[list[Modality | str]] = None,
        top_k: int = 10,
        method: str = "rrf",
    ) -> list[RetrievalResult]:
        """多查询 + 多模态融合检索.

        对每个查询在各模态检索，所有结果统一融合。

        :param collection_name: 集合名
        :param queries: 查询文本列表
        :param modalities: 模态列表
        :param top_k: topK
        :param method: 融合方法
        :return: 融合后的检索结果列表
        """
        per_k = top_k * 2
        mods = modalities or list(DEFAULT_MODALITY_WEIGHTS.keys())
        mod_strs = [
            m.value if isinstance(m, Modality) else str(m) for m in mods
        ]

        # 并行检索所有 query × modality 组合
        tasks = []
        for q in queries:
            for mod_str in mod_strs:
                tasks.append(
                    self.retriever.retrieve_by_modality(
                        collection_name, q, mod_str, top_k=per_k
                    )
                )
        result_lists = await asyncio.gather(*tasks, return_exceptions=True)

        valid_lists = [
            r for r in result_lists if not isinstance(r, Exception)
        ]
        if not valid_lists:
            return []

        method_lower = method.lower()
        if method_lower == "rrf":
            fused = reciprocal_rank_fusion(valid_lists, k=self.rrfK)
        elif method_lower == "weighted":
            weights = [1.0] * len(valid_lists)
            fused = weighted_fusion(valid_lists, weights)
        else:
            raise ValueError(
                f"未知融合方法: {method}，支持 rrf/weighted"
            )

        return fused[:top_k]