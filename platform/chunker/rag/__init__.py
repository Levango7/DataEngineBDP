"""RAG 管道模块 (T008-6).

提供端到端 RAG（检索增强生成）能力：
    - 向量存储（Milvus / Mock）
    - 索引器（切片 -> embedding -> 入库）
    - 检索器（查询 -> embedding -> 检索）
    - 多模态融合检索（RRF / 加权）
    - 端到端管道（切片 -> 索引 -> 检索）

基于 T008-1~5 多模态切片器框架与 T008-6 embedding 适配器，
对齐设计文档 T008-6。

快速上手：
    from chunker.rag import RAGPipeline, MockVectorStore
    from chunker.embedding import get_adapter
    from chunker import get_chunker

    pipeline = RAGPipeline(
        chunker=get_chunker("text"),
        adapter=get_adapter("bge-large-zh"),
        store=MockVectorStore(),
    )
    await pipeline.index("chunks", "文档内容", config)
    results = await pipeline.retrieve("chunks", "查询")
"""

from __future__ import annotations

from chunker.rag.config import (
    DEFAULT_FUSION_METHOD,
    DEFAULT_INDEX_TYPE,
    DEFAULT_METRIC_TYPE,
    DEFAULT_MODALITY_WEIGHTS,
    DEFAULT_RRF_K,
    DEFAULT_TOP_K,
    RAGSettings,
    get_rag_settings,
    reset_rag_settings,
)
from chunker.rag.exceptions import (
    CollectionAlreadyExistsError,
    CollectionNotFoundError,
    EmbeddingMissingError,
    IndexError,
    RAGConfigError,
    RAGError,
    RAGRuntimeError,
    RetrieveError,
    VectorStoreError,
)
from chunker.rag.fusion import (
    MultiModalFusionRetriever,
    reciprocal_rank_fusion,
    weighted_fusion,
)
from chunker.rag.hybrid_retriever import (
    DEFAULT_BM25_B,
    DEFAULT_BM25_K1,
    DEFAULT_CHANNEL_WEIGHTS,
    DEFAULT_EXPAND_SYNONYMS,
    DEFAULT_RERANK_METHOD,
    BM25Index,
    BM25Retriever,
    CrossEncoderReranker,
    HybridRetrievalResult,
    HybridRetriever,
    IdentityReranker,
    KnowledgeGraph,
    LLMReranker,
    MockKnowledgeGraph,
    Reranker,
    create_reranker,
    tokenize,
)
from chunker.rag.indexer import Indexer
from chunker.rag.pipeline import RAGPipeline
from chunker.rag.retriever import RetrievalResult, Retriever
from chunker.rag.vector_store import (
    INDEX_FLAT,
    INDEX_HNSW,
    INDEX_IVF_FLAT,
    INDEX_IVF_PQ,
    METRIC_COSINE,
    METRIC_IP,
    METRIC_L2,
    CollectionInfo,
    MilvusVectorStore,
    MockVectorStore,
    SearchResult,
    VectorRecord,
    VectorStore,
    create_vector_store,
    is_pymilvus_available,
)

__version__ = "0.1.0"

__all__ = [
    "__version__",
    # 向量存储
    "VectorStore",
    "MockVectorStore",
    "MilvusVectorStore",
    "VectorRecord",
    "SearchResult",
    "CollectionInfo",
    "create_vector_store",
    "is_pymilvus_available",
    # 索引器
    "Indexer",
    # 检索器
    "Retriever",
    "RetrievalResult",
    # 融合检索
    "MultiModalFusionRetriever",
    "reciprocal_rank_fusion",
    "weighted_fusion",
    # 混合检索与重排序 (T009)
    "HybridRetriever",
    "HybridRetrievalResult",
    "BM25Index",
    "BM25Retriever",
    "KnowledgeGraph",
    "MockKnowledgeGraph",
    "Reranker",
    "IdentityReranker",
    "CrossEncoderReranker",
    "LLMReranker",
    "create_reranker",
    "tokenize",
    "DEFAULT_BM25_K1",
    "DEFAULT_BM25_B",
    "DEFAULT_RERANK_METHOD",
    "DEFAULT_CHANNEL_WEIGHTS",
    "DEFAULT_EXPAND_SYNONYMS",
    # 端到端管道
    "RAGPipeline",
    # 配置
    "RAGSettings",
    "get_rag_settings",
    "reset_rag_settings",
    "DEFAULT_METRIC_TYPE",
    "DEFAULT_INDEX_TYPE",
    "DEFAULT_TOP_K",
    "DEFAULT_FUSION_METHOD",
    "DEFAULT_RRF_K",
    "DEFAULT_MODALITY_WEIGHTS",
    # 异常
    "RAGError",
    "RAGConfigError",
    "CollectionNotFoundError",
    "CollectionAlreadyExistsError",
    "RAGRuntimeError",
    "VectorStoreError",
    "IndexError",
    "RetrieveError",
    "EmbeddingMissingError",
    # 度量/索引常量
    "METRIC_L2",
    "METRIC_IP",
    "METRIC_COSINE",
    "INDEX_FLAT",
    "INDEX_IVF_FLAT",
    "INDEX_HNSW",
    "INDEX_IVF_PQ",
]
