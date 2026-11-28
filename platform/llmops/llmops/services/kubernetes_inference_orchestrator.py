"""Kubernetes 真推理编排器（B 线）.

deploy/scale/stop 落到真实集群，视图状态由 reconcile 按集群实况对账改写：
- deploy：先建 **per-service 模型 URL Secret**（G 线：模型 URL 含 presigned
  签名等效短期凭据，禁止明文落 Deployment spec/etcd）再建 Deployment
  （initContainer 经 secretKeyRef 取 $MODEL_URL 下载模型）+ Service，
  视图先落 DEPLOYING；创建失败 ⇒ FAILED + errorMessage 并 best-effort 清理残留；
- scale/stop：走 scale subresource；desiredReplicas 立即写、replicas 待对账；
  **stop 是 scale 0 不是删除**——Secret 与 Deployment 存亡绑定，stop 不删 Secret
  （scale 回来时 Pod 重新解析 secretKeyRef 需要 Secret 在）；
- invoke：仅 RUNNING 可调用，转发到集群内 Service 的 llama.cpp /completion；
- reconcile：按 managed label 列举 Deployment 对账；store 有而集群没有的非终态
  记录 ⇒ FAILED（不掩盖集群外删除漂移）；对视图记录为 ``s3://`` 稳定引用的
  服务做 **presigned URL 重签**（视图 signedAt 剩余 < TTL/2 时盲 patch Secret，
  单次 patch 原子更新 data+signedAt 注解；不读 Secret、不 list secrets）。

安全边界（G 线，security 复审 P1 泄漏面硬规则）：
- presigned URL 只经内存进 Secret；日志与视图 ``errorMessage`` 不含签名
  （K8s 异常消息可能内嵌请求体，写日志/视图前只记异常类别与资源名）；
- 视图 ``modelArtifactUrl`` 对 s3 形态记 ``s3://bucket/key`` 稳定引用
  （API 回显零签名泄漏；同时兼任重签触发键）。

K8s client 可注入（单元测试 fake；生产为官方 kubernetes 包适配层，初始化探活
/version fail-fast，不可达不静默降级为契约级）。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
import logging
from typing import Any, Optional
from urllib.parse import unquote, urlparse

import httpx

from llmops.config.settings import Settings
from llmops.services.frontend_store import FrontendContractStore
from llmops.services.inference_orchestrator import (
    MANAGED_LABEL,
    SERVICE_ID_LABEL,
    TENANT_ANNOTATION,
    TENANT_LABEL,
    OrchestratorError,
    cluster_service_url,
    deployment_name,
    tenant_tag,
)
from llmops.services.model_artifact import S3PresignConfig, _log_safe, _parse_s3_uri, _presign_s3_get_object

logger = logging.getLogger(__name__)

_INFERENCE_PORT = 8000
_MODEL_URL_SECRET_KEY = "model-url"
_SIGNED_AT_ANNOTATION = "llmops.sq/model-url-signed-at"


def model_url_secret_name(svc_id: str) -> str:
    """per-service 模型 URL Secret 名（从既有 deployment_name 派生，单点归一）."""
    return f"{deployment_name(svc_id)}-model-url"


def secret_body(
    tenant: str,
    svc: dict[str, Any],
    model_url: str,
    signed_at: str,
) -> dict[str, Any]:
    """构造 per-service 模型 URL Secret（stringData；签发时间进注解供重签判定）.

    ``model_url`` 为解析后的下载 URL（可能含 X-Amz-Signature 签名 = 短期凭据）——
    只落 Secret，不落 Deployment spec、不进日志、不进视图。
    """
    svc_id = str(svc["id"])
    return {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {
            "name": model_url_secret_name(svc_id),
            "namespace": "",  # 由调用方填 namespace（与 Deployment 同 ns）
            "labels": {
                MANAGED_LABEL: "true",
                SERVICE_ID_LABEL: svc_id,
                TENANT_LABEL: tenant_tag(tenant),
            },
            "annotations": {TENANT_ANNOTATION: tenant, _SIGNED_AT_ANNOTATION: signed_at},
        },
        "stringData": {_MODEL_URL_SECRET_KEY: model_url},
    }


def _stable_ref_for(url: Optional[str]) -> str:
    """视图稳定引用：presigned URL（含 X-Amz-Signature）反解为 s3://bucket/key，
    其余形态原样（http(s) 直链/MLflow tracking URL 本身非短期凭据）.

    反解必须 **unquote**：presigned URL 的 path 是单次编码形态（如 ``a%2Bb``），
    直接截取会把已编码 key 存进视图，重签时二次编码 ⇒ NoSuchKey（reviewer P2
    round-trip 缺陷）。unquote 后重组 ``s3://bucket/key`` 并**过对象键白名单复核**：
    复核不过（virtual-host 等反解失败形态）⇒ 返回占位串，调用方 deploy 会
    fail-fast 暴露配置与形态不匹配，而非 TTL 后静默断链。
    """
    text = (url or "").strip()
    if "X-Amz-Signature=" not in text:
        return text
    try:
        path = unquote(urlparse(text).path).lstrip("/")
        bucket, _, key = path.partition("/")
        if bucket and key and _parse_s3_uri(f"s3://{bucket}/{key}") is not None:
            return f"s3://{bucket}/{key}"
    except Exception:  # noqa: BLE001  反解失败走占位（调用方 fail-fast）
        pass
    return "已签名模型 URL（细节见集群内 Secret）"


def status_patch_from_deployment(dep: dict[str, Any]) -> dict[str, Any]:
    """Deployment 实况 → 视图补丁（reconcile 唯一状态映射口径）."""
    spec = dep.get("spec", {}) or {}
    st = dep.get("status", {}) or {}
    desired = int(spec.get("replicas") or 0)
    # 官方 kubernetes client 的 to_dict() 把 JSON 的 camelCase 转成 Python 属性名
    # snake_case（availableReplicas → available_replicas）；B2 真集群实测抓到：
    # 只读 camelCase 会恒得 0 ⇒ 状态永远 DEPLOYING。两形态都兼容。
    available = int(st.get("availableReplicas", st.get("available_replicas")) or 0)
    if desired == 0:
        return {"status": "STOPPED", "replicas": 0, "desiredReplicas": 0}
    if available >= desired:
        return {"status": "RUNNING", "replicas": available, "desiredReplicas": desired}
    return {"status": "DEPLOYING", "replicas": available, "desiredReplicas": desired}


def deployment_body(
    settings: Settings, tenant: str, svc: dict[str, Any], secret_name: Optional[str] = None
) -> dict[str, Any]:
    """构造推理 Deployment（initContainer 下载模型到 emptyDir，主容器起推理运行时）.

    模型 URL 经 **per-service Secret + secretKeyRef** 注入 initContainer（G 线改造：
    此前为明文 env value，presigned URL 等效短期凭据会落 etcd 与 ``kubectl get
    deploy -o yaml``）。``secret_name`` 为该服务模型 URL Secret 名（``deploy()``
    先建 Secret 再建 Deployment）；为空时回退全局兜底
    ``settings.inferenceModelUrl``（明文 env——该值为操作者配置信任边界）。
    运行时契约 = llama.cpp server（/completion 推理、/health 探针）。
    """
    svc_id = str(svc["id"])
    name = deployment_name(svc_id)
    ns = settings.inferenceNamespace
    labels = {
        MANAGED_LABEL: "true",
        SERVICE_ID_LABEL: svc_id,
        TENANT_LABEL: tenant_tag(tenant),
    }
    pod_labels = {MANAGED_LABEL: "true", SERVICE_ID_LABEL: svc_id}
    if secret_name:
        model_env: dict[str, Any] = {
            "name": "MODEL_URL",
            "valueFrom": {"secretKeyRef": {"name": secret_name, "key": _MODEL_URL_SECRET_KEY}},
        }
    else:
        model_env = {"name": "MODEL_URL", "value": settings.inferenceModelUrl}
    return {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {
            "name": name,
            "namespace": ns,
            "labels": labels,
            "annotations": {TENANT_ANNOTATION: tenant},
        },
        "spec": {
            "replicas": int(svc["desiredReplicas"]),
            "selector": {"matchLabels": {SERVICE_ID_LABEL: svc_id}},
            "template": {
                "metadata": {"labels": pod_labels},
                "spec": {
                    "initContainers": [
                        {
                            "name": "model-fetch",
                            "image": settings.inferenceInitImage,
                            "command": ["/bin/sh", "-c"],
                            "args": ['curl -fSL --retry 3 --max-time 900 "$MODEL_URL" -o /models/model.gguf'],
                            "env": [model_env],
                            "volumeMounts": [{"name": "model", "mountPath": "/models"}],
                        }
                    ],
                    "containers": [
                        {
                            "name": "inference",
                            "image": settings.inferenceImage,
                            "args": [
                                "--model",
                                "/models/model.gguf",
                                "--host",
                                "0.0.0.0",
                                "--port",
                                str(_INFERENCE_PORT),
                            ],
                            "env": [{"name": "LLMOPS_MODEL_NAME", "value": str(svc.get("modelName", ""))}],
                            "ports": [{"containerPort": _INFERENCE_PORT}],
                            "volumeMounts": [{"name": "model", "mountPath": "/models"}],
                            "resources": {
                                "requests": {"cpu": "250m", "memory": "512Mi"},
                                "limits": {"cpu": "2", "memory": "2Gi"},
                            },
                            "readinessProbe": {
                                "httpGet": {"path": "/health", "port": _INFERENCE_PORT},
                                "initialDelaySeconds": 5,
                                "periodSeconds": 10,
                                "failureThreshold": 60,
                            },
                        }
                    ],
                    "volumes": [{"name": "model", "emptyDir": {}}],
                },
            },
        },
    }


def service_body(settings: Settings, tenant: str, svc: dict[str, Any]) -> dict[str, Any]:
    """构造 ClusterIP Service（invoke 转发入口）."""
    svc_id = str(svc["id"])
    return {
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": {
            "name": deployment_name(svc_id),
            "namespace": settings.inferenceNamespace,
            "labels": {
                MANAGED_LABEL: "true",
                SERVICE_ID_LABEL: svc_id,
                TENANT_LABEL: tenant_tag(tenant),
            },
            "annotations": {TENANT_ANNOTATION: tenant},
        },
        "spec": {
            "selector": {SERVICE_ID_LABEL: svc_id},
            "ports": [{"port": 80, "targetPort": _INFERENCE_PORT}],
        },
    }


class _RealK8sClient:
    """官方 kubernetes 包适配层（返回值统一 to_dict，便于与 fake 同形）."""

    def __init__(self, settings: Settings) -> None:
        from kubernetes import client, config

        if settings.inferenceKubeconfig.strip():
            config.load_kube_config(config_file=settings.inferenceKubeconfig)
        else:
            config.load_incluster_config()
        self._apps = client.AppsV1Api()
        self._core = client.CoreV1Api()
        # 探活 fail-fast：API 不可达即抛，不静默降级契约级（生产真编排契约）。
        client.VersionApi().get_code()

    def create_namespaced_deployment(self, namespace: str, body: dict) -> dict:
        return self._apps.create_namespaced_deployment(namespace=namespace, body=body).to_dict()

    def create_namespaced_service(self, namespace: str, body: dict) -> dict:
        return self._core.create_namespaced_service(namespace=namespace, body=body).to_dict()

    def create_namespaced_secret(self, namespace: str, body: dict) -> dict:
        return self._core.create_namespaced_secret(namespace=namespace, body=body).to_dict()

    def patch_namespaced_secret(self, name: str, namespace: str, body: dict) -> dict:
        # merge patch 盲写：不存在时 K8s 返回 404（无 get 权限的 create-or-patch 语义
        # 由调用方按 404 落 create 处理）。
        return self._core.patch_namespaced_secret(name=name, namespace=namespace, body=body).to_dict()

    def delete_namespaced_secret(self, name: str, namespace: str) -> None:
        self._core.delete_namespaced_secret(name=name, namespace=namespace)

    def patch_namespaced_deployment_scale(self, name: str, namespace: str, body: dict) -> dict:
        return self._apps.patch_namespaced_deployment_scale(name=name, namespace=namespace, body=body).to_dict()

    def list_namespaced_deployment(self, namespace: str, label_selector: str) -> dict:
        return self._apps.list_namespaced_deployment(namespace=namespace, label_selector=label_selector).to_dict()

    def delete_namespaced_deployment(self, name: str, namespace: str) -> None:
        self._apps.delete_namespaced_deployment(name=name, namespace=namespace)

    def delete_namespaced_service(self, name: str, namespace: str) -> None:
        self._core.delete_namespaced_service(name=name, namespace=namespace)


class KubernetesInferenceOrchestrator:
    """真 K8s 推理编排（状态以集群实况为准，视图是对账结果而非命令回显）."""

    def __init__(self, store: FrontendContractStore, settings: Settings, k8s_client: Any = None) -> None:
        self._store = store
        self._settings = settings
        self._ns = settings.inferenceNamespace
        self._k8s = k8s_client if k8s_client is not None else _RealK8sClient(settings)

    # ---------------------------------------------------------------- 内部工具
    def _find(self, tenant: str, service_id: str) -> Optional[dict[str, Any]]:
        return next((s for s in self._store.list_inference(tenant) if s.get("id") == service_id), None)

    @staticmethod
    def _describe_exc(exc: Exception) -> str:
        """异常脱敏描述（security P1）：只记类别与状态码，不含异常消息——
        K8s ApiException 消息可能内嵌请求体（含模型 URL/签名）。"""
        status = getattr(exc, "status", None)
        return f"{type(exc).__name__}({status})" if status is not None else type(exc).__name__

    def _s3_cfg(self) -> S3PresignConfig:
        s = self._settings
        return S3PresignConfig(
            endpoint_url=s.artifactS3EndpointUrl.strip(),
            region=s.artifactS3Region.strip(),
            access_key_id=s.artifactS3AccessKeyId.strip(),
            secret_access_key=s.artifactS3SecretAccessKey.strip(),
            ttl_sec=s.artifactS3PresignTtlSec,
            path_style=s.artifactS3PathStyle,
            allowed_buckets=s.artifactS3AllowedBuckets,
        )

    def _cleanup(self, name: str, secret_name: Optional[str] = None) -> None:
        """deploy 失败后的残留清理（best-effort，清理失败仅记日志不掩盖原错误）.

        ``secret_name`` 必须传 per-service Secret 名（``model_url_secret_name(svc_id)``，
        与 Deployment 名不同后缀——reviewer P1：此前复用 Deployment 名导致
        delete 永远 404，含 URL 的 Secret 永久残留）。
        注意：stop（scale 0）不走这里——Secret 与 Deployment 存亡绑定，
        scale 回来时 Pod 需要重新解析 secretKeyRef。
        """
        deletions: list[tuple[str, str]] = [
            ("delete_namespaced_service", name),
            ("delete_namespaced_deployment", name),
        ]
        if secret_name:
            deletions.append(("delete_namespaced_secret", secret_name))
        for fn_name, target in deletions:
            fn = getattr(self._k8s, fn_name, None)
            if fn is None:
                continue
            try:
                fn(name=target, namespace=self._ns)
            except Exception as exc:  # noqa: BLE001
                logger.warning("残留清理 %s(%s) 失败: %s", _log_safe(fn_name), _log_safe(target), type(exc).__name__)

    # ---------------------------------------------------------------- 编排操作
    def deploy(self, tenant: str, payload: dict[str, Any], model_url: Optional[str] = None) -> dict[str, Any]:
        svc = self._store.deploy_inference(tenant, payload, initial_status="DEPLOYING")
        svc_id = str(svc["id"])
        url_to_use = model_url or self._settings.inferenceModelUrl
        stable_ref = _stable_ref_for(url_to_use)
        # fail-fast（reviewer P2，含 host 逐字节断言——security 裁决 B）：签名 URL
        # 无法反解出稳定引用（如 path_style=false 的 virtual-host 形态，bucket 在
        # host 里反解是猜）或 host 与配置端点不一致 ⇒ reconcile 重签机制对该服务
        # 失效 ⇒ TTL 后静默断链。部署期暴露配置与形态不匹配，而不是运行期坏掉。
        if "X-Amz-Signature=" in url_to_use:
            cfg = self._s3_cfg()
            url_host = urlparse(url_to_use).netloc
            host_ok = cfg.is_configured() and url_host == urlparse(cfg.endpoint_url).netloc
            if not host_ok or not stable_ref.startswith("s3://"):
                self._store.patch_inference(
                    tenant,
                    svc_id,
                    {
                        "status": "FAILED",
                        "errorMessage": "presigned 模型 URL 无法建立稳定引用（检查 artifactS3PathStyle/端点配置）",
                    },
                )
                raise OrchestratorError(
                    "presigned 模型 URL 无法建立稳定引用（重签机制失效，检查 artifactS3PathStyle/端点配置）",
                    status_code=422,
                )
        # 泄漏面硬规则（security P1）：视图只记稳定引用——presigned URL 反解为
        # s3://bucket/key（兼任 reconcile 重签触发键），签名不进视图/PG/API 回显。
        signed_at = datetime.now(timezone.utc).isoformat()
        svc = (
            self._store.patch_inference(
                tenant,
                svc_id,
                {"modelArtifactUrl": stable_ref, "modelUrlSignedAt": signed_at},
            )
            or svc
        )
        name = deployment_name(svc_id)
        secret_name = model_url_secret_name(svc_id)
        try:
            # Secret 先于 Deployment（secretKeyRef 在 Pod 创建时解析）；
            # Deployment 引用 Secret 名，签名 URL 只落 Secret。
            sbody = secret_body(tenant, svc, url_to_use, signed_at)
            sbody["metadata"]["namespace"] = self._ns
            self._k8s.create_namespaced_secret(namespace=self._ns, body=sbody)
            self._k8s.create_namespaced_deployment(
                namespace=self._ns, body=deployment_body(self._settings, tenant, svc, secret_name=secret_name)
            )
            self._k8s.create_namespaced_service(namespace=self._ns, body=service_body(self._settings, tenant, svc))
        except Exception as exc:  # noqa: BLE001  K8s API 异常 → 真实上报 FAILED
            # 日志与 errorMessage 只记脱敏描述（不含异常消息体——可能内嵌签名 URL）。
            logger.warning("推理服务 %s 集群创建失败: %s", _log_safe(svc_id), self._describe_exc(exc))
            self._cleanup(name, secret_name)
            return self._store.patch_inference(
                tenant,
                svc_id,
                {"status": "FAILED", "errorMessage": f"集群创建失败: {self._describe_exc(exc)}"},
            )
        return svc

    def stop(self, tenant: str, service_id: str) -> Optional[dict[str, Any]]:
        svc = self._find(tenant, service_id)
        if svc is None:
            return None
        name = deployment_name(service_id)
        try:
            self._k8s.patch_namespaced_deployment_scale(name=name, namespace=self._ns, body={"spec": {"replicas": 0}})
        except Exception as exc:  # noqa: BLE001
            raise OrchestratorError(f"停止推理服务失败（集群 scale 0 未生效）: {exc}") from exc
        return self._store.patch_inference(tenant, service_id, {"status": "STOPPED", "desiredReplicas": 0})

    def scale(self, tenant: str, service_id: str, replicas: int) -> Optional[dict[str, Any]]:
        svc = self._find(tenant, service_id)
        if svc is None:
            return None
        name = deployment_name(service_id)
        try:
            self._k8s.patch_namespaced_deployment_scale(
                name=name, namespace=self._ns, body={"spec": {"replicas": replicas}}
            )
        except Exception as exc:  # noqa: BLE001
            raise OrchestratorError(f"扩缩容失败（集群 scale {replicas} 未生效）: {exc}") from exc
        # desiredReplicas 立即写；replicas/status 由 reconcile 按集群实况对账。
        return self._store.patch_inference(
            tenant,
            service_id,
            {"desiredReplicas": replicas, "status": "STOPPED" if replicas == 0 else "DEPLOYING"},
        )

    async def invoke(self, tenant: str, service_id: str, payload: dict[str, Any]) -> Optional[dict[str, Any]]:
        svc = self._find(tenant, service_id)
        if svc is None:
            return None
        if svc.get("status") != "RUNNING":
            raise OrchestratorError(
                f"推理服务未就绪（当前 status={svc.get('status')}），请待 RUNNING 后调用",
                status_code=409,
            )
        # 集群外开发调试：可用 port-forward 地址覆盖（生产留空走集群 DNS）。
        # override 同样仅允许 http/https（SSRF 白名单；操作者配置信任边界）。
        override = self._settings.inferenceInvokeBaseUrl.strip()
        if override:
            if not override.lower().startswith(("http://", "https://")):
                raise OrchestratorError("LLMOPS_INFERENCE_INVOKE_BASE_URL 仅允许 http/https", status_code=500)
            base_url = override.rstrip("/")
        else:
            base_url = cluster_service_url(service_id, self._ns)
        body = {
            "prompt": str(payload.get("prompt") or ""),
            "n_predict": int(payload.get("maxTokens") or 128),
        }
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(180.0, connect=5.0)) as cli:
                resp = await cli.post(f"{base_url}/completion", json=body)
                resp.raise_for_status()
                data = resp.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise OrchestratorError(f"推理运行时调用失败: {exc}") from exc
        return {
            "serviceId": service_id,
            "model": svc.get("modelName"),
            "text": data.get("content", ""),
            "raw": data,
        }

    # ---------------------------------------------------------------- 对账
    def _resign_if_needed(self, tenant: str, svc_id: str, svc: dict[str, Any]) -> None:
        """s3 稳定引用服务的 presigned URL 重签（reconcile 每轮调用）.

        触发键 = 视图 ``modelArtifactUrl`` 前缀 ``s3://``（deploy 写入的稳定引用，
        兼任「是否需要重签」标记——静态 http(s)/runs:/ URL 跳过）。判定用视图
        ``modelUrlSignedAt``（deploy 写入，非敏感时间戳）剩余 < TTL/2 即重签，
        **不读 Secret、不 list secrets**（RBAC 最小化：create/patch/delete 无 get）。
        单次 patch 原子更新 stringData + signedAt 注解（避免「新 URL+旧时间」中间态）；
        patch 404（Secret 被外部删）⇒ create 重建。正在 RUNNING 的 Pod 不受重签
        影响（模型已在 emptyDir；env 仅容器启动时解析），重签只服务未来 Pod 启动。
        失败仅记类别日志不改服务状态，下轮重试——不掩盖集群实况对账语义。
        """
        ref = str(svc.get("modelArtifactUrl") or "")
        if not ref.startswith("s3://"):
            return
        cfg = self._s3_cfg()
        if not cfg.is_configured():
            return
        now = datetime.now(timezone.utc)
        signed_at: Optional[datetime] = None
        raw = str(svc.get("modelUrlSignedAt") or "")
        try:
            signed_at = datetime.fromisoformat(raw)
        except ValueError:
            signed_at = None
        if signed_at is not None and signed_at.tzinfo is not None:
            if now - signed_at < timedelta(seconds=max(cfg.ttl_sec // 2, 1)):
                return  # 未到重签窗口
        loc = _parse_s3_uri(ref)
        if loc is None:
            logger.warning("重签跳过（svc=%s）：稳定引用未通过对象键白名单", svc_id)
            return
        # 白名单复检（reviewer P3）：运维收紧 ALLOWED_BUCKETS 后既有服务不再续签
        allowed = cfg.allowed_bucket_set
        if allowed and loc.bucket not in allowed:
            logger.warning("重签跳过（svc=%s）：bucket 不在收紧后的白名单内", svc_id)
            return
        try:
            url = _presign_s3_get_object(loc, cfg, now=now)
            patch = {
                "stringData": {_MODEL_URL_SECRET_KEY: url},
                "metadata": {"annotations": {_SIGNED_AT_ANNOTATION: now.isoformat()}},
            }
            try:
                self._k8s.patch_namespaced_secret(name=model_url_secret_name(svc_id), namespace=self._ns, body=patch)
            except Exception as patch_exc:  # noqa: BLE001
                if getattr(patch_exc, "status", None) == 404:
                    sbody = secret_body(tenant, svc, url, now.isoformat())
                    sbody["metadata"]["namespace"] = self._ns
                    self._k8s.create_namespaced_secret(namespace=self._ns, body=sbody)
                else:
                    raise
            # signedAt 原子进视图（独立字段，不改 status_patch_from_deployment 状态口径）
            self._store.patch_inference(tenant, svc_id, {"modelUrlSignedAt": now.isoformat()})
        except Exception as exc:  # noqa: BLE001
            logger.warning("重签失败（svc=%s, %s），下轮重试", svc_id, type(exc).__name__)

    def reconcile(self) -> None:
        """按 managed label 对账：集群实况 → 视图；集群外删除 ⇒ FAILED（不掩盖）."""
        try:
            resp = self._k8s.list_namespaced_deployment(namespace=self._ns, label_selector=f"{MANAGED_LABEL}=true")
        except Exception as exc:  # noqa: BLE001
            logger.warning("reconcile 列举 Deployment 失败（下轮重试）: %s", type(exc).__name__)
            return
        items = resp.get("items", []) if isinstance(resp, dict) else getattr(resp, "items", [])
        seen: set[tuple[str, str]] = set()
        for dep in items:
            meta = dep.get("metadata", {}) or {}
            labels = meta.get("labels", {}) or {}
            annotations = meta.get("annotations", {}) or {}
            svc_id = labels.get(SERVICE_ID_LABEL)
            tenant = annotations.get(TENANT_ANNOTATION)
            if not svc_id or tenant is None:
                continue
            seen.add((tenant, svc_id))
            self._store.patch_inference(tenant, svc_id, status_patch_from_deployment(dep))
        for svc in self._store.list_inference_all():
            key = (svc.get("tenantId"), svc.get("id"))
            if key in seen:
                # G 线重签：集群在册的 s3 引用服务（含 STOPPED——scale 回来需要有效 URL）
                self._resign_if_needed(str(svc.get("tenantId")), str(svc.get("id")), svc)
                continue
            if svc.get("status") in ("STOPPED", "FAILED"):
                continue
            self._store.patch_inference(
                svc["tenantId"],
                svc["id"],
                {"status": "FAILED", "errorMessage": "集群中未找到对应 Deployment（可能被外部删除）"},
            )
