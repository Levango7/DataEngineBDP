"""推理编排器（B 线）单元测试：契约双模式 + K8s 资源形状 + 状态映射 + 失败真实上报.

fake K8s client 与生产适配层同签名（返回 dict）；不冒充真集群验收——
真集群 create/invoke/scale/stop 由 B2 泳道（LLMOPS_TEST_KUBECONFIG 门控）验证。
"""

from __future__ import annotations

from fastapi.testclient import TestClient
import httpx
from pydantic import ValidationError
import pytest

from llmops.api.app import create_app
from llmops.config.settings import Settings
from llmops.services import kubernetes_inference_orchestrator as kio
from llmops.services.frontend_store import FrontendContractStore
from llmops.services.inference_orchestrator import (
    SERVICE_ID_LABEL,
    TENANT_ANNOTATION,
    ContractInferenceOrchestrator,
    OrchestratorError,
    cluster_service_url,
    deployment_name,
    dns1123,
    tenant_tag,
)
from llmops.services.kubernetes_inference_orchestrator import (
    KubernetesInferenceOrchestrator,
    deployment_body,
    service_body,
    status_patch_from_deployment,
)

# ---------------------------------------------------------------- 工具与夹具


def _k8s_settings() -> Settings:
    return Settings(
        storeType="mock",
        inferenceOrchestrator="kubernetes",
        inferenceImage="ghcr.io/ggml-org/llama.cpp:server",
        inferenceModelUrl="https://example.invalid/model.gguf",
        inferenceNamespace="llmops-inference",
    )


class _FakeResp:
    def __init__(self, data):
        self._data = data

    def raise_for_status(self) -> None:
        return None

    def json(self):
        return self._data


class _FakeAsyncClient:
    last_url = ""

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def post(self, url, json):
        type(self).last_url = url
        return _FakeResp({"content": "hello world"})


class _FakeK8s:
    """与 _RealK8sClient 同签名的 fake（记录调用、可注入失败、可布置集群实况）."""

    def __init__(self, fail_on=()):
        self.calls = []
        self.fail_on = set(fail_on)
        self.deleted = []
        self.list_items = []

    def create_namespaced_deployment(self, namespace, body):
        self.calls.append(("create_deploy", namespace, body))
        if "create_deploy" in self.fail_on:
            raise RuntimeError("simulated create deployment failure")
        return body

    def create_namespaced_service(self, namespace, body):
        self.calls.append(("create_svc", namespace, body))
        if "create_svc" in self.fail_on:
            raise RuntimeError("simulated create service failure")
        return body

    def patch_namespaced_deployment_scale(self, name, namespace, body):
        self.calls.append(("scale", name, body))
        if "scale" in self.fail_on:
            raise RuntimeError("simulated scale failure")
        return body

    def list_namespaced_deployment(self, namespace, label_selector):
        if "list" in self.fail_on:
            raise RuntimeError("simulated list failure")
        return {"items": self.list_items}

    def delete_namespaced_deployment(self, name, namespace):
        self.deleted.append(("deploy", name))

    def delete_namespaced_service(self, name, namespace):
        self.deleted.append(("svc", name))


def _orch(store=None, fake=None):
    store = store or FrontendContractStore()
    fake = fake or _FakeK8s()
    return KubernetesInferenceOrchestrator(store, _k8s_settings(), k8s_client=fake), store, fake


# ---------------------------------------------------------------- settings 校验


def test_production_rejects_contract_orchestrator() -> None:
    with pytest.raises(ValidationError):
        Settings(
            storeType="mock",
            frontendStore="postgresql",
            frontendDatabaseUrl="postgresql://llmops_test@localhost:5432/llmops_test",
            environment="production",
        )


def test_kubernetes_requires_image_and_model_url() -> None:
    with pytest.raises(ValidationError):
        Settings(storeType="mock", inferenceOrchestrator="kubernetes")
    with pytest.raises(ValidationError):
        Settings(storeType="mock", inferenceOrchestrator="kubernetes", inferenceImage="img")
    with pytest.raises(ValidationError):
        Settings(
            storeType="mock",
            inferenceOrchestrator="kubernetes",
            inferenceImage="img",
            inferenceModelUrl="ftp://example.invalid/model.gguf",
        )


def test_development_defaults_to_contract() -> None:
    s = Settings(storeType="mock")
    assert s.inferenceOrchestrator == "contract"


# ---------------------------------------------------------------- 工具函数


def test_dns1123_and_resource_names() -> None:
    assert dns1123("AbC_def") == "abc-def"
    assert tenant_tag("tenant-A") != tenant_tag("tenant-B")
    name = deployment_name("svc-123")
    assert name.startswith("llmops-inf-") and len(name) <= 63
    with pytest.raises(ValueError):
        dns1123("---")


def test_cluster_service_url_host_validation() -> None:
    url = cluster_service_url("abc", "llmops-inference")
    assert url == "http://llmops-inf-abc.llmops-inference.svc.cluster.local"
    # 非法字符被归一化折叠（白名单 [a-z0-9-]）⇒ host 注入不可能
    assert cluster_service_url("abc", "bad_ns!") == "http://llmops-inf-abc.bad-ns.svc.cluster.local"
    with pytest.raises(ValueError):
        cluster_service_url("abc", "!!!")  # 归一后为空 ⇒ 拒绝


@pytest.mark.parametrize(
    "desired,available,expected",
    [(0, 0, "STOPPED"), (2, 2, "RUNNING"), (3, 1, "DEPLOYING"), (2, 0, "DEPLOYING")],
)
def test_status_patch_from_deployment(desired, available, expected) -> None:
    patch = status_patch_from_deployment({"spec": {"replicas": desired}, "status": {"availableReplicas": available}})
    assert patch["status"] == expected
    assert patch["desiredReplicas"] == desired


# ---------------------------------------------------------------- 资源形状


def test_deployment_and_service_body_shape() -> None:
    svc = {"id": "svc-1", "modelName": "m", "desiredReplicas": 2}
    body = deployment_body(_k8s_settings(), "tenant-A", svc)
    assert body["spec"]["replicas"] == 2
    assert body["metadata"]["annotations"][TENANT_ANNOTATION] == "tenant-A"
    init = body["spec"]["template"]["spec"]["initContainers"][0]
    assert init["env"][0]["value"] == "https://example.invalid/model.gguf"
    # 模型 URL 经 env 引用（$MODEL_URL），不拼进 shell 命令（防注入）
    assert "$MODEL_URL" in init["args"][0]
    assert "example.invalid" not in init["args"][0]
    main = body["spec"]["template"]["spec"]["containers"][0]
    assert main["image"] == "ghcr.io/ggml-org/llama.cpp:server"
    assert main["readinessProbe"]["httpGet"]["path"] == "/health"
    sb = service_body(_k8s_settings(), "tenant-A", svc)
    assert sb["spec"]["selector"][SERVICE_ID_LABEL] == "svc-1"
    assert sb["spec"]["ports"][0]["targetPort"] == 8000


# ---------------------------------------------------------------- contract 模式


def test_contract_deploy_scale_stop_and_invoke_501() -> None:
    store = FrontendContractStore()
    orch = ContractInferenceOrchestrator(store)
    svc = orch.deploy("t1", {"modelName": "m", "replicas": 2})
    assert svc["status"] == "RUNNING"
    assert orch.scale("t1", svc["id"], 0)["status"] == "STOPPED"
    assert orch.stop("t1", "no-such") is None


@pytest.mark.asyncio
async def test_contract_invoke_honest_501_and_404() -> None:
    store = FrontendContractStore()
    orch = ContractInferenceOrchestrator(store)
    svc = orch.deploy("t1", {"modelName": "m"})
    with pytest.raises(OrchestratorError) as exc_info:
        await orch.invoke("t1", svc["id"], {"prompt": "hi"})
    assert exc_info.value.status_code == 501
    assert await orch.invoke("t1", "no-such", {}) is None


# ---------------------------------------------------------------- K8s 编排


def test_k8s_deploy_writes_deploying_and_calls_cluster() -> None:
    orch, store, fake = _orch()
    svc = orch.deploy("t1", {"modelName": "m", "replicas": 2})
    assert svc["status"] == "DEPLOYING"
    kinds = [c[0] for c in fake.calls]
    assert kinds == ["create_deploy", "create_svc"]


def test_k8s_deploy_failure_marks_failed_and_cleans_up() -> None:
    orch, store, fake = _orch(fake=_FakeK8s(fail_on={"create_svc"}))
    svc = orch.deploy("t1", {"modelName": "m"})
    assert svc["status"] == "FAILED"
    assert "errorMessage" in svc
    assert fake.deleted, "deploy 失败必须 best-effort 清理残留"


def test_k8s_scale_writes_desired_and_failure_does_not_rewrite() -> None:
    orch, store, fake = _orch()
    svc = orch.deploy("t1", {"modelName": "m", "replicas": 2})
    scaled = orch.scale("t1", svc["id"], 5)
    assert scaled["desiredReplicas"] == 5 and scaled["status"] == "DEPLOYING"
    fake.fail_on.add("scale")
    with pytest.raises(OrchestratorError):
        orch.scale("t1", svc["id"], 3)
    assert orch._find("t1", svc["id"])["desiredReplicas"] == 5
    assert orch.scale("t1", "no-such", 1) is None


def test_k8s_stop_scales_to_zero() -> None:
    orch, store, fake = _orch()
    svc = orch.deploy("t1", {"modelName": "m", "replicas": 2})
    stopped = orch.stop("t1", svc["id"])
    assert stopped["status"] == "STOPPED" and stopped["desiredReplicas"] == 0
    assert orch.stop("t1", "no-such") is None


@pytest.mark.asyncio
async def test_k8s_invoke_requires_running_and_forwards(monkeypatch) -> None:
    orch, store, fake = _orch()
    svc = orch.deploy("t1", {"modelName": "m"})
    with pytest.raises(OrchestratorError) as exc_info:
        await orch.invoke("t1", svc["id"], {"prompt": "hi"})
    assert exc_info.value.status_code == 409
    assert await orch.invoke("t1", "no-such", {}) is None
    # 置 RUNNING 后转发到集群内 Service /completion
    store.patch_inference("t1", svc["id"], {"status": "RUNNING"})
    monkeypatch.setattr(httpx, "AsyncClient", _FakeAsyncClient)
    result = await orch.invoke("t1", svc["id"], {"prompt": "hi"})
    assert result["text"] == "hello world"
    assert _FakeAsyncClient.last_url.endswith("/completion")


def test_reconcile_maps_cluster_state_and_marks_missing() -> None:
    orch, store, fake = _orch()
    svc = orch.deploy("t1", {"modelName": "m", "replicas": 2})
    svc_id = svc["id"]
    fake.list_items = [
        {
            "metadata": {"labels": {SERVICE_ID_LABEL: svc_id}, "annotations": {TENANT_ANNOTATION: "t1"}},
            "spec": {"replicas": 2},
            "status": {"availableReplicas": 2},
        }
    ]
    orch.reconcile()
    assert orch._find("t1", svc_id)["status"] == "RUNNING"
    fake.list_items = []
    orch.reconcile()
    assert orch._find("t1", svc_id)["status"] == "FAILED"
    # FAILED 终态不重复标；list 异常静默下轮
    fake.fail_on.add("list")
    orch.reconcile()
    assert orch._find("t1", svc_id)["status"] == "FAILED"


def test_reconcile_stopped_records_are_not_marked_failed() -> None:
    orch, store, fake = _orch()
    svc = orch.deploy("t1", {"modelName": "m"})
    orch.stop("t1", svc["id"])
    fake.list_items = []
    orch.reconcile()
    assert orch._find("t1", svc["id"])["status"] == "STOPPED"


# ---------------------------------------------------------------- 路由层


def test_contract_route_invoke_501_and_404(monkeypatch) -> None:
    monkeypatch.setenv("AUTH_MODE", "none")
    app = create_app(settings=Settings(storeType="mock"))
    c = TestClient(app)
    r = c.post("/api/v1/llmops/inference-services", json={"modelName": "m"})
    svc_id = r.json()["id"]
    r2 = c.post(f"/api/v1/llmops/inference-services/{svc_id}/invoke", json={"prompt": "hi"})
    assert r2.status_code == 501
    r3 = c.post("/api/v1/llmops/inference-services/no-such/invoke", json={"prompt": "hi"})
    assert r3.status_code == 404


def test_k8s_route_deploy_returns_deploying(monkeypatch) -> None:
    monkeypatch.setenv("AUTH_MODE", "none")
    fake = _FakeK8s()
    monkeypatch.setattr(kio, "_RealK8sClient", lambda settings: fake)
    app = create_app(settings=_k8s_settings())
    c = TestClient(app)
    r = c.post("/api/v1/llmops/inference-services", json={"modelName": "m", "replicas": 2})
    assert r.status_code == 201, r.text
    assert r.json()["status"] == "DEPLOYING"
    assert any(call[0] == "create_deploy" for call in fake.calls)
