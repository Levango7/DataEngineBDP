"""多模型发布：模型 artifact 解析单元测试（按模型名+版本取推理下载 URL）."""

from __future__ import annotations

import uuid

import pytest

from llmops.models.model import ModelFilter, ModelInfo, ModelType, ModelVersion
from llmops.repositories.mock.store import MockModelStore
from llmops.services.model_artifact import resolve_model_artifact_url
from llmops.services.model_service import ModelService


class _Registry:
    """最小 registry 形状（只有 modelService，够 resolver 用）."""

    def __init__(self, service: ModelService) -> None:
        self.modelService = service


async def _service_with_versions(name: str, versions: list[tuple[int, str, bool]]) -> ModelService:
    """注册模型并添加版本：[(version, artifactUri, isProduction), ...]."""
    store = MockModelStore()
    svc = ModelService(store)
    model = await svc.register_model(ModelInfo(id=str(uuid.uuid4()), name=name, type=ModelType.BASE))
    for ver, uri, prod in versions:
        await store.add_model_version(model.id, ModelVersion(version=ver, modelId=model.id, artifactUri=uri))
        if prod:
            await store.set_production_version(model.id, ver)
    return svc


@pytest.mark.asyncio
async def test_resolve_http_artifact() -> None:
    svc = await _service_with_versions("qwen2.5-0.5b", [(1, "https://models.example/qwen.gguf", False)])
    url = await resolve_model_artifact_url(_Registry(svc), "qwen2.5-0.5b", "1")
    assert url == "https://models.example/qwen.gguf"


@pytest.mark.asyncio
async def test_resolve_missing_model_returns_none() -> None:
    svc = await _service_with_versions("qwen2.5-0.5b", [(1, "https://m/a.gguf", False)])
    assert await resolve_model_artifact_url(_Registry(svc), "no-such-model", None) is None


@pytest.mark.asyncio
async def test_resolve_non_http_artifact_returns_none() -> None:
    """MLflow 内部 URI（runs:/…）v1 不解析 ⇒ None（回退全局兜底，并留日志）."""
    svc = await _service_with_versions("ft-model", [(1, "runs:/abc123/model", False)])
    assert await resolve_model_artifact_url(_Registry(svc), "ft-model", "1") is None


@pytest.mark.asyncio
async def test_resolve_explicit_version() -> None:
    svc = await _service_with_versions("m", [(1, "https://m/v1.gguf", False), (2, "https://m/v2.gguf", False)])
    assert await resolve_model_artifact_url(_Registry(svc), "m", "2") == "https://m/v2.gguf"
    assert await resolve_model_artifact_url(_Registry(svc), "m", 1) == "https://m/v1.gguf"


@pytest.mark.asyncio
async def test_resolve_production_version_when_unspecified() -> None:
    svc = await _service_with_versions("m", [(1, "https://m/v1.gguf", False), (2, "https://m/v2.gguf", True)])
    assert await resolve_model_artifact_url(_Registry(svc), "m", None) == "https://m/v2.gguf"


def test_pick_version_latest_when_no_production() -> None:
    """「无 production ⇒ 最大版本」分支用纯函数验证（mock store 会把首版本自动置 production）."""
    from types import SimpleNamespace

    from llmops.services.model_artifact import _pick_version

    versions = [SimpleNamespace(version=1, isProduction=False), SimpleNamespace(version=3, isProduction=False)]
    assert _pick_version(versions, None).version == 3
    assert _pick_version(versions, "1").version == 1


@pytest.mark.asyncio
async def test_mock_store_first_version_is_production() -> None:
    """记录 mock 语义：首个版本自动 production ⇒ 不传版本选中它（真实仓由 set_production 决定）."""
    svc = await _service_with_versions("m", [(1, "https://m/v1.gguf", False), (3, "https://m/v3.gguf", False)])
    assert await resolve_model_artifact_url(_Registry(svc), "m", None) == "https://m/v1.gguf"


@pytest.mark.asyncio
async def test_resolve_version_not_found_returns_none() -> None:
    svc = await _service_with_versions("m", [(1, "https://m/v1.gguf", False)])
    assert await resolve_model_artifact_url(_Registry(svc), "m", "9") is None
    assert await resolve_model_artifact_url(_Registry(svc), "m", "abc") is None


@pytest.mark.asyncio
async def test_resolve_blank_name_returns_none() -> None:
    svc = await _service_with_versions("m", [(1, "https://m/v1.gguf", False)])
    assert await resolve_model_artifact_url(_Registry(svc), "  ", None) is None


@pytest.mark.asyncio
async def test_find_model_by_name_is_exact_not_fuzzy() -> None:
    """多模型发布必须精确匹配：'qwen' 不能命中 'qwen-7b'（否则部署错模型）."""
    store = MockModelStore()
    svc = ModelService(store)
    await svc.register_model(ModelInfo(id=str(uuid.uuid4()), name="qwen-7b", type=ModelType.BASE))
    assert await svc.find_model_by_name("qwen") is None
    assert (await svc.find_model_by_name("qwen-7b")).name == "qwen-7b"


@pytest.mark.asyncio
async def test_resolve_registry_error_returns_none() -> None:
    class _Broken:
        class modelService:  # noqa: N801
            @staticmethod
            async def find_model_by_name(name):
                raise RuntimeError("registry down")

    assert await resolve_model_artifact_url(_Broken(), "m", None) is None


@pytest.mark.asyncio
async def test_model_filter_fuzzy_contract_unchanged() -> None:
    """既有 ModelFilter 模糊语义不变（resolver 的精确过滤只发生在服务层）."""
    store = MockModelStore()
    svc = ModelService(store)
    await svc.register_model(ModelInfo(id=str(uuid.uuid4()), name="qwen-7b", type=ModelType.BASE))
    fuzzy = await svc.list_models(ModelFilter(name="qwen"))
    assert [m.name for m in fuzzy] == ["qwen-7b"]


def test_register_model_version_endpoint(monkeypatch) -> None:
    """多模型发布端到端前置：POST /models/{id}/versions 注册带 http artifactUri 的版本."""
    from fastapi.testclient import TestClient

    from llmops.api.app import create_app
    from llmops.config.settings import Settings

    monkeypatch.setenv("AUTH_MODE", "none")
    app = create_app(settings=Settings(storeType="mock"))
    c = TestClient(app)
    m = c.post("/api/v1/llmops/models", json={"name": "qwen-x", "type": "base"})
    assert m.status_code in (200, 201), m.text
    model_id = m.json()["id"]

    r1 = c.post(
        f"/api/v1/llmops/models/{model_id}/versions",
        json={"artifactUri": "https://models.example/q4.gguf"},
    )  # 前端契约面（/llmops/models）
    assert r1.status_code == 201, r1.text
    assert r1.json()["version"] == 1 and r1.json()["artifactUri"] == "https://models.example/q4.gguf"

    r2 = c.post(
        f"/api/v1/llmops/models/{model_id}/versions",
        json={"artifactUri": "https://models.example/q8.gguf"},
    )
    assert r2.status_code == 201 and r2.json()["version"] == 2

    r3 = c.post(
        f"/api/v1/llmops/models/{model_id}/versions",
        json={"version": 5, "artifactUri": "https://models.example/q5.gguf"},
    )
    assert r3.status_code == 201 and r3.json()["version"] == 5

    r4 = c.post("/api/v1/llmops/models/no-such-model/versions", json={"artifactUri": "https://x/y.gguf"})
    assert r4.status_code == 404


# ---------------------------------------------------------------- MLflow 内部 URI 解析
# 「自己训练的模型」的 artifactUri 是 MLflow 产物形式（runs:/…），需转成 tracking
# server 的下载 URL 才能被推理 initContainer 下载。


def test_to_mlflow_download_url_runs() -> None:
    from llmops.services.model_artifact import _to_mlflow_download_url

    url = _to_mlflow_download_url("runs:/run1/model/foo.gguf", "http://mlflow:5000")
    assert url == "http://mlflow:5000/get-artifact?path=model%2Ffoo.gguf&run_id=run1"


def test_to_mlflow_download_url_mlflow_artifacts() -> None:
    from llmops.services.model_artifact import _to_mlflow_download_url

    url = _to_mlflow_download_url("mlflow-artifacts:/1/abc/model.gguf", "https://mlflow.local/")
    assert url == "https://mlflow.local/api/2.0/mlflow-artifacts/artifacts/1/abc/model.gguf"


def test_to_mlflow_download_url_rejects_unsupported() -> None:
    from llmops.services.model_artifact import _to_mlflow_download_url

    assert _to_mlflow_download_url("s3://bucket/key", "http://mlflow:5000") is None
    assert _to_mlflow_download_url("file:///tmp/x", "http://mlflow:5000") is None
    assert _to_mlflow_download_url("runs:/run1/model", "ftp://mlflow") is None
    assert _to_mlflow_download_url("runs:/run1/model", "") is None
    assert _to_mlflow_download_url("runs:/runonly", "http://mlflow:5000") is None


@pytest.mark.asyncio
async def test_resolve_runs_uri_with_tracking_uri() -> None:
    svc = await _service_with_versions("ft-model", [(1, "runs:/run1/model/foo.gguf", False)])
    url = await resolve_model_artifact_url(_Registry(svc), "ft-model", "1", tracking_uri="http://mlflow:5000")
    assert url == "http://mlflow:5000/get-artifact?path=model%2Ffoo.gguf&run_id=run1"


@pytest.mark.asyncio
async def test_resolve_runs_uri_without_tracking_uri_returns_none() -> None:
    """不传 tracking_uri 保持旧行为：内部 URI 无法转换 ⇒ None（回退全局兜底）."""
    svc = await _service_with_versions("ft-model", [(1, "runs:/run1/model/foo.gguf", False)])
    assert await resolve_model_artifact_url(_Registry(svc), "ft-model", "1") is None


@pytest.mark.asyncio
async def test_resolve_models_uri_recurses_to_runs() -> None:
    """models:/<name>/<ver> 解析为注册表对应版本的 artifactUri（runs:/）再转下载 URL."""
    store = MockModelStore()
    svc = ModelService(store)
    target = await svc.register_model(ModelInfo(id=str(uuid.uuid4()), name="source-model", type=ModelType.BASE))
    await svc.add_model_version(
        target.id, ModelVersion(version=1, modelId=target.id, artifactUri="runs:/run9/model.gguf")
    )
    alias = await svc.register_model(ModelInfo(id=str(uuid.uuid4()), name="alias-model", type=ModelType.BASE))
    await svc.add_model_version(
        alias.id, ModelVersion(version=1, modelId=alias.id, artifactUri="models:/source-model/1")
    )
    url = await resolve_model_artifact_url(_Registry(svc), "alias-model", "1", tracking_uri="http://mlflow:5000")
    assert url == "http://mlflow:5000/get-artifact?path=model.gguf&run_id=run9"


@pytest.mark.asyncio
async def test_resolve_models_uri_depth_guard() -> None:
    """models:/ 自指时（深度超限）返回 None，不无限递归."""
    store = MockModelStore()
    svc = ModelService(store)
    m = await svc.register_model(ModelInfo(id=str(uuid.uuid4()), name="self-ref", type=ModelType.BASE))
    await svc.add_model_version(m.id, ModelVersion(version=1, modelId=m.id, artifactUri="models:/self-ref/1"))
    assert await resolve_model_artifact_url(_Registry(svc), "self-ref", "1", tracking_uri="http://mlflow:5000") is None
