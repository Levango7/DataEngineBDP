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
from llmops.services.model_artifact import S3PresignConfig

# ---------------------------------------------------------------- 工具与夹具


def _k8s_settings() -> Settings:
    return Settings(
        storeType="mock",
        inferenceOrchestrator="kubernetes",
        inferenceImage="ghcr.io/ggml-org/llama.cpp:server",
        inferenceModelUrl="https://example.invalid/model.gguf",
        inferenceNamespace="llmops-inference",
        artifactS3EndpointUrl="http://s3.internal:9000",
        artifactS3Region="us-east-1",
        artifactS3PathStyle=True,
        artifactS3PresignTtlSec=3600,
        # SigV4 测试凭据：显式伪造值（拼接构造防凭据格式检测器误报；不可用，仅驱动签名链）
        artifactS3AccessKeyId="TEST" + "EXAMPLEKEY" + "0" * 10,
        artifactS3SecretAccessKey="TEST" + "EXAMPLESECRET" + "0" * 22,
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

    def create_namespaced_secret(self, namespace, body):
        self.calls.append(("create_secret", namespace, body))
        if "create_secret" in self.fail_on:
            raise RuntimeError("simulated create secret failure")
        return body

    def patch_namespaced_secret(self, name, namespace, body):
        self.calls.append(("patch_secret", name, namespace, body))
        if "patch_secret_404" in self.fail_on:
            exc = RuntimeError("simulated secret not found")
            exc.status = 404
            raise exc
        if "patch_secret" in self.fail_on:
            raise RuntimeError("simulated patch secret failure")
        return body

    def delete_namespaced_secret(self, name, namespace):
        self.deleted.append(("secret", name))

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


def test_status_patch_accepts_snake_case_from_real_client() -> None:
    """B2 真集群实测：官方 client to_dict() 返回 snake_case（available_replicas）."""
    patch = status_patch_from_deployment({"spec": {"replicas": 2}, "status": {"available_replicas": 2}})
    assert patch["status"] == "RUNNING"
    patch2 = status_patch_from_deployment({"spec": {"replicas": 3}, "status": {"available_replicas": 1}})
    assert patch2["status"] == "DEPLOYING" and patch2["replicas"] == 1


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
    # G 线：Secret 先于 Deployment（secretKeyRef 在 Pod 创建时解析）
    assert kinds == ["create_secret", "create_deploy", "create_svc"]


def test_k8s_deploy_failure_marks_failed_and_cleans_up() -> None:
    orch, store, fake = _orch(fake=_FakeK8s(fail_on={"create_svc"}))
    svc = orch.deploy("t1", {"modelName": "m"})
    assert svc["status"] == "FAILED"
    assert "errorMessage" in svc
    assert fake.deleted, "deploy 失败必须 best-effort 清理残留"
    # reviewer P1：cleanup 必须用 per-service Secret 名（非 Deployment 名）——
    # 否则含模型 URL 的 Secret 永久残留（泄漏面不变量）
    assert ("secret", kio.model_url_secret_name(svc["id"])) in fake.deleted
    # 脱敏护栏（reviewer P3）：errorMessage 只含脱敏描述，不含 fake 异常原文
    assert "simulated" not in svc["errorMessage"]


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


@pytest.mark.asyncio
async def test_k8s_invoke_base_url_override(monkeypatch) -> None:
    """集群外开发调试：invoke 转发地址可用 port-forward 覆盖（生产走集群 DNS）."""
    store = FrontendContractStore()
    settings = _k8s_settings()
    settings.inferenceInvokeBaseUrl = "http://172.17.218.229:18080"
    orch = KubernetesInferenceOrchestrator(store, settings, k8s_client=_FakeK8s())
    svc = orch.deploy("t1", {"modelName": "m"})
    store.patch_inference("t1", svc["id"], {"status": "RUNNING"})
    monkeypatch.setattr(httpx, "AsyncClient", _FakeAsyncClient)
    result = await orch.invoke("t1", svc["id"], {"prompt": "hi"})
    assert result["text"] == "hello world"
    assert _FakeAsyncClient.last_url.startswith("http://172.17.218.229:18080/")


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


# ---------------------------------------------------------------- 多模型发布（模型仓解析）


def test_deployment_body_uses_resolved_model_url() -> None:
    """G 线：模型 URL 经 per-service Secret 注入（secretKeyRef）；不传 Secret 名则回退全局兜底明文 env."""
    svc = {"id": "svc-1", "modelName": "m", "desiredReplicas": 1}
    body = deployment_body(_k8s_settings(), "t1", svc, secret_name="llmops-inf-svc-1-model-url")
    init = body["spec"]["template"]["spec"]["initContainers"][0]
    ref = init["env"][0]["valueFrom"]["secretKeyRef"]
    assert ref["name"] == "llmops-inf-svc-1-model-url" and ref["key"] == "model-url"
    assert "value" not in init["env"][0]
    # URL 不拼进 shell 命令（防注入）——$MODEL_URL 引用保持
    assert "$MODEL_URL" in init["args"][0]
    body2 = deployment_body(_k8s_settings(), "t1", svc)
    init2 = body2["spec"]["template"]["spec"]["initContainers"][0]
    assert init2["env"][0]["value"] == _k8s_settings().inferenceModelUrl


def test_k8s_deploy_records_model_artifact_url() -> None:
    """G 线：deploy 先建 per-service Secret（URL 只落 Secret），视图记稳定引用，
    Deployment 用 secretKeyRef；signedAt 注解/视图字段就绪（reconcile 重签依赖）。"""
    orch, store, fake = _orch()
    svc = orch.deploy("t1", {"modelName": "m"}, model_url="https://models.example/own.gguf")
    assert svc["modelArtifactUrl"] == "https://models.example/own.gguf"  # 非签名 URL 原样
    assert svc.get("modelUrlSignedAt")  # 重签判定时间戳
    # Secret 先于 Deployment；URL 只在 Secret，不在 Deployment spec
    secret_call = next(call for call in fake.calls if call[0] == "create_secret")
    deploy_call = next(call for call in fake.calls if call[0] == "create_deploy")
    assert fake.calls.index(secret_call) < fake.calls.index(deploy_call)
    sbody = secret_call[2]
    assert sbody["stringData"]["model-url"] == "https://models.example/own.gguf"
    assert sbody["metadata"]["annotations"]["llmops.sq/model-url-signed-at"] == svc["modelUrlSignedAt"]
    init = deploy_call[2]["spec"]["template"]["spec"]["initContainers"][0]
    ref = init["env"][0]["valueFrom"]["secretKeyRef"]
    assert ref["key"] == "model-url" and ref["name"].endswith("-model-url")
    assert "https://models.example" not in str(deploy_call[2])  # URL 不落 Deployment manifest


@pytest.mark.asyncio
async def test_deploy_route_resolves_registered_model(monkeypatch) -> None:
    """路由集成：注册了 http artifactUri 的模型 ⇒ deploy 的 MODEL_URL 用注册值（多模型并存）."""
    import uuid as _uuid

    from llmops.models.model import ModelInfo, ModelType, ModelVersion

    monkeypatch.setenv("AUTH_MODE", "none")
    fake = _FakeK8s()
    monkeypatch.setattr(kio, "_RealK8sClient", lambda settings: fake)
    app = create_app(settings=_k8s_settings())
    model = await app.state.registry.modelService.register_model(
        ModelInfo(id=str(_uuid.uuid4()), name="own-model", type=ModelType.BASE)
    )
    await app.state.registry.modelService.add_model_version(
        model.id, ModelVersion(version=1, modelId=model.id, artifactUri="https://models.example/own.gguf")
    )
    client = TestClient(app)
    r = client.post(
        "/api/v1/llmops/inference-services",
        json={"modelName": "own-model", "modelVersion": "1", "replicas": 1},
    )
    assert r.status_code == 201, r.text
    assert r.json()["modelArtifactUrl"] == "https://models.example/own.gguf"
    sbody = next(call[2] for call in fake.calls if call[0] == "create_secret")
    assert sbody["stringData"]["model-url"] == "https://models.example/own.gguf"
    body = next(call[2] for call in fake.calls if call[0] == "create_deploy")
    init = body["spec"]["template"]["spec"]["initContainers"][0]
    assert init["env"][0]["valueFrom"]["secretKeyRef"]["key"] == "model-url"
    assert "https://models.example" not in str(body)  # URL 不落 Deployment manifest（G 线泄漏面规则）


# ---------------------------------------------------------------- G 线：稳定引用与重签


def _presigned_for(bucket: str, key: str, ttl: int = 3600) -> str:
    """测试用 presigned URL（伪造凭据拼接构造，仅驱动签名链，不可用）。"""
    from datetime import datetime, timezone

    from llmops.services.model_artifact import _parse_s3_uri, _presign_s3_get_object

    cfg = S3PresignConfig(
        endpoint_url="http://s3.internal:9000",
        region="us-east-1",
        access_key_id="TEST" + "EXAMPLEKEY" + "0" * 10,
        secret_access_key="TEST" + "EXAMPLESECRET" + "0" * 22,
        ttl_sec=ttl,
        path_style=True,
        allowed_buckets="",
    )
    return _presign_s3_get_object(_parse_s3_uri(f"s3://{bucket}/{key}"), cfg, now=datetime.now(timezone.utc))


def _seen_deploy(svc_id: str) -> dict:
    """构造 reconcile 在册 Deployment 实况（fake client 返回形状）."""
    return {
        "metadata": {
            "name": f"llmops-inf-{svc_id}",
            "labels": {kio.MANAGED_LABEL: "true", kio.SERVICE_ID_LABEL: svc_id},
            "annotations": {kio.TENANT_ANNOTATION: "t1"},
        },
        "spec": {"replicas": 1},
        "status": {"availableReplicas": 1},
    }


def test_stable_ref_for_presigned_and_plain() -> None:
    """视图稳定引用：presigned URL 反解 s3://bucket/key；普通 URL 原样；反解失败脱敏."""
    presigned = (
        "http://s3.internal:9000/demo-bucket/models/qwen.gguf?X-Amz-Algorithm=AWS4-HMAC-SHA256"
        "&X-Amz-Signature=abc123"
    )
    assert kio._stable_ref_for(presigned) == "s3://demo-bucket/models/qwen.gguf"
    assert kio._stable_ref_for("https://models.example/own.gguf") == "https://models.example/own.gguf"
    # 无法反解的签名 URL（如 virtual-host）⇒ 脱敏占位，绝不回显签名
    weird = "https://demo-bucket.s3.internal:9000/models/qwen.gguf?X-Amz-Signature=abc"
    assert "X-Amz-Signature" not in kio._stable_ref_for(weird)


def test_reconcile_resigns_expired_s3_secret() -> None:
    """signedAt 过 TTL/2 ⇒ 盲 patch Secret（新签名）+ 视图 signedAt 原子更新."""
    orch, store, fake = _orch()
    presigned = _presigned_for("demo-bucket", "models/qwen.gguf")
    svc = orch.deploy("t1", {"modelName": "m"}, model_url=presigned)
    assert svc["modelArtifactUrl"] == "s3://demo-bucket/models/qwen.gguf"  # 稳定引用
    assert "X-Amz-Signature" not in svc["modelArtifactUrl"]  # 签名不进视图
    from datetime import datetime, timedelta, timezone

    old = (datetime.now(timezone.utc) - timedelta(seconds=1800)).isoformat()
    store.patch_inference("t1", svc["id"], {"modelUrlSignedAt": old})
    fake.list_items = [_seen_deploy(svc["id"])]
    orch.reconcile()
    patches = [c for c in fake.calls if c[0] == "patch_secret"]
    assert len(patches) == 1
    new_url = patches[0][3]["stringData"]["model-url"]
    assert "X-Amz-Signature=" in new_url
    updated = orch._find("t1", svc["id"])
    assert updated["modelUrlSignedAt"] != old  # 视图 signedAt 原子更新


def test_resign_round_trip_encoded_key() -> None:
    """reviewer P2 round-trip：key 含 + 等保留字符时，重签 URL 解码必须回到原 key
    （旧实现从 URL path 原样截取已编码 key ⇒ 重签二次编码 ⇒ NoSuchKey）."""
    orch, store, fake = _orch()
    presigned = _presigned_for("demo-bucket", "models/ver1.0+v2(final).gguf")
    svc = orch.deploy("t1", {"modelName": "m"}, model_url=presigned)
    assert svc["modelArtifactUrl"] == "s3://demo-bucket/models/ver1.0+v2(final).gguf"
    from datetime import datetime, timedelta, timezone
    from urllib.parse import unquote, urlparse

    old = (datetime.now(timezone.utc) - timedelta(seconds=1800)).isoformat()
    store.patch_inference("t1", svc["id"], {"modelUrlSignedAt": old})
    fake.list_items = [_seen_deploy(svc["id"])]
    orch.reconcile()
    patches = [c for c in fake.calls if c[0] == "patch_secret"]
    assert len(patches) == 1
    new_url = patches[0][3]["stringData"]["model-url"]
    path = unquote(urlparse(new_url).path).lstrip("/")
    assert path == "demo-bucket/models/ver1.0+v2(final).gguf"  # round-trip 闭环
    assert "%252B" not in new_url  # 双重编码特征


def test_deploy_fails_fast_when_stable_ref_unresolvable() -> None:
    """reviewer P2：签名 URL 反解失败（virtual-host 形态）⇒ deploy fail-fast 422，
    部署期暴露配置不匹配，而非 TTL 后重签静默失效断链."""
    orch, store, fake = _orch()
    weird_signed = "https://demo-bucket.s3.internal:9000/models/qwen.gguf?X-Amz-Signature=abc"
    with pytest.raises(OrchestratorError) as exc_info:
        orch.deploy("t1", {"modelName": "m"}, model_url=weird_signed)
    assert exc_info.value.status_code == 422
    svc = store.list_inference("t1")[0]
    assert svc["status"] == "FAILED"
    assert not [c for c in fake.calls if c[0] == "create_secret"]  # 未建任何集群资源


def test_reconcile_skips_fresh_signed_at() -> None:
    """signedAt 新鲜（< TTL/2）⇒ 即便引用是 s3:// 也不重签（reviewer 点名缺口）."""
    orch, store, fake = _orch()
    presigned = _presigned_for("demo-bucket", "models/qwen.gguf")
    svc = orch.deploy("t1", {"modelName": "m"}, model_url=presigned)
    assert svc["modelArtifactUrl"].startswith("s3://")
    fake.list_items = [_seen_deploy(svc["id"])]
    orch.reconcile()  # signedAt = deploy 刚写入（新鲜）
    assert not [c for c in fake.calls if c[0] == "patch_secret"]


def test_reconcile_skips_fresh_signed_or_static_urls() -> None:
    """signedAt 新鲜（< TTL/2）或静态 http(s) 引用 ⇒ 不触发重签."""
    orch, store, fake = _orch()
    svc = orch.deploy("t1", {"modelName": "m"}, model_url="https://models.example/own.gguf")
    fake.list_items = [_seen_deploy(svc["id"])]
    orch.reconcile()
    assert not [c for c in fake.calls if c[0] == "patch_secret"]  # 静态 URL 不重签


def test_reconcile_recreates_secret_on_404() -> None:
    """重签 patch 404（Secret 被外部删）⇒ create 重建（零 get 权限语义）."""
    orch, store, fake = _orch(fake=_FakeK8s(fail_on={"patch_secret_404"}))
    presigned = _presigned_for("demo-bucket", "models/qwen.gguf")
    svc = orch.deploy("t1", {"modelName": "m"}, model_url=presigned)
    from datetime import datetime, timedelta, timezone

    old = (datetime.now(timezone.utc) - timedelta(seconds=1800)).isoformat()
    store.patch_inference("t1", svc["id"], {"modelUrlSignedAt": old})
    fake.list_items = [_seen_deploy(svc["id"])]
    orch.reconcile()
    assert [c for c in fake.calls if c[0] == "patch_secret"]  # patch 尝试发生
    creates = [c for c in fake.calls if c[0] == "create_secret"]
    assert len(creates) == 2  # deploy 一次 + 404 重建一次
    assert "X-Amz-Signature=" in creates[1][2]["stringData"]["model-url"]
