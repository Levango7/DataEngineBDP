"""推理服务编排器（B 线真推理编排）.

两种模式：
- ContractInferenceOrchestrator：契约级状态机（现状行为，development/test 默认）；
  deploy 即 RUNNING、invoke 诚实 501（契约级无真推理，不假装能调）。
- KubernetesInferenceOrchestrator（独立模块懒导入）：真 K8s 编排，production 强制；
  deploy/scale/stop 落到集群，状态由 reconcile 按集群实况对账改写，
  失败真实上报（FAILED + errorMessage），绝不回退契约级假 RUNNING。

信任边界（SSRF）：K8s API 地址来自 kubeconfig / in-cluster（操作者配置）；
invoke 的 Service DNS 名由本模块按 store 内可信记录的 service_id 经 DNS-1123
归一构造（白名单字符 + 集群域名后缀校验），用户输入只有 prompt（进请求体，不进 URL）。
"""

from __future__ import annotations

import hashlib
import logging
import re
from typing import Any, Optional

from llmops.config.settings import Settings
from llmops.services.frontend_store import FrontendContractStore

logger = logging.getLogger(__name__)

MANAGED_LABEL = "llmops.io/managed"
SERVICE_ID_LABEL = "llmops.io/service-id"
TENANT_LABEL = "llmops.io/tenant"
TENANT_ANNOTATION = "llmops.io/tenant-id"

_DNS1123_RE = re.compile(r"^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")
_CLUSTER_DNS_SUFFIX = ".svc.cluster.local"


class OrchestratorError(Exception):
    """编排/调用失败（真实上报；status_code 由路由映射为 HTTP 响应）."""

    def __init__(self, message: str, *, status_code: int = 502) -> None:
        super().__init__(message)
        self.status_code = status_code


def dns1123(value: str) -> str:
    """把任意标识归一成 DNS-1123 合法名（小写、非法字符折叠为 '-'，≤63 字符）."""
    v = re.sub(r"[^a-z0-9-]+", "-", value.lower()).strip("-")
    v = re.sub(r"-{2,}", "-", v)[:63].rstrip("-")
    if not v or not _DNS1123_RE.match(v):
        raise ValueError(f"无法归一为 DNS-1123 名: {value!r}")
    return v


def tenant_tag(tenant: str) -> str:
    """租户标识 → label 安全值（原文可能含大写/下划线 ⇒ 取哈希，原文存 annotation）."""
    return hashlib.sha256(tenant.encode("utf-8")).hexdigest()[:16]


def deployment_name(service_id: str) -> str:
    """推理服务 K8s 资源名（Deployment 与 Service 同名）."""
    return f"llmops-inf-{dns1123(service_id)}"[:63].rstrip("-")


def cluster_service_url(service_id: str, namespace: str) -> str:
    """集群内 Service DNS URL（SSRF 防御：逐段白名单 + 域名后缀校验）.

    host 不由用户输入直接拼接：service_id 经 DNS-1123 归一（仅 [a-z0-9-]），
    namespace 同规则校验，固定 .svc.cluster.local 后缀（集群内部信任边界，
    非 localhost/环回字面量；私有集群 DNS 属操作者配置）。
    """
    name = deployment_name(service_id)
    ns = dns1123(namespace)
    host = f"{name}.{ns}{_CLUSTER_DNS_SUFFIX}"
    if not re.fullmatch(r"[a-z0-9]([a-z0-9.-]*[a-z0-9])?", host) or not host.endswith(_CLUSTER_DNS_SUFFIX):
        raise OrchestratorError(f"非法集群 Service host: {host!r}", status_code=500)
    return f"http://{host}"


class ContractInferenceOrchestrator:
    """契约级状态机（与既有前端契约逐字段一致；development/test 默认）."""

    def __init__(self, store: FrontendContractStore) -> None:
        self._store = store

    def deploy(self, tenant: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self._store.deploy_inference(tenant, payload)

    def stop(self, tenant: str, service_id: str) -> Optional[dict[str, Any]]:
        return self._store.stop_inference(tenant, service_id)

    def scale(self, tenant: str, service_id: str, replicas: int) -> Optional[dict[str, Any]]:
        return self._store.scale_inference(tenant, service_id, replicas)

    async def invoke(self, tenant: str, service_id: str, payload: dict[str, Any]) -> Optional[dict[str, Any]]:
        svc = next((s for s in self._store.list_inference(tenant) if s.get("id") == service_id), None)
        if svc is None:
            return None
        # 诚实降级：契约级无真推理运行时，不伪造推理结果。
        raise OrchestratorError(
            "当前为契约级模式（LLMOPS_INFERENCE_ORCHESTRATOR=contract），无真实推理运行时；"
            "请配置 kubernetes 编排后调用",
            status_code=501,
        )

    def reconcile(self) -> None:
        """契约级无集群实况，无需对账（空操作）."""


def create_inference_orchestrator(settings: Settings, store: FrontendContractStore):
    """按配置创建推理编排器；kubernetes 实现懒导入（无 kubernetes 包时 contract 可跑）."""
    if settings.inferenceOrchestrator == "kubernetes":
        from llmops.services.kubernetes_inference_orchestrator import KubernetesInferenceOrchestrator

        return KubernetesInferenceOrchestrator(store, settings)
    return ContractInferenceOrchestrator(store)
