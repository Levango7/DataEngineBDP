"""Kubernetes 真推理编排器（B 线）.

deploy/scale/stop 落到真实集群，视图状态由 reconcile 按集群实况对账改写：
- deploy：建 Deployment（initContainer 下载模型 + 推理运行时主容器）+ Service，
  视图先落 DEPLOYING；创建失败 ⇒ FAILED + errorMessage 并 best-effort 清理残留；
- scale/stop：走 scale subresource；desiredReplicas 立即写、replicas 待对账；
- invoke：仅 RUNNING 可调用，转发到集群内 Service 的 llama.cpp /completion；
- reconcile：按 managed label 列举 Deployment 对账；store 有而集群没有的非终态
  记录 ⇒ FAILED（不掩盖集群外删除漂移）。

K8s client 可注入（单元测试 fake；生产为官方 kubernetes 包适配层，初始化探活
/version fail-fast，不可达不静默降级为契约级）。
"""

from __future__ import annotations

import logging
from typing import Any, Optional

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

logger = logging.getLogger(__name__)

_INFERENCE_PORT = 8000


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


def deployment_body(settings: Settings, tenant: str, svc: dict[str, Any]) -> dict[str, Any]:
    """构造推理 Deployment（initContainer 下载模型到 emptyDir，主容器起推理运行时）.

    模型 URL 经 env 传入 initContainer（$MODEL_URL 引用，不做 shell 拼接注入）；
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
                            "env": [{"name": "MODEL_URL", "value": settings.inferenceModelUrl}],
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

    def _cleanup(self, name: str) -> None:
        """deploy 失败后的残留清理（best-effort，清理失败仅记日志不掩盖原错误）."""
        for fn_name in ("delete_namespaced_service", "delete_namespaced_deployment"):
            fn = getattr(self._k8s, fn_name, None)
            if fn is None:
                continue
            try:
                fn(name=name, namespace=self._ns)
            except Exception as exc:  # noqa: BLE001
                logger.warning("残留清理 %s(%s) 失败: %s", fn_name, name, exc)

    # ---------------------------------------------------------------- 编排操作
    def deploy(self, tenant: str, payload: dict[str, Any]) -> dict[str, Any]:
        svc = self._store.deploy_inference(tenant, payload, initial_status="DEPLOYING")
        svc_id = str(svc["id"])
        name = deployment_name(svc_id)
        try:
            self._k8s.create_namespaced_deployment(
                namespace=self._ns, body=deployment_body(self._settings, tenant, svc)
            )
            self._k8s.create_namespaced_service(namespace=self._ns, body=service_body(self._settings, tenant, svc))
        except Exception as exc:  # noqa: BLE001  K8s API 异常 → 真实上报 FAILED
            logger.warning("推理服务 %s 集群创建失败: %s", svc_id, exc)
            self._cleanup(name)
            return self._store.patch_inference(
                tenant,
                svc_id,
                {"status": "FAILED", "errorMessage": f"集群创建失败: {exc}"[:500]},
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
    def reconcile(self) -> None:
        """按 managed label 对账：集群实况 → 视图；集群外删除 ⇒ FAILED（不掩盖）."""
        try:
            resp = self._k8s.list_namespaced_deployment(namespace=self._ns, label_selector=f"{MANAGED_LABEL}=true")
        except Exception as exc:  # noqa: BLE001
            logger.warning("reconcile 列举 Deployment 失败（下轮重试）: %s", exc)
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
            if key in seen or svc.get("status") in ("STOPPED", "FAILED"):
                continue
            self._store.patch_inference(
                svc["tenantId"],
                svc["id"],
                {"status": "FAILED", "errorMessage": "集群中未找到对应 Deployment（可能被外部删除）"},
            )
