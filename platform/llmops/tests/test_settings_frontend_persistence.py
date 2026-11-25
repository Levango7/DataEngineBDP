"""前端视图持久化配置回归（A2）：生产 fail-fast + env 映射 + 工厂分发。

安全约束（用户指令）：生产禁止内存回退；DSN 缺失必须启动即失败，不得静默降级。
"""

from __future__ import annotations

from pydantic import ValidationError
import pytest

from llmops.config.settings import Settings
from llmops.services.frontend_store import FrontendContractStore
from llmops.services.frontend_store_factory import create_frontend_store
from llmops.services.postgres_frontend_store import PostgresFrontendContractStore


def test_production_rejects_memory_frontend_store() -> None:
    with pytest.raises(ValidationError):
        Settings(storeType="mock", frontendStore="memory", environment="production")


def test_production_requires_postgresql_frontend_store() -> None:
    s = Settings(
        storeType="mock",
        frontendStore="postgresql",
        frontendDatabaseUrl="postgresql://llmops_test@localhost:5432/llmops_test",
        environment="production",
        # B 线：production 另需真编排（禁止契约级假编排）
        inferenceOrchestrator="kubernetes",
        inferenceImage="ghcr.io/ggml-org/llama.cpp:server",
        inferenceModelUrl="https://example.invalid/model.gguf",
    )
    assert s.environment == "production"
    assert s.frontendStore == "postgresql"


def test_postgresql_without_dsn_fails_fast() -> None:
    with pytest.raises(ValidationError):
        Settings(storeType="mock", frontendStore="postgresql", frontendDatabaseUrl="")


def test_development_defaults_to_memory(monkeypatch) -> None:
    monkeypatch.delenv("LLMOPS_FRONTEND_STORE", raising=False)
    monkeypatch.delenv("LLMOPS_ENV", raising=False)
    s = Settings(storeType="mock")
    assert s.frontendStore == "memory"
    assert s.environment == "development"


def test_env_vars_are_honored(monkeypatch) -> None:
    monkeypatch.setenv("LLMOPS_FRONTEND_STORE", "postgresql")
    monkeypatch.setenv("LLMOPS_FRONTEND_DATABASE_URL", "postgresql://llmops_test@localhost:5432/llmops_test")
    monkeypatch.setenv("LLMOPS_ENV", "production")
    monkeypatch.setenv("LLMOPS_INFERENCE_ORCHESTRATOR", "kubernetes")
    monkeypatch.setenv("LLMOPS_INFERENCE_IMAGE", "ghcr.io/ggml-org/llama.cpp:server")
    monkeypatch.setenv("LLMOPS_INFERENCE_MODEL_URL", "https://example.invalid/model.gguf")
    monkeypatch.delenv("AI_MODE", raising=False)
    s = Settings()
    assert s.frontendStore == "postgresql"
    assert s.frontendDatabaseUrl == "postgresql://llmops_test@localhost:5432/llmops_test"
    assert s.environment == "production"


def test_factory_dispatches_memory_by_default(monkeypatch) -> None:
    monkeypatch.delenv("LLMOPS_FRONTEND_STORE", raising=False)
    monkeypatch.delenv("AI_MODE", raising=False)
    store = create_frontend_store(Settings(storeType="mock"))
    assert isinstance(store, FrontendContractStore)
    assert not isinstance(store, PostgresFrontendContractStore)
