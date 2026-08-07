"""Embedding 异常与配置测试 (T008-6)."""
from __future__ import annotations

import pytest

from chunker.embedding.config import (
    DEFAULT_MODEL,
    MODEL_DIMENSIONS,
    SUPPORTED_MODELS,
    EmbeddingSettings,
    get_embedding_settings,
    get_model_dimension,
    model_short_name,
    resolve_model_name,
    reset_embedding_settings,
    should_normalize,
)
from chunker.embedding.exceptions import (
    EmbeddingComputeError,
    EmbeddingConfigError,
    EmbeddingDimensionError,
    EmbeddingError,
    EmbeddingRuntimeError,
    InvalidModelError,
    ModelLoadError,
    ModelUnavailableError,
)


# ----------------------------------------------------------------------
# 异常层次
# ----------------------------------------------------------------------


class TestExceptions:
    def test_hierarchy(self):
        assert issubclass(EmbeddingConfigError, EmbeddingError)
        assert issubclass(InvalidModelError, EmbeddingConfigError)
        assert issubclass(EmbeddingRuntimeError, EmbeddingError)
        assert issubclass(ModelLoadError, EmbeddingRuntimeError)
        assert issubclass(EmbeddingComputeError, EmbeddingRuntimeError)
        assert issubclass(ModelUnavailableError, EmbeddingRuntimeError)
        assert issubclass(EmbeddingDimensionError, EmbeddingError)

    def test_embedding_error_with_cause(self):
        cause = ValueError("inner")
        err = EmbeddingError("outer", cause=cause)
        assert err.message == "outer"
        assert err.cause is cause
        assert "cause" in str(err)

    def test_embedding_error_no_cause(self):
        err = EmbeddingError("simple")
        assert str(err) == "simple"

    def test_invalid_model_error(self):
        err = InvalidModelError("foo", available=["bge-large-zh"])
        assert err.model == "foo"
        assert "bge-large-zh" in err.available

    def test_model_unavailable_error(self):
        err = ModelUnavailableError("bge", "未安装")
        assert err.model == "bge"
        assert "未安装" in str(err)

    def test_dimension_error(self):
        err = EmbeddingDimensionError(1024, 768)
        assert err.expected == 1024
        assert err.actual == 768


# ----------------------------------------------------------------------
# 配置
# ----------------------------------------------------------------------


class TestConfig:
    def test_resolve_model_short_name(self):
        assert resolve_model_name("bge-large-zh") == "BAAI/bge-large-zh"
        assert resolve_model_name("m3e-base") == "moka-ai/m3e-base"
        assert resolve_model_name("openai") == "text-embedding-3-small"

    def test_resolve_model_full_name(self):
        assert resolve_model_name("BAAI/bge-large-zh") == "BAAI/bge-large-zh"

    def test_resolve_model_hf_path_passthrough(self):
        assert resolve_model_name("custom-org/custom-model") == "custom-org/custom-model"

    def test_resolve_model_invalid(self):
        with pytest.raises(InvalidModelError):
            resolve_model_name("nonexistent-model")

    def test_resolve_model_empty(self):
        with pytest.raises(InvalidModelError):
            resolve_model_name("")

    def test_model_short_name(self):
        assert model_short_name("bge-large-zh") == "bge-large-zh"
        assert model_short_name("BAAI/bge-large-zh") == "bge-large-zh"
        assert model_short_name("custom/foo") == "custom"

    def test_get_model_dimension(self):
        assert get_model_dimension("bge-large-zh") == 1024
        assert get_model_dimension("m3e-base") == 768
        assert get_model_dimension("openai") == 1536
        assert get_model_dimension("custom/foo") == 0

    def test_should_normalize(self):
        assert should_normalize("bge-large-zh") is True
        assert should_normalize("m3e-base") is True
        assert should_normalize("openai") is False

    def test_supported_models_nonempty(self):
        assert "bge-large-zh" in SUPPORTED_MODELS
        assert "m3e-base" in SUPPORTED_MODELS
        assert "openai" in SUPPORTED_MODELS

    def test_model_dimensions_covered(self):
        for short in SUPPORTED_MODELS:
            if short in MODEL_DIMENSIONS:
                assert MODEL_DIMENSIONS[short] > 0


class TestEmbeddingSettings:
    def test_defaults(self):
        s = EmbeddingSettings()
        assert s.model == DEFAULT_MODEL
        assert s.device == "cpu"
        assert s.batchSize > 0
        assert s.asyncChunk > 0

    def test_invalid_model(self):
        with pytest.raises(Exception):
            EmbeddingSettings(model="nonexistent")

    def test_invalid_device(self):
        with pytest.raises(Exception):
            EmbeddingSettings(device="invalid")

    def test_env_override(self, monkeypatch):
        monkeypatch.setenv("CHUNKER_EMBEDDING_MODEL", "m3e-base")
        monkeypatch.setenv("CHUNKER_EMBEDDING_DEVICE", "cuda")
        s = EmbeddingSettings()
        assert s.model == "m3e-base"
        assert s.device == "cuda"

    def test_get_settings_cached(self):
        reset_embedding_settings()
        s1 = get_embedding_settings()
        s2 = get_embedding_settings()
        assert s1 is s2

    def test_reset_settings(self):
        s1 = get_embedding_settings()
        reset_embedding_settings()
        s2 = get_embedding_settings()
        # 重置后应重新创建
        assert s1 is not s2