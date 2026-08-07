"""RAG 管道异常定义 (T008-6).

异常层次：
    RAGError                       RAG 基础异常
    ├── RAGConfigError             配置错误
    │   └── CollectionNotFoundError 集合不存在
    ├── RAGRuntimeError            运行时错误
    │   ├── VectorStoreError       向量存储操作失败
    │   ├── IndexError             索引（写入）失败
    │   └── RetrieveError          检索失败
    └── EmbeddingMissingError      切片缺少 embedding

对齐设计文档 T008-6。
"""
from __future__ import annotations


class RAGError(Exception):
    """RAG 基础异常.

    所有 rag 模块内抛出的异常均应继承自此基类。
    """

    def __init__(self, message: str = "", *, cause: Exception | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.cause = cause

    def __str__(self) -> str:
        if self.cause is not None:
            return f"{self.message} (cause: {self.cause!r})"
        return self.message


class RAGConfigError(RAGError):
    """RAG 配置错误."""


class CollectionNotFoundError(RAGConfigError):
    """集合不存在."""

    def __init__(self, collection: str) -> None:
        self.collection = collection
        super().__init__(f"向量集合不存在: {collection}")


class CollectionAlreadyExistsError(RAGConfigError):
    """集合已存在."""

    def __init__(self, collection: str) -> None:
        self.collection = collection
        super().__init__(f"向量集合已存在: {collection}")


class RAGRuntimeError(RAGError):
    """RAG 运行时错误."""


class VectorStoreError(RAGRuntimeError):
    """向量存储操作失败."""


class IndexError(RAGRuntimeError):
    """索引（写入）失败."""


class RetrieveError(RAGRuntimeError):
    """检索失败."""


class EmbeddingMissingError(RAGError):
    """切片缺少 embedding.

    当索引器收到的 Chunk 未携带 embedding 且未配置自动生成时抛出。
    """

    def __init__(self, chunk_id: str) -> None:
        self.chunk_id = chunk_id
        super().__init__(f"切片 {chunk_id} 缺少 embedding，且未配置自动生成")