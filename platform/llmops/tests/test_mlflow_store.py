"""MLflowModelStore 事件循环卸载与行为回归测试.

mlflow 包未安装时也可运行：用鸭子类型替身模拟 mlflow 模块与同步 MlflowClient。
"""

from __future__ import annotations

import asyncio
import time
import types

import pytest

from llmops.models.base import ModelType
from llmops.models.model import ModelFilter, ModelInfo, ModelVersion
from llmops.repositories import (
    ModelAlreadyExistsError,
    ModelNotFoundError,
    VersionNotFoundError,
)
from llmops.repositories.mlflow import client as mlflow_client_module
from llmops.repositories.mlflow.client import MLflowClient
from llmops.repositories.mlflow.store import MLflowModelStore

SDK_DELAY_SECONDS = 0.5
PROBE_TIME_BUDGET_SECONDS = 0.45


class _RestException(Exception):
    pass


class _FakeMlflowModule:
    exceptions = types.SimpleNamespace(RestException=_RestException)


class _Tag:
    def __init__(self, key: str, value: str) -> None:
        self.key = key
        self.value = value


class _SdkModelVersion:
    def __init__(
        self, version: int, run_id=None, source="", current_stage="None"
    ) -> None:
        self.version = version
        self.run_id = run_id
        self.source = source
        self.current_stage = current_stage


class _SdkRegisteredModel:
    def __init__(self, name: str, tags=None, description: str = "") -> None:
        self.name = name
        self.tags = [_Tag(k, v) for k, v in (tags or {}).items()]
        self.description = description
        self.latest_versions = []
        self.creation_timestamp = 1700000000000
        self.last_updated_timestamp = 1700000000000


class _FakeRegistrySdk:
    """同步假 MlflowClient，可注入每调用延迟以模拟阻塞的底层 requests HTTP."""

    def __init__(self, delay: float = 0.0) -> None:
        self._delay = delay
        self._models: dict[str, _SdkRegisteredModel] = {}
        self._next_version: dict[str, int] = {}

    def _block(self) -> None:
        if self._delay > 0:
            time.sleep(self._delay)

    def get_registered_model(self, name: str) -> _SdkRegisteredModel:
        self._block()
        if name not in self._models:
            raise _RestException(f"RESOURCE_DOES_NOT_EXIST: {name}")
        return self._models[name]

    def create_registered_model(
        self, name: str, tags=None, description: str = ""
    ) -> _SdkRegisteredModel:
        rm = _SdkRegisteredModel(name, tags, description)
        self._models[name] = rm
        return rm

    def search_registered_models(self) -> list[_SdkRegisteredModel]:
        self._block()
        return list(self._models.values())

    def delete_registered_model(self, name: str) -> None:
        self._block()
        self._models.pop(name, None)

    def create_model_version(
        self, name: str, source: str = "", run_id=None, tags=None
    ) -> _SdkModelVersion:
        self._block()
        rm = self._models[name]
        version = self._next_version.get(name, 1)
        mv = _SdkModelVersion(version, run_id=run_id, source=source)
        self._next_version[name] = version + 1
        rm.latest_versions.append(mv)
        return mv

    def transition_model_version_stage(
        self, name: str, version: int, stage: str
    ) -> None:
        self._block()
        matched = [
            mv for mv in self._models[name].latest_versions if mv.version == version
        ]
        if not matched:
            raise _RestException(f"model version {version} not found")
        matched[0].current_stage = stage

    def update_registered_model(self, name: str, description: str = "") -> None:
        self._block()
        self._models[name].description = description

    def set_registered_model_tag(self, name: str, key: str, value: str) -> None:
        self._block()
        rm = self._models[name]
        rm.tags = [t for t in rm.tags if t.key != key]
        rm.tags.append(_Tag(key, value))


class _FakeStoreClient:
    """鸭子类型替身 llmops.repositories.mlflow.client.MLflowClient."""

    def __init__(self, sdk: _FakeRegistrySdk) -> None:
        self.mlflow = _FakeMlflowModule()
        self.client = sdk


def _make_store(delay: float = 0.0) -> tuple[MLflowModelStore, _FakeRegistrySdk]:
    sdk = _FakeRegistrySdk(delay=delay)
    return MLflowModelStore(_FakeStoreClient(sdk)), sdk


def _make_info(name: str = "m-1", **kwargs) -> ModelInfo:
    kwargs.setdefault("id", f"id-{name}")
    kwargs.setdefault("type", ModelType.BASE)
    return ModelInfo(name=name, **kwargs)


def test_store_methods_do_not_block_event_loop():
    """慢 SDK 调用被线程卸载时，并发的健康探测协程必须在一次 SDK 延迟内完成."""
    cases = {
        "list_models": lambda s: lambda: s.list_models(ModelFilter(limit=10)),
        "register_model": lambda s: lambda: s.register_model(
            _make_info(name="slow-reg")
        ),
    }

    for case_name, make_op in cases.items():
        store, _ = _make_store(delay=SDK_DELAY_SECONDS)
        op_factory = make_op(store)

        async def scenario(op_factory=op_factory):
            start = time.perf_counter()

            async def health_probe() -> float:
                await asyncio.sleep(0.05)
                return time.perf_counter() - start

            results = await asyncio.gather(op_factory(), health_probe())
            total = time.perf_counter() - start
            return results[1], total

        probe_elapsed, total_elapsed = asyncio.run(scenario())
        assert probe_elapsed < PROBE_TIME_BUDGET_SECONDS, (
            f"{case_name}: 并发探测耗时 {probe_elapsed:.3f}s，事件循环疑似被阻塞"
        )
        assert total_elapsed >= SDK_DELAY_SECONDS, (
            f"{case_name}: 总耗时 {total_elapsed:.3f}s，SDK 延迟未真实发生"
        )


def test_full_roundtrip_behavior_unchanged():
    store, _ = _make_store()

    async def scenario():
        model_id = await store.register_model(
            _make_info(name="llm-a", description="desc-1", tags={"team": "nlp"})
        )
        assert model_id == "id-llm-a"

        info = await store.get_model(model_id)
        assert info.name == "llm-a"
        assert info.tags == {"team": "nlp"}
        assert info.description == "desc-1"

        listed = await store.list_models(ModelFilter(limit=10))
        assert [m.id for m in listed] == [model_id]

        filtered = await store.list_models(ModelFilter(name="no-match", limit=10))
        assert filtered == []

        added = await store.add_model_version(
            model_id,
            ModelVersion(version=99, modelId=model_id, artifactUri="s3://bucket/m"),
        )
        assert added.version == 1
        assert added.modelId == model_id

        prod = await store.set_production_version(model_id, added.version)
        assert prod.versions[0].isProduction is True

        updated = await store.update_model(
            model_id, description="desc-2", tags={"team": "cv"}
        )
        assert updated.description == "desc-2"
        assert updated.tags == {"team": "nlp"}

        versions = await store.get_model_versions(model_id)
        assert [v.version for v in versions] == [1]

        await store.delete_model(model_id)
        listed_after_delete = await store.list_models(ModelFilter(limit=10))
        assert listed_after_delete == []
        return model_id

    asyncio.run(scenario())


def test_register_duplicate_raises_already_exists():
    store, _ = _make_store()

    async def scenario():
        await store.register_model(_make_info(name="dup"))
        with pytest.raises(ModelAlreadyExistsError):
            await store.register_model(_make_info(name="dup"))

    asyncio.run(scenario())


def test_register_generates_uuid_when_id_missing():
    store, _ = _make_store()

    async def scenario():
        info = _make_info(name="gen-id")
        info.id = ""
        model_id = await store.register_model(info)
        assert len(model_id) == 36

    asyncio.run(scenario())


def test_get_unknown_model_raises_not_found():
    store, _ = _make_store()

    async def scenario():
        with pytest.raises(ModelNotFoundError):
            await store.get_model("missing")

    asyncio.run(scenario())


def test_delete_unknown_model_raises_not_found():
    store, _ = _make_store()

    async def scenario():
        with pytest.raises(ModelNotFoundError):
            await store.delete_model("missing")

    asyncio.run(scenario())


def test_set_production_unknown_version_raises_version_not_found():
    store, _ = _make_store()

    async def scenario():
        model_id = await store.register_model(_make_info(name="v-probe"))
        with pytest.raises(VersionNotFoundError):
            await store.set_production_version(model_id, 42)

    asyncio.run(scenario())


def test_mlflow_client_reuses_single_sdk_instance(monkeypatch):
    created = []

    class _FakeSdkClient:
        def __init__(self, tracking_uri=None, registry_uri=None) -> None:
            self.tracking_uri = tracking_uri
            self.registry_uri = registry_uri
            created.append(self)

    fake_mlflow = types.SimpleNamespace(
        set_tracking_uri=lambda uri: None,
        set_registry_uri=lambda uri: None,
        tracking=types.SimpleNamespace(MlflowClient=_FakeSdkClient),
    )
    monkeypatch.setattr(mlflow_client_module, "_import_mlflow", lambda: fake_mlflow)

    wrapper = MLflowClient(tracking_uri="http://mlflow:5000")
    first = wrapper.client
    second = wrapper.client
    third = wrapper.client

    assert first is second
    assert second is third
    assert len(created) == 1
    assert created[0].tracking_uri == "http://mlflow:5000"
    assert created[0].registry_uri == "http://mlflow:5000"


def test_mlflow_client_no_lru_cache_dead_method():
    assert not hasattr(MLflowClient, "_registry_client")
