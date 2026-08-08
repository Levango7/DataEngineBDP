"""RAG 异常与配置测试 (T008-6)."""

from __future__ import annotations

from chunker.rag.config import (
    DEFAULT_FUSION_METHOD,
    DEFAULT_INDEX_TYPE,
    DEFAULT_METRIC_TYPE,
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
import pytest


class TestExceptions:
    def test_hierarchy(self):
        assert issubclass(RAGConfigError, RAGError)
        assert issubclass(CollectionNotFoundError, RAGConfigError)
        assert issubclass(CollectionAlreadyExistsError, RAGConfigError)
        assert issubclass(RAGRuntimeError, RAGError)
        assert issubclass(VectorStoreError, RAGRuntimeError)
        assert issubclass(IndexError, RAGRuntimeError)
        assert issubclass(RetrieveError, RAGRuntimeError)
        assert issubclass(EmbeddingMissingError, RAGError)

    def test_rag_error_with_cause(self):
        cause = ValueError("inner")
        err = RAGError("outer", cause=cause)
        assert err.message == "outer"
        assert err.cause is cause

    def test_collection_not_found(self):
        err = CollectionNotFoundError("my_coll")
        assert err.collection == "my_coll"
        assert "my_coll" in str(err)

    def test_collection_already_exists(self):
        err = CollectionAlreadyExistsError("my_coll")
        assert err.collection == "my_coll"

    def test_embedding_missing(self):
        err = EmbeddingMissingError("chunk-123")
        assert err.chunk_id == "chunk-123"


class TestConfig:
    def test_defaults(self):
        s = RAGSettings()
        assert s.defaultCollection == "chunks"
        assert s.metricType == DEFAULT_METRIC_TYPE
        assert s.indexType == DEFAULT_INDEX_TYPE
        assert s.topK == DEFAULT_TOP_K
        assert s.fusionMethod == DEFAULT_FUSION_METHOD
        assert s.rrfK == DEFAULT_RRF_K
        assert s.storeType == "mock"

    def test_invalid_metric(self):
        with pytest.raises(Exception):
            RAGSettings(metricType="INVALID")

    def test_invalid_index(self):
        with pytest.raises(Exception):
            RAGSettings(indexType="INVALID")

    def test_invalid_fusion(self):
        with pytest.raises(Exception):
            RAGSettings(fusionMethod="invalid")

    def test_invalid_store_type(self):
        with pytest.raises(Exception):
            RAGSettings(storeType="invalid")

    def test_env_override(self, monkeypatch):
        monkeypatch.setenv("CHUNKER_RAG_TOPK", "20")
        monkeypatch.setenv("CHUNKER_RAG_STORETYPE", "milvus")
        s = RAGSettings()
        assert s.topK == 20
        assert s.storeType == "milvus"

    def test_modality_weights(self):
        s = RAGSettings()
        assert "text" in s.modalityWeights
        assert "image" in s.modalityWeights

    def test_get_settings_cached(self):
        reset_rag_settings()
        s1 = get_rag_settings()
        s2 = get_rag_settings()
        assert s1 is s2

    def test_reset_settings(self):
        s1 = get_rag_settings()
        reset_rag_settings()
        s2 = get_rag_settings()
        assert s1 is not s2
