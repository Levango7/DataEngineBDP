"""混合检索与重排序 (T009).

在 T008-6 单路向量检索与多模态融合基础上，引入：
    1. **多路检索融合**：向量检索 + 关键词检索（BM25）+ 知识图谱检索
    2. **重排序**：cross-encoder / LLM / identity 多种 reranker
    3. **知识图谱增强**：实体链接 -> 子图扩展 -> 查询扩展 -> 邻居切片召回

整体流程::

    query
      ├── [向量检索]  Retriever.retrieve            -> list[RetrievalResult]
      ├── [关键词检索] BM25Retriever.retrieve        -> list[RetrievalResult]
      ├── [KG 增强]
      │     ├── EntityLinker.link(query)             -> list[Entity]
      │     ├── QueryExpander.expand(query, ents)    -> list[str]   # 扩展查询
      │     └── KnowledgeGraph.neighbor_chunks(ents) -> list[RetrievalResult]
      └── 融合 (RRF / weighted) -> 重排序 (reranker) -> top_k

特性：
1. **零外部依赖**：BM25、KG、reranker 均内置 Mock 实现，可独立运行
2. **可插拔**：每一路检索、reranker、KG 均可注入自定义实现
3. **异步并行**：多路检索并行执行
4. **可观测**：返回结果携带来源通道、融合分数、重排序分数
5. **容错**：单路检索失败不阻断整体，仅记录 warning

对齐设计文档 T009。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
import asyncio
from collections import Counter, defaultdict
import logging
import math
import re
from typing import Any, Awaitable, Callable, Optional, Sequence

from chunker.rag.config import DEFAULT_RRF_K, DEFAULT_TOP_K
from chunker.rag.fusion import reciprocal_rank_fusion, weighted_fusion
from chunker.rag.retriever import RetrievalResult, Retriever

logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 默认 BM25 参数 k1
DEFAULT_BM25_K1 = 1.5

#: 默认 BM25 参数 b
DEFAULT_BM25_B = 0.75

#: 默认 BM25 是否小写化
DEFAULT_BM25_LOWERCASE = True

#: 默认重排序方法
DEFAULT_RERANK_METHOD = "identity"  # identity / cross_encoder / llm

#: 默认多路检索权重（vector / keyword / kg）
DEFAULT_CHANNEL_WEIGHTS: dict[str, float] = {
    "vector": 1.0,
    "keyword": 0.8,
    "kg": 0.6,
}

#: 默认查询扩展最大同义词数
DEFAULT_EXPAND_SYNONYMS = 3

#: 默认 BM25 检索 topK 倍数（相对最终 top_k）
DEFAULT_BM25_TOPK_MULTIPLIER = 2

#: 简单英文/中文词切分正则（覆盖大多数场景）
_TOKEN_RE = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fa5]")


# ----------------------------------------------------------------------
# 工具函数
# ----------------------------------------------------------------------


def tokenize(text: str, *, lowercase: bool = True) -> list[str]:
    """轻量分词：英文按单词、中文按字切分.

    :param text: 原始文本
    :param lowercase: 是否小写化
    :return: token 列表
    """
    if not text:
        return []
    tokens = _TOKEN_RE.findall(text)
    if lowercase:
        tokens = [t.lower() for t in tokens]
    return tokens


# ----------------------------------------------------------------------
# BM25 关键词检索
# ----------------------------------------------------------------------


class BM25Index:
    """内存 BM25 倒排索引.

    纯 Python 实现，零外部依赖，适合中小规模语料（< 100k 文档）。
    算法参考 Robertson & Zaragoza, "The Probabilistic Relevance Framework: BM25 and Beyond".
    """

    def __init__(
        self,
        *,
        k1: float = DEFAULT_BM25_K1,
        b: float = DEFAULT_BM25_B,
        lowercase: bool = DEFAULT_BM25_LOWERCASE,
    ) -> None:
        self.k1 = k1
        self.b = b
        self.lowercase = lowercase
        # doc_id -> token Counter
        self._docFreqs: dict[str, Counter[str]] = {}
        # doc_id -> doc length
        self._docLen: dict[str, int] = {}
        # token -> document frequency
        self._df: Counter[str] = Counter()
        # token -> set(doc_id)
        self._postings: dict[str, set[str]] = defaultdict(set)
        self._totalLen = 0
        self._docCount = 0

    @property
    def docCount(self) -> int:
        """已索引文档数."""
        return self._docCount

    @property
    def avgDocLen(self) -> float:
        """平均文档长度."""
        if self._docCount == 0:
            return 0.0
        return self._totalLen / self._docCount

    def add_doc(self, doc_id: str, text: str) -> None:
        """添加/更新文档.

        :param doc_id: 文档 ID
        :param text: 文档文本
        """
        if doc_id in self._docFreqs:
            self.remove_doc(doc_id)
        tokens = tokenize(text, lowercase=self.lowercase)
        freq = Counter(tokens)
        self._docFreqs[doc_id] = freq
        self._docLen[doc_id] = len(tokens)
        self._totalLen += len(tokens)
        self._docCount += 1
        for tok in freq:
            self._df[tok] += 1
            self._postings[tok].add(doc_id)

    def remove_doc(self, doc_id: str) -> None:
        """移除文档."""
        if doc_id not in self._docFreqs:
            return
        freq = self._docFreqs.pop(doc_id)
        self._totalLen -= self._docLen.pop(doc_id, 0)
        self._docCount -= 1
        for tok in freq:
            self._df[tok] -= 1
            if self._df[tok] <= 0:
                del self._df[tok]
            self._postings[tok].discard(doc_id)
            if not self._postings[tok]:
                del self._postings[tok]

    def clear(self) -> None:
        """清空索引."""
        self._docFreqs.clear()
        self._docLen.clear()
        self._df.clear()
        self._postings.clear()
        self._totalLen = 0
        self._docCount = 0

    def search(
        self,
        query: str,
        *,
        top_k: int = 10,
    ) -> list[tuple[str, float]]:
        """BM25 检索.

        :param query: 查询文本
        :param top_k: 返回数
        :return: (doc_id, score) 列表，按分数降序
        """
        if self._docCount == 0 or not query:
            return []
        q_tokens = tokenize(query, lowercase=self.lowercase)
        if not q_tokens:
            return []
        avg_len = self.avgDocLen or 1.0
        scores: dict[str, float] = defaultdict(float)
        for tok in set(q_tokens):
            df = self._df.get(tok, 0)
            if df == 0:
                continue
            idf = math.log(1.0 + (self._docCount - df + 0.5) / (df + 0.5))
            for doc_id in self._postings.get(tok, ()):
                tf = self._docFreqs[doc_id].get(tok, 0)
                dl = self._docLen.get(doc_id, 0)
                denom = tf + self.k1 * (1 - self.b + self.b * dl / avg_len)
                if denom == 0:
                    continue
                scores[doc_id] += idf * (tf * (self.k1 + 1)) / denom
        ranked = sorted(scores.items(), key=lambda x: x[1], reverse=True)
        return ranked[:top_k]


class BM25Retriever:
    """基于 BM25 的关键词检索器.

    与 :class:`Retriever` 接口对齐，输出 :class:`RetrievalResult` 列表，
    可直接喂给融合器。

    用法::

        bm25 = BM25Retriever()
        bm25.add_doc("c1", "知识图谱增强检索")
        results = await bm25.retrieve("知识图谱", top_k=5)
    """

    def __init__(
        self,
        index: Optional[BM25Index] = None,
        *,
        metadata_store: Optional[dict[str, dict[str, Any]]] = None,
    ) -> None:
        """初始化.

        :param index: BM25 索引，None 则自建
        :param metadata_store: doc_id -> metadata 映射，用于回填检索结果
        """
        self.index = index or BM25Index()
        # doc_id -> metadata
        self._metaStore: dict[str, dict[str, Any]] = metadata_store if metadata_store is not None else {}

    def add_doc(
        self,
        doc_id: str,
        text: str,
        metadata: Optional[dict[str, Any]] = None,
    ) -> None:
        """添加文档.

        :param doc_id: 文档 ID（通常为 chunkId）
        :param text: 文档文本
        :param metadata: 元数据
        """
        self.index.add_doc(doc_id, text)
        if metadata is not None:
            self._metaStore[doc_id] = dict(metadata)

    def add_chunks(self, chunks: Sequence[Any]) -> int:
        """批量添加切片.

        :param chunks: Chunk 列表（需有 id/content/metadata）
        :return: 添加数
        """
        count = 0
        for c in chunks:
            text = _chunk_to_text(c)
            meta = _chunk_to_meta(c)
            self.add_doc(c.id, text, meta)
            count += 1
        return count

    def remove_doc(self, doc_id: str) -> None:
        """移除文档."""
        self.index.remove_doc(doc_id)
        self._metaStore.pop(doc_id, None)

    def clear(self) -> None:
        """清空."""
        self.index.clear()
        self._metaStore.clear()

    async def retrieve(
        self,
        collection_name: str,  # noqa: ARG002 保留接口对齐
        query: str,
        *,
        top_k: int = 10,
    ) -> list[RetrievalResult]:
        """BM25 检索.

        :param collection_name: 集合名（BM25 不区分集合，仅用于接口对齐）
        :param query: 查询文本
        :param top_k: topK
        :return: 检索结果列表
        """
        hits = self.index.search(query, top_k=top_k)
        return [
            RetrievalResult(
                doc_id,
                score,
                dict(self._metaStore.get(doc_id, {})),
            )
            for doc_id, score in hits
        ]


# ----------------------------------------------------------------------
# 知识图谱抽象与 Mock 实现
# ----------------------------------------------------------------------


class KnowledgeGraph(ABC):
    """知识图谱抽象接口.

    用于 RAG 的 KG 增强：
        - 实体链接：query -> 命中实体
        - 邻居切片召回：实体 -> 关联 chunkId 列表
        - 同义词/别名扩展：实体 -> 别名列表

    实现方需保证线程安全。
    """

    @abstractmethod
    async def link_entities(
        self,
        query: str,
        *,
        top_k: int = 5,
    ) -> list[dict[str, Any]]:
        """从查询中链接实体.

        :param query: 查询文本
        :param top_k: 最多返回实体数
        :return: 实体列表，每项至少包含 ``{"id", "label", "aliases"}``
        """
        ...

    @abstractmethod
    async def neighbor_chunks(
        self,
        entities: list[dict[str, Any]],
        *,
        top_k: int = 20,
    ) -> list[str]:
        """根据实体召回关联 chunkId.

        :param entities: 实体列表
        :param top_k: 最多返回 chunkId 数
        :return: chunkId 列表
        """
        ...

    @abstractmethod
    async def expand_query(
        self,
        query: str,
        entities: list[dict[str, Any]],
        *,
        max_synonyms: int = DEFAULT_EXPAND_SYNONYMS,
    ) -> list[str]:
        """基于实体别名扩展查询.

        :param query: 原始查询
        :param entities: 链接到的实体
        :param max_synonyms: 每个实体最多取多少别名
        :return: 扩展查询列表（不含原始查询）
        """
        ...


class MockKnowledgeGraph(KnowledgeGraph):
    """内存知识图谱实现.

    用于单元测试与无外部依赖场景。结构::

        entities: {
            entity_id: {
                "id": entity_id,
                "label": "Person",
                "name": "张三",
                "aliases": ["老张", "Zhang San"],
                "chunkIds": ["c1", "c2"],   # 该实体关联的切片
            }
        }

    实体链接策略：在 query 中查找实体 name 或 aliases 的子串匹配。
    """

    def __init__(self) -> None:
        self._entities: dict[str, dict[str, Any]] = {}
        # name/alias -> entity_id（用于链接）
        self._aliasIndex: dict[str, str] = {}

    def add_entity(
        self,
        entity_id: str,
        *,
        label: str = "Entity",
        name: str = "",
        aliases: Optional[list[str]] = None,
        chunk_ids: Optional[list[str]] = None,
        properties: Optional[dict[str, Any]] = None,
    ) -> None:
        """添加/更新实体.

        :param entity_id: 实体 ID
        :param label: 实体标签
        :param name: 实体主名
        :param aliases: 别名列表
        :param chunk_ids: 关联切片 ID 列表
        :param properties: 额外属性
        """
        aliases = list(aliases or [])
        chunk_ids = list(chunk_ids or [])
        ent = {
            "id": entity_id,
            "label": label,
            "name": name,
            "aliases": aliases,
            "chunkIds": chunk_ids,
            "properties": dict(properties or {}),
        }
        # 移除旧别名索引
        old = self._entities.get(entity_id)
        if old:
            for alias in [old.get("name", "")] + old.get("aliases", []):
                if alias and self._aliasIndex.get(alias) == entity_id:
                    self._aliasIndex.pop(alias, None)
        self._entities[entity_id] = ent
        # 建立别名索引
        for alias in [name] + aliases:
            if alias:
                self._aliasIndex[alias] = entity_id

    def remove_entity(self, entity_id: str) -> None:
        """移除实体."""
        ent = self._entities.pop(entity_id, None)
        if not ent:
            return
        for alias in [ent.get("name", "")] + ent.get("aliases", []):
            if alias and self._aliasIndex.get(alias) == entity_id:
                self._aliasIndex.pop(alias, None)

    def clear(self) -> None:
        """清空."""
        self._entities.clear()
        self._aliasIndex.clear()

    @property
    def entityCount(self) -> int:
        """实体数."""
        return len(self._entities)

    async def link_entities(
        self,
        query: str,
        *,
        top_k: int = 5,
    ) -> list[dict[str, Any]]:
        if not query:
            return []
        hits: list[dict[str, Any]] = []
        seen: set[str] = set()
        # 按别名长度降序匹配，优先长别名（更具体）
        for alias, ent_id in sorted(self._aliasIndex.items(), key=lambda x: len(x[0]), reverse=True):
            if ent_id in seen:
                continue
            if alias and alias in query:
                ent = self._entities.get(ent_id)
                if ent:
                    hits.append(dict(ent))
                    seen.add(ent_id)
            if len(hits) >= top_k:
                break
        return hits

    async def neighbor_chunks(
        self,
        entities: list[dict[str, Any]],
        *,
        top_k: int = 20,
    ) -> list[str]:
        chunk_ids: list[str] = []
        seen: set[str] = set()
        for ent in entities:
            for cid in ent.get("chunkIds", []):
                if cid not in seen:
                    chunk_ids.append(cid)
                    seen.add(cid)
                if len(chunk_ids) >= top_k:
                    return chunk_ids
        return chunk_ids

    async def expand_query(
        self,
        query: str,
        entities: list[dict[str, Any]],
        *,
        max_synonyms: int = DEFAULT_EXPAND_SYNONYMS,
    ) -> list[str]:
        expansions: list[str] = []
        for ent in entities:
            aliases = ent.get("aliases", [])[:max_synonyms]
            name = ent.get("name", "")
            for alias in aliases:
                if alias and alias != name and alias not in query:
                    expansions.append(alias)
        return expansions


# ----------------------------------------------------------------------
# 重排序器
# ----------------------------------------------------------------------


class Reranker(ABC):
    """重排序器抽象接口.

    输入 :class:`RetrievalResult` 列表与查询，输出按相关性重排后的列表。
    """

    @abstractmethod
    async def rerank(
        self,
        query: str,
        results: list[RetrievalResult],
        *,
        top_k: Optional[int] = None,
    ) -> list[RetrievalResult]:
        """重排序.

        :param query: 查询文本
        :param results: 待重排结果列表
        :param top_k: 返回数，None 表示全部
        :return: 重排后的结果列表
        """
        ...

    @property
    @abstractmethod
    def name(self) -> str:
        """reranker 名称."""


class IdentityReranker(Reranker):
    """恒等重排序器（不改变顺序，仅截断 top_k）.

    用于关闭重排序或基线对比。
    """

    async def rerank(
        self,
        query: str,  # noqa: ARG002
        results: list[RetrievalResult],
        *,
        top_k: Optional[int] = None,
    ) -> list[RetrievalResult]:
        if top_k is None:
            return list(results)
        return list(results[:top_k])

    @property
    def name(self) -> str:
        return "identity"


class CrossEncoderReranker(Reranker):
    """Cross-encoder 重排序器.

    通过外部打分函数对 (query, doc) 对打分，按分数重排。
    打分函数 ``score_fn(query, doc_text) -> float`` 可注入：
        - 真实 cross-encoder 模型（如 sentence-transformers）
        - LLM 打分（如让 LLM 输出 0-1 相关性分数）
        - 任意自定义函数

    用法::

        async def score_fn(q, doc):
            return await model.predict(q, doc)
        reranker = CrossEncoderReranker(score_fn)
        reranked = await reranker.rerank(query, results, top_k=5)
    """

    def __init__(
        self,
        score_fn: Callable[[str, str], Awaitable[float]],
        *,
        doc_text_key: str = "content",
        normalize: bool = False,
    ) -> None:
        """初始化.

        :param score_fn: 异步打分函数 (query, doc_text) -> score
        :param doc_text_key: 从 metadata 取文档文本的键
        :param normalize: 是否对分数做 min-max 归一化
        """
        self._scoreFn = score_fn
        self._docTextKey = doc_text_key
        self._normalize = normalize

    async def rerank(
        self,
        query: str,
        results: list[RetrievalResult],
        *,
        top_k: Optional[int] = None,
    ) -> list[RetrievalResult]:
        if not results:
            return []

        # 并行打分
        async def _score(r: RetrievalResult) -> tuple[float, RetrievalResult]:
            doc_text = str(r.metadata.get(self._docTextKey, ""))
            try:
                s = await self._scoreFn(query, doc_text)
            except Exception as ex:  # noqa: BLE001
                logger.warning("cross-encoder 打分失败 %s: %s", r.chunkId, ex)
                s = r.score  # 回退到原分数
            return s, r

        scored = await asyncio.gather(*[_score(r) for r in results])
        # 归一化
        if self._normalize and scored:
            raw = [s for s, _ in scored]
            min_s, max_s = min(raw), max(raw)
            span = max_s - min_s
            if span > 0:
                scored = [((s - min_s) / span, r) for s, r in scored]
        # 排序
        scored.sort(key=lambda x: x[0], reverse=True)
        out: list[RetrievalResult] = []
        for s, r in scored:
            new_meta = dict(r.metadata)
            new_meta["rerankScore"] = s
            new_meta["reranker"] = "cross_encoder"
            out.append(RetrievalResult(r.chunkId, s, new_meta))
        if top_k is not None:
            out = out[:top_k]
        return out

    @property
    def name(self) -> str:
        return "cross_encoder"


class LLMReranker(Reranker):
    """LLM 重排序器.

    通过 LLM 对候选结果打分/排序。``llm_score_fn`` 注入 LLM 调用：
        llm_score_fn(query, candidates) -> list[float]

    其中 candidates 为 ``list[dict]``，每项含 ``{"id", "text"}``，
    返回与 candidates 同长的分数列表。

    用法::

        async def llm_fn(q, cands):
            return await llm.invoke(prompt(q, cands))
        reranker = LLMReranker(llm_fn)
        reranked = await reranker.rerank(query, results, top_k=5)
    """

    def __init__(
        self,
        llm_score_fn: Callable[[str, list[dict[str, str]]], Awaitable[list[float]]],
        *,
        doc_text_key: str = "content",
        batch_size: int = 20,
    ) -> None:
        """初始化.

        :param llm_score_fn: LLM 批量打分函数
        :param doc_text_key: 从 metadata 取文档文本的键
        :param batch_size: 单次 LLM 调用最大候选数
        """
        self._llmFn = llm_score_fn
        self._docTextKey = doc_text_key
        self._batchSize = batch_size

    async def rerank(
        self,
        query: str,
        results: list[RetrievalResult],
        *,
        top_k: Optional[int] = None,
    ) -> list[RetrievalResult]:
        if not results:
            return []
        # 分批调用 LLM
        scored: list[tuple[float, RetrievalResult]] = []
        for i in range(0, len(results), self._batchSize):
            batch = results[i : i + self._batchSize]
            cands = [
                {
                    "id": r.chunkId,
                    "text": str(r.metadata.get(self._docTextKey, "")),
                }
                for r in batch
            ]
            try:
                scores = await self._llmFn(query, cands)
            except Exception as ex:  # noqa: BLE001
                logger.warning("LLM 打分失败: %s", ex)
                scores = [r.score for r in batch]
            if len(scores) != len(batch):
                logger.warning(
                    "LLM 返回分数数 %d 与候选数 %d 不符，回退原分数",
                    len(scores),
                    len(batch),
                )
                scores = [r.score for r in batch]
            scored.extend(zip(scores, batch))

        scored.sort(key=lambda x: x[0], reverse=True)
        out: list[RetrievalResult] = []
        for s, r in scored:
            new_meta = dict(r.metadata)
            new_meta["rerankScore"] = s
            new_meta["reranker"] = "llm"
            out.append(RetrievalResult(r.chunkId, s, new_meta))
        if top_k is not None:
            out = out[:top_k]
        return out

    @property
    def name(self) -> str:
        return "llm"


def create_reranker(
    method: str,
    *,
    score_fn: Optional[Callable] = None,
    llm_score_fn: Optional[Callable] = None,
    **kwargs: Any,
) -> Reranker:
    """工厂函数：按方法名创建 reranker.

    :param method: ``"identity"`` / ``"cross_encoder"`` / ``"llm"``
    :param score_fn: cross-encoder 打分函数
    :param llm_score_fn: LLM 打分函数
    :param kwargs: 透传给 reranker 构造函数
    :return: Reranker 实例
    :raises ValueError: 未知方法或缺少必要参数
    """
    m = method.lower()
    if m == "identity":
        return IdentityReranker()
    if m == "cross_encoder":
        if score_fn is None:
            raise ValueError("cross_encoder reranker 需要 score_fn")
        return CrossEncoderReranker(score_fn, **kwargs)  # type: ignore[arg-type]
    if m == "llm":
        if llm_score_fn is None:
            raise ValueError("llm reranker 需要 llm_score_fn")
        return LLMReranker(llm_score_fn, **kwargs)  # type: ignore[arg-type]
    raise ValueError(f"未知重排序方法: {method}，支持 identity/cross_encoder/llm")


# ----------------------------------------------------------------------
# 混合检索结果
# ----------------------------------------------------------------------


class HybridRetrievalResult:
    """混合检索结果.

    封装最终结果及各通道中间结果，便于可观测与调试。
    """

    def __init__(
        self,
        results: list[RetrievalResult],
        *,
        channel_results: Optional[dict[str, list[RetrievalResult]]] = None,
        expanded_queries: Optional[list[str]] = None,
        linked_entities: Optional[list[dict[str, Any]]] = None,
        fused_method: str = "rrf",
        reranker_name: str = "identity",
    ) -> None:
        self.results = results
        self.channelResults = channel_results or {}
        self.expandedQueries = expanded_queries or []
        self.linkedEntities = linked_entities or []
        self.fusedMethod = fused_method
        self.rerankerName = reranker_name

    @property
    def topK(self) -> int:
        """结果数."""
        return len(self.results)

    def to_dict(self) -> dict[str, Any]:
        """转为字典."""
        return {
            "results": [r.to_dict() for r in self.results],
            "channelResults": {k: [r.to_dict() for r in v] for k, v in self.channelResults.items()},
            "expandedQueries": self.expandedQueries,
            "linkedEntities": self.linkedEntities,
            "fusedMethod": self.fusedMethod,
            "rerankerName": self.rerankerName,
        }

    def __iter__(self):
        return iter(self.results)

    def __len__(self) -> int:
        return len(self.results)


# ----------------------------------------------------------------------
# 混合检索器
# ----------------------------------------------------------------------


class HybridRetriever:
    """混合检索器：多路检索 + 融合 + 重排序 + KG 增强.

    用法::

        hybrid = HybridRetriever(
            retriever=Retriever(store, adapter),
            bm25=BM25Retriever(),
            kg=MockKnowledgeGraph(),
            reranker=IdentityReranker(),
        )
        result = await hybrid.retrieve(
            "chunks", "查询",
            top_k=10,
            channels=["vector", "keyword", "kg"],
            method="rrf",
            rerank=True,
        )
        for r in result:
            print(r.chunkId, r.score)
    """

    def __init__(
        self,
        retriever: Retriever,
        *,
        bm25: Optional[BM25Retriever] = None,
        kg: Optional[KnowledgeGraph] = None,
        reranker: Optional[Reranker] = None,
        channel_weights: Optional[dict[str, float]] = None,
        rrf_k: int = DEFAULT_RRF_K,
        enable_query_expansion: bool = True,
        max_synonyms: int = DEFAULT_EXPAND_SYNONYMS,
    ) -> None:
        """初始化.

        :param retriever: 向量检索器（必填）
        :param bm25: BM25 关键词检索器，None 表示禁用 keyword 通道
        :param kg: 知识图谱，None 表示禁用 kg 通道
        :param reranker: 重排序器，None 表示不重排序
        :param channel_weights: 各通道权重（仅 weighted 融合使用）
        :param rrf_k: RRF k 参数
        :param enable_query_expansion: 是否启用 KG 查询扩展
        :param max_synonyms: 查询扩展每实体最大同义词数
        """
        self.retriever = retriever
        self.bm25 = bm25
        self.kg = kg
        self.reranker = reranker or IdentityReranker()
        self.channelWeights = dict(channel_weights or DEFAULT_CHANNEL_WEIGHTS)
        self.rrfK = rrf_k
        self.enableQueryExpansion = enable_query_expansion
        self.maxSynonyms = max_synonyms

    # ------------------------------------------------------------------
    # 公共接口
    # ------------------------------------------------------------------

    async def retrieve(
        self,
        collection_name: str,
        query: str,
        *,
        top_k: int = DEFAULT_TOP_K,
        channels: Optional[Sequence[str]] = None,
        method: str = "rrf",
        rerank: bool = True,
        min_score: Optional[float] = None,
        filter: Optional[str] = None,
    ) -> HybridRetrievalResult:
        """混合检索.

        :param collection_name: 向量集合名
        :param query: 查询文本
        :param top_k: 最终返回 topK
        :param channels: 参与通道列表，None 表示全部可用通道
            支持 ``"vector"`` / ``"keyword"`` / ``"kg"``
        :param method: 融合方法（``"rrf"`` / ``"weighted"``）
        :param rerank: 是否对融合结果重排序
        :param min_score: 最低分数阈值（融合后过滤）
        :param filter: 向量检索标量过滤
        :return: :class:`HybridRetrievalResult`
        """
        if not query:
            return HybridRetrievalResult([])

        # 确定通道
        active = self._resolve_channels(channels)
        if not active:
            return HybridRetrievalResult([])

        # KG 增强：实体链接 + 查询扩展
        linked_entities: list[dict[str, Any]] = []
        expanded_queries: list[str] = []
        if "kg" in active and self.kg is not None:
            try:
                linked_entities = await self.kg.link_entities(query, top_k=5)
                if self.enableQueryExpansion and linked_entities:
                    expanded_queries = await self.kg.expand_query(
                        query,
                        linked_entities,
                        max_synonyms=self.maxSynonyms,
                    )
            except Exception as ex:  # noqa: BLE001
                logger.warning("KG 实体链接失败: %s", ex)

        # 并行执行各通道检索
        per_k = top_k * 2  # 每路多召回，融合后截断
        tasks: list[Awaitable[list[RetrievalResult]]] = []
        task_channels: list[str] = []
        for ch in active:
            if ch == "vector":
                tasks.append(self._retrieve_vector(collection_name, query, per_k, filter, expanded_queries))
                task_channels.append(ch)
            elif ch == "keyword":
                if self.bm25 is not None:
                    tasks.append(self._retrieve_keyword(query, per_k, expanded_queries))
                    task_channels.append(ch)
            elif ch == "kg":
                if self.kg is not None:
                    tasks.append(self._retrieve_kg(collection_name, query, per_k, linked_entities))
                    task_channels.append(ch)

        if not tasks:
            return HybridRetrievalResult([])

        raw_results = await asyncio.gather(*tasks, return_exceptions=True)

        # 过滤异常 + 标记通道
        channel_results: dict[str, list[RetrievalResult]] = {}
        valid_lists: list[list[RetrievalResult]] = []
        valid_channels: list[str] = []
        for ch, res in zip(task_channels, raw_results):
            if isinstance(res, Exception):
                logger.warning("通道 %s 检索失败: %s", ch, res)
                continue
            # 标记来源通道
            tagged = []
            for r in res:
                meta = dict(r.metadata)
                meta["channel"] = ch
                tagged.append(RetrievalResult(r.chunkId, r.score, meta))
            channel_results[ch] = tagged
            valid_lists.append(tagged)
            valid_channels.append(ch)

        if not valid_lists:
            return HybridRetrievalResult(
                [],
                channel_results=channel_results,
                expanded_queries=expanded_queries,
                linked_entities=linked_entities,
            )

        # 融合
        method_lower = method.lower()
        if method_lower == "rrf":
            fused = reciprocal_rank_fusion(valid_lists, k=self.rrfK)
            fused_method = "rrf"
        elif method_lower == "weighted":
            weights = [self.channelWeights.get(c, 1.0) for c in valid_channels]
            fused = weighted_fusion(valid_lists, weights)
            fused_method = "weighted"
        else:
            raise ValueError(f"未知融合方法: {method}，支持 rrf/weighted")

        # 标记融合方法
        for r in fused:
            r.metadata["fusedMethod"] = fused_method

        # 分数阈值过滤
        if min_score is not None:
            fused = [r for r in fused if r.score >= min_score]

        # 截断到 top_k * 2 给 reranker 留余量
        fused = fused[: top_k * 2]

        # 重排序
        reranker_name = self.reranker.name
        if rerank:
            try:
                fused = await self.reranker.rerank(query, fused, top_k=top_k)
            except Exception as ex:  # noqa: BLE001
                logger.warning("重排序失败，使用融合结果: %s", ex)
                fused = fused[:top_k]
        else:
            fused = fused[:top_k]

        return HybridRetrievalResult(
            fused,
            channel_results=channel_results,
            expanded_queries=expanded_queries,
            linked_entities=linked_entities,
            fused_method=fused_method,
            reranker_name=reranker_name,
        )

    async def retrieve_multi(
        self,
        collection_name: str,
        queries: list[str],
        *,
        top_k: int = DEFAULT_TOP_K,
        channels: Optional[Sequence[str]] = None,
        method: str = "rrf",
        rerank: bool = True,
    ) -> list[HybridRetrievalResult]:
        """多查询混合检索.

        :param collection_name: 集合名
        :param queries: 查询列表
        :param top_k: topK
        :param channels: 通道列表
        :param method: 融合方法
        :param rerank: 是否重排序
        :return: 每个查询的混合检索结果
        """
        tasks = [
            self.retrieve(
                collection_name,
                q,
                top_k=top_k,
                channels=channels,
                method=method,
                rerank=rerank,
            )
            for q in queries
        ]
        return await asyncio.gather(*tasks)

    # ------------------------------------------------------------------
    # 内部方法
    # ------------------------------------------------------------------

    def _resolve_channels(self, channels: Optional[Sequence[str]]) -> list[str]:
        """解析有效通道列表."""
        if channels is None:
            # 默认全部可用通道
            result = ["vector"]
            if self.bm25 is not None:
                result.append("keyword")
            if self.kg is not None:
                result.append("kg")
            return result
        # 过滤未配置的通道
        valid: list[str] = []
        for ch in channels:
            if ch == "vector":
                valid.append(ch)
            elif ch == "keyword" and self.bm25 is not None:
                valid.append(ch)
            elif ch == "kg" and self.kg is not None:
                valid.append(ch)
            else:
                logger.warning("通道 %s 未配置或未知，已忽略", ch)
        return valid

    async def _retrieve_vector(
        self,
        collection_name: str,
        query: str,
        top_k: int,
        filter: Optional[str],
        expanded_queries: list[str],
    ) -> list[RetrievalResult]:
        """向量检索通道（含扩展查询合并）."""
        all_results: list[RetrievalResult] = []
        # 原始查询
        try:
            results = await self.retriever.retrieve(collection_name, query, top_k=top_k, filter=filter)
            all_results.extend(results)
        except Exception as ex:  # noqa: BLE001
            logger.warning("向量检索原始查询失败: %s", ex)
        # 扩展查询
        for eq in expanded_queries:
            try:
                results = await self.retriever.retrieve(collection_name, eq, top_k=top_k, filter=filter)
                all_results.extend(results)
            except Exception as ex:  # noqa: BLE001
                logger.warning("向量检索扩展查询 '%s' 失败: %s", eq, ex)
        # 去重（按 chunkId 保留首个）
        seen: set[str] = set()
        deduped: list[RetrievalResult] = []
        for r in all_results:
            if r.chunkId not in seen:
                seen.add(r.chunkId)
                deduped.append(r)
        return deduped[:top_k]

    async def _retrieve_keyword(
        self,
        query: str,
        top_k: int,
        expanded_queries: list[str],
    ) -> list[RetrievalResult]:
        """关键词检索通道（含扩展查询合并）."""
        assert self.bm25 is not None
        all_results: list[RetrievalResult] = []
        # 原始查询
        results = await self.bm25.retrieve("", query, top_k=top_k)
        all_results.extend(results)
        # 扩展查询
        for eq in expanded_queries:
            results = await self.bm25.retrieve("", eq, top_k=top_k)
            all_results.extend(results)
        # 去重
        seen: set[str] = set()
        deduped: list[RetrievalResult] = []
        for r in all_results:
            if r.chunkId not in seen:
                seen.add(r.chunkId)
                deduped.append(r)
        return deduped[:top_k]

    async def _retrieve_kg(
        self,
        collection_name: str,
        query: str,  # noqa: ARG002 保留以备未来用 query 直接检索
        top_k: int,
        linked_entities: list[dict[str, Any]],
    ) -> list[RetrievalResult]:
        """KG 检索通道：通过实体邻居切片召回.

        将邻居 chunkId 转为 :class:`RetrievalResult`，分数按召回序递减。
        若配置了向量检索器，可二次向量化邻居切片以获取真实相似度；
        此处采用轻量策略：按邻居排名给衰减分数。
        """
        assert self.kg is not None
        chunk_ids = await self.kg.neighbor_chunks(linked_entities, top_k=top_k)
        if not chunk_ids:
            return []
        # 按排名给衰减分数（首位 1.0，依次衰减）
        results: list[RetrievalResult] = []
        for i, cid in enumerate(chunk_ids):
            score = 1.0 / (1.0 + i)  # 1.0, 0.5, 0.333, ...
            results.append(
                RetrievalResult(
                    cid,
                    score,
                    {"kgSource": True, "kgRank": i},
                )
            )
        return results


# ----------------------------------------------------------------------
# 辅助函数
# ----------------------------------------------------------------------


def _chunk_to_text(chunk: Any) -> str:
    """将切片内容转为文本（与 indexer._chunk_to_text 对齐）."""
    content = getattr(chunk, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, (bytes, bytearray)):
        meta = getattr(chunk, "metadata", None)
        extra = getattr(meta, "extra", {}) if meta else {}
        return extra.get("text", "") or (getattr(meta, "source", "") if meta else "")
    if isinstance(content, (dict, list)):
        import json

        try:
            return json.dumps(content, ensure_ascii=False)
        except Exception:  # noqa: BLE001
            return str(content)
    return str(content)


def _chunk_to_meta(chunk: Any) -> dict[str, Any]:
    """将切片元数据转为 dict."""
    meta = getattr(chunk, "metadata", None)
    if meta is None:
        return {}
    result: dict[str, Any] = {
        "modality": getattr(meta, "modality", "text"),
        "source": getattr(meta, "source", ""),
        "start": getattr(meta, "start", 0),
        "end": getattr(meta, "end", 0),
        "index": getattr(meta, "index", 0),
    }
    # modality 可能是 Enum
    mod = result["modality"]
    if hasattr(mod, "value"):
        result["modality"] = mod.value
    extra = getattr(meta, "extra", {})
    if extra:
        result["extra"] = dict(extra)
    return result


__all__ = [
    # 常量
    "DEFAULT_BM25_K1",
    "DEFAULT_BM25_B",
    "DEFAULT_BM25_LOWERCASE",
    "DEFAULT_RERANK_METHOD",
    "DEFAULT_CHANNEL_WEIGHTS",
    "DEFAULT_EXPAND_SYNONYMS",
    "DEFAULT_BM25_TOPK_MULTIPLIER",
    # 工具
    "tokenize",
    # BM25
    "BM25Index",
    "BM25Retriever",
    # 知识图谱
    "KnowledgeGraph",
    "MockKnowledgeGraph",
    # 重排序
    "Reranker",
    "IdentityReranker",
    "CrossEncoderReranker",
    "LLMReranker",
    "create_reranker",
    # 混合检索
    "HybridRetrievalResult",
    "HybridRetriever",
]
