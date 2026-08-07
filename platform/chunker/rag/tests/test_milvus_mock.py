"""Milvus 向量存储 mock 测试 (T008-6).

使用 mock 的 pymilvus 模块测试 MilvusVectorStore 的逻辑，
无需真实 Milvus 服务。
"""
from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from chunker.rag.exceptions import (
    CollectionAlreadyExistsError,
    CollectionNotFoundError,
    VectorStoreError,
)
from chunker.rag.vector_store import (
    MilvusVectorStore,
    VectorRecord,
    is_pymilvus_available,
)


class TestMilvusMock:
    """使用 mock pymilvus 测试 MilvusVectorStore."""

    @pytest.fixture
    def mock_milvus(self):
        """创建 mock pymilvus 模块."""
        mock_module = MagicMock()
        mock_client = MagicMock()
        mock_client.has_collection = MagicMock(return_value=False)
        mock_client.create_collection = MagicMock()
        mock_client.drop_collection = MagicMock()
        mock_client.insert = MagicMock()
        mock_client.search = MagicMock(return_value=[])
        mock_client.delete = MagicMock()
        mock_client.get_collection_stats = MagicMock(return_value={"row_count": 0})
        mock_client.describe_collection = MagicMock(return_value={
            "fields": [{"name": "vector", "params": {"dim": 4}}]
        })
        mock_client.close = MagicMock()
        mock_module.MilvusClient = MagicMock(return_value=mock_client)
        return mock_module, mock_client

    @pytest.mark.asyncio
    async def test_create_collection(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                await store.create_collection("test", 4)
                mock_client.create_collection.assert_called_once()

    @pytest.mark.asyncio
    async def test_create_duplicate(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=True)
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                with pytest.raises(CollectionAlreadyExistsError):
                    await store.create_collection("test", 4)

    @pytest.mark.asyncio
    async def test_drop_collection(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=True)
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                await store.drop_collection("test")
                mock_client.drop_collection.assert_called_once()

    @pytest.mark.asyncio
    async def test_drop_nonexistent(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=False)
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                with pytest.raises(CollectionNotFoundError):
                    await store.drop_collection("test")

    @pytest.mark.asyncio
    async def test_insert(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=True)
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                await store.insert("test", [
                    VectorRecord("a", [1.0, 0.0, 0.0, 0.0]),
                ])
                mock_client.insert.assert_called_once()

    @pytest.mark.asyncio
    async def test_insert_nonexistent(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=False)
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                with pytest.raises(CollectionNotFoundError):
                    await store.insert("test", [VectorRecord("a", [1.0])])

    @pytest.mark.asyncio
    async def test_search(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=True)
        mock_client.search = MagicMock(return_value=[[
            {"id": "a", "distance": 0.9, "entity": {"metadata": {"modality": "text"}}}
        ]])
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                results = await store.search("test", [1.0, 0.0, 0.0, 0.0], top_k=5)
                assert len(results) == 1
                assert results[0].id == "a"

    @pytest.mark.asyncio
    async def test_search_nonexistent(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=False)
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                with pytest.raises(CollectionNotFoundError):
                    await store.search("test", [1.0])

    @pytest.mark.asyncio
    async def test_delete(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=True)
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                await store.delete("test", ["a", "b"])
                mock_client.delete.assert_called_once()

    @pytest.mark.asyncio
    async def test_get_stats(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=True)
        mock_client.get_collection_stats = MagicMock(return_value={
            "row_count": 10,
            "metric_type": "COSINE",
            "index_type": "HNSW",
        })
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                stats = await store.get_stats("test")
                assert stats.vectorCount == 10

    @pytest.mark.asyncio
    async def test_hybrid_search(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        mock_client.has_collection = MagicMock(return_value=True)
        mock_client.search = MagicMock(return_value=[[
            {"id": "a", "distance": 0.9, "entity": {"metadata": {}}}
        ]])
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                results = await store.hybrid_search(
                    "test", [1.0, 0.0, 0.0, 0.0], top_k=5, min_score=0.5
                )
                assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_close(self, mock_milvus):
        mock_module, mock_client = mock_milvus
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=True):
            with patch.dict("sys.modules", {"pymilvus": mock_module}):
                store = MilvusVectorStore()
                store._client = mock_client
                await store.close()
                mock_client.close.assert_called_once()

    @pytest.mark.asyncio
    async def test_not_installed(self):
        with patch("chunker.rag.vector_store.is_pymilvus_available", return_value=False):
            store = MilvusVectorStore()
            with pytest.raises(VectorStoreError):
                await store.create_collection("test", 4)

    @pytest.mark.asyncio
    async def test_close_no_client(self):
        store = MilvusVectorStore()
        await store.close()  # 不应抛异常
