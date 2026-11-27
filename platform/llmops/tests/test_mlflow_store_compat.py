"""MLflow 3.x 适配层兼容回归（真链路实测踩到的形态差异）.

真链路（MLflow 3.17.2 + 训练产物 runs:/ 端到端）暴露了既有适配层 4 处与 3.x 不兼容：
1. RegisteredModel.tags 在 3.x 是 dict（2.x 是 Tag 实体列表）——decode 直接 t.key 会崩；
2. RegisteredModel.latest_versions 只含「每个 stage 的最新」⇒ 无 stage 的新版本会漏，
   get_model_versions 曾恒为空；
3. create_model_version 的 tags 参数形态（dict vs 实体列表）——已省略（无消费者）；
4. source=runs:/<run_id>/… 必须同时传 run_id（否则 INVALID_PARAMETER_VALUE），且
   3.x 的 mv.version 是字符串（契约要求 int）。
本文件用 fake client 锁定这四处行为；真实 MLflow 端到端由 B5 泳道验证。
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from llmops.models.model import ModelInfo, ModelType, ModelVersion
from llmops.repositories.mlflow.store import MLflowModelStore


class _Inner:
    """与 mlflow.MlflowClient 同形状的最小 fake（只覆盖本适配层用到的调用）."""

    def __init__(self) -> None:
        self.create_kwargs: dict | None = None
        self.versions: list = []
        self.rms: list = []

    def search_registered_models(self):
        return self.rms

    def get_registered_model(self, name: str):
        return next(r for r in self.rms if r.name == name)

    def search_model_versions(self, filter_string: str):
        assert "ft-qwen" in filter_string
        return self.versions

    def create_model_version(self, **kwargs):
        self.create_kwargs = kwargs
        return SimpleNamespace(version="2")


class _Wrapper:
    def __init__(self, inner: _Inner) -> None:
        self.client = inner


def _rm(tags=None, latest=None):
    return SimpleNamespace(
        name="ft-qwen",
        tags=tags if tags is not None else {},
        latest_versions=latest or [],
        description="",
        creation_timestamp=1,
        last_updated_timestamp=1,
    )


def _store() -> tuple[MLflowModelStore, _Inner]:
    inner = _Inner()
    return MLflowModelStore(_Wrapper(inner)), inner


def test_decode_tags_dict_3x() -> None:
    """MLflow 3.x：RegisteredModel.tags 是 dict."""
    store, _ = _store()
    m = store._decode_registered_model(
        _rm(tags={"sq.llmops.id": "abc", "sq.llmops.type": "base", "sq.llmops.status": "ready"})
    )
    assert m.id == "abc" and m.type == "base" and m.status == "ready"


def test_decode_tags_list_2x() -> None:
    """MLflow 2.x：RegisteredModel.tags 是 Tag 实体列表（兼容保留）."""
    store, _ = _store()
    m = store._decode_registered_model(
        _rm(
            tags=[SimpleNamespace(key="sq.llmops.id", value="xyz"), SimpleNamespace(key="sq.llmops.type", value="base")]
        )
    )
    assert m.id == "xyz" and m.type == "base"


@pytest.mark.asyncio
async def test_get_model_versions_includes_unstaged_3x() -> None:
    """3.x 的 latest_versions 漏无 stage 版本 ⇒ 必须走 search_model_versions 全量."""
    store, inner = _store()
    inner.rms = [_rm(tags={"sq.llmops.id": "abc"})]
    inner.versions = [
        SimpleNamespace(version="1", run_id="run1", source="runs:/run1/model/x.gguf", current_stage="None")
    ]
    versions = await store.get_model_versions("abc")
    assert [v.version for v in versions] == [1]
    assert versions[0].artifactUri == "runs:/run1/model/x.gguf"
    assert versions[0].isProduction is False


@pytest.mark.asyncio
async def test_add_model_version_extracts_run_id_and_int_version(monkeypatch) -> None:
    """source=runs:/<run_id>/… 必须配 run_id；3.x 返回的 mv.version 是 str ⇒ 转 int."""
    store, inner = _store()

    async def fake_get_model(model_id: str) -> ModelInfo:
        return ModelInfo(id=model_id, name="ft-qwen", type=ModelType.BASE)

    monkeypatch.setattr(store, "get_model", fake_get_model)
    created = await store.add_model_version(
        "abc", ModelVersion(version=1, modelId="abc", artifactUri="runs:/run9/model/x.gguf")
    )
    assert inner.create_kwargs is not None
    assert inner.create_kwargs["run_id"] == "run9"
    assert inner.create_kwargs["source"] == "runs:/run9/model/x.gguf"
    assert "tags" not in inner.create_kwargs  # 省略（3.x/2.x 形态不同且无消费者）
    assert created.version == 2 and isinstance(created.version, int)


@pytest.mark.asyncio
async def test_add_model_version_keeps_explicit_run_id(monkeypatch) -> None:
    """显式 sourceRunId 优先于从 artifactUri 提取."""
    store, inner = _store()

    async def fake_get_model(model_id: str) -> ModelInfo:
        return ModelInfo(id=model_id, name="ft-qwen", type=ModelType.BASE)

    monkeypatch.setattr(store, "get_model", fake_get_model)
    await store.add_model_version(
        "abc",
        ModelVersion(version=1, modelId="abc", artifactUri="runs:/run9/model/x.gguf", sourceRunId="explicit-run"),
    )
    assert inner.create_kwargs["run_id"] == "explicit-run"
