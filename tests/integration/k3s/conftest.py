"""K3s 端到端集成测试公共配置与 fixtures.

本模块是数擎大数据平台 K3s 集成测试的入口配置：
- 通过 ``kubectl get svc`` 自动发现 K3s 集群内各服务的 ClusterIP；
- 支持通过环境变量 ``K3S_SVC_<NAME>`` 手动覆盖服务地址；
- 提供 ``k3s_client`` 通用 HTTP 客户端 fixture（自动携带 JWT）；
- 提供 ``wait_for_k3s_service`` 工具函数，带重试等待服务就绪；
- 通过 ``pytest_collection_modifyitems`` 钩子在服务不可用时自动跳过对应测试。

设计要点：
- 测试脚本设计为在 WSL（K3s 节点）内运行，可直接访问 ClusterIP；
- 也可在 Windows 主机运行，但需通过 ``kubectl port-forward`` 或 ``K3S_SVC_*`` 环境变量指定可达地址；
- 所有服务调用带 5 秒超时 + 3 次重试，应对 Pod 重启导致的短暂不可用。
"""

from __future__ import annotations

import os
import subprocess
import time
import json
from typing import Dict, Optional

import jwt
import pytest
import requests


# ---------------------------------------------------------------------------
# K3s 服务发现
# ---------------------------------------------------------------------------
# K3s namespace
K3S_NAMESPACE = os.environ.get("K3S_NAMESPACE", "shuqing")

# 服务名 → (K3s Service 名, 端口) 映射
# 端口与 deploy/k3s/manifests/*.yaml 中 Service.spec.ports.port 保持一致
K3S_SERVICES: Dict[str, tuple[str, int]] = {
    "encaps-layer": ("encaps-layer", 8080),
    "sql-gateway": ("sql-gateway", 8081),
    "catalog": ("catalog", 8082),
    "rule-engine": ("rule-engine", 8083),
    "infra-orchestrator": ("infra-orchestrator", 8085),
    "knowledge-engine": ("knowledge-engine", 8080),
    "nl2sql": ("nl2sql", 8093),
    "lineage-analyzer": ("lineage-analyzer", 8086),
    "metadata-collector": ("metadata-collector", 8084),
    "tag-engine": ("tag-engine", 8080),
    "open-api-catalog": ("open-api-catalog", 8090),
    "llm-gateway": ("llm-gateway", 8084),
    "infra-provider-cloud": ("infra-provider-cloud", 8084),
    "infra-provider-private": ("infra-provider-private", 8084),
    "infra-provider-xinchang": ("infra-provider-xinchang", 8081),
}

# HTTP 请求默认超时（秒）
DEFAULT_TIMEOUT = 10

# 健康检查路径映射（部分组件使用自定义路径）
HEALTH_PATHS: Dict[str, str] = {
    "encaps-layer": "/api/v1/health",
    "sql-gateway": "/api/v1/health",
    "catalog": "/api/v1/health",
    "rule-engine": "/api/v1/health",
    "infra-orchestrator": "/actuator/health",
    "knowledge-engine": "/health",
    "nl2sql": "/api/v1/health",
    "lineage-analyzer": "/api/v1/health",
    "metadata-collector": "/api/v1/health",
    "tag-engine": "/api/v1/health",
    "open-api-catalog": "/api/v1/health",
    "llm-gateway": "/api/v1/health",
    "infra-provider-cloud": "/api/v1/health",
    "infra-provider-private": "/api/v1/health",
    "infra-provider-xinchang": "/api/v1/health",
}


def _kubectl_get_svc_ip(svc_name: str, namespace: str = K3S_NAMESPACE) -> Optional[str]:
    """通过 kubectl 获取 K3s Service 的 ClusterIP.

    Args:
        svc_name: K3s Service 名称.
        namespace: K3s namespace.

    Returns:
        ClusterIP 字符串，获取失败返回 None.
    """
    try:
        result = subprocess.run(
            [
                "kubectl", "get", "svc", svc_name,
                "-n", namespace,
                "-o", "jsonpath={.spec.clusterIP}",
            ],
            capture_output=True,
            text=True,
            timeout=10,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    except (subprocess.SubprocessError, FileNotFoundError):
        pass
    return None


def discover_k3s_services() -> Dict[str, str]:
    """发现所有 K3s 服务的可达 URL.

    优先级：
    1. 环境变量 ``K3S_SVC_<NAME>``（手动覆盖，格式 ``http://ip:port``）；
    2. ``kubectl get svc`` 自动发现 ClusterIP.

    Returns:
        服务名 → 基础 URL 映射，不可达的服务不包含在内.
    """
    urls: Dict[str, str] = {}
    for name, (svc_name, port) in K3S_SERVICES.items():
        # 1. 环境变量覆盖
        env_key = f"K3S_SVC_{name.upper().replace('-', '_')}"
        env_url = os.environ.get(env_key)
        if env_url:
            urls[name] = env_url.rstrip("/")
            continue

        # 2. kubectl 自动发现
        cluster_ip = _kubectl_get_svc_ip(svc_name)
        if cluster_ip:
            urls[name] = f"http://{cluster_ip}:{port}"
    return urls


# 模块级缓存：服务 URL 映射（首次调用时发现）
_DISCOVERED_URLS: Optional[Dict[str, str]] = None


def get_service_urls() -> Dict[str, str]:
    """获取所有已发现的 K3s 服务 URL（带缓存）."""
    global _DISCOVERED_URLS
    if _DISCOVERED_URLS is None:
        _DISCOVERED_URLS = discover_k3s_services()
    return _DISCOVERED_URLS


# ---------------------------------------------------------------------------
# JWT 配置（与各组件 application.yml / 环境变量默认值保持一致）
# ---------------------------------------------------------------------------
JWT_SECRET = os.environ.get(
    "JWT_SECRET", "dev-secret-key-change-in-production-at-least-256-bits"
)
JWT_ISSUER = os.environ.get("JWT_ISSUER", "shuqing-bigdata")


def generate_test_jwt(
    tenant_id: str = "it-test-tenant", user_id: str = "it-tester"
) -> str:
    """生成集成测试用 JWT Bearer token.

    Args:
        tenant_id: 租户 ID，写入 ``tenantId`` claim.
        user_id: 用户 ID，写入 ``sub`` claim.

    Returns:
        编码后的 JWT 字符串.
    """
    payload = {
        "iss": JWT_ISSUER,
        "sub": user_id,
        "tenantId": tenant_id,
        "iat": int(time.time()),
        "exp": int(time.time()) + 3600,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm="HS256")


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def wait_for_k3s_service(
    name: str, url: str, timeout: int = 15, interval: float = 1.0
) -> bool:
    """轮询等待 K3s 服务健康检查通过.

    Args:
        name: 服务名（用于查找健康检查路径）.
        url: 服务基础 URL.
        timeout: 最长等待秒数.
        interval: 轮询间隔秒数.

    Returns:
        True 表示服务就绪，False 表示超时.
    """
    health_path = HEALTH_PATHS.get(name, "/api/v1/health")
    health_url = url.rstrip("/") + health_path
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            resp = requests.get(health_url, timeout=5)
            if resp.status_code == 200:
                return True
        except requests.RequestException:
            pass
        time.sleep(interval)
    return False


def is_k3s_service_available(name: str) -> bool:
    """检查 K3s 服务是否可用（短时间内探测一次）.

    Args:
        name: 服务名.

    Returns:
        True 表示服务在 5 秒内响应健康检查.
    """
    urls = get_service_urls()
    url = urls.get(name)
    if not url:
        return False
    return wait_for_k3s_service(name, url, timeout=5, interval=0.5)


# ---------------------------------------------------------------------------
# HTTP 客户端
# ---------------------------------------------------------------------------
class K3sApiClient:
    """K3s 服务 HTTP 客户端封装.

    特性：
    - 自动携带 JWT Bearer token；
    - 请求超时 + 重试（应对 Pod 重启短暂不可用）；
    - 提供 get/post/put/delete 方法.
    """

    def __init__(self, max_retries: int = 3, retry_delay: float = 1.0):
        self._token: Optional[str] = None
        self._max_retries = max_retries
        self._retry_delay = retry_delay

    @property
    def auth_header(self) -> Dict[str, str]:
        """返回携带 Bearer token 的请求头."""
        if self._token is None:
            self._token = generate_test_jwt()
        return {"Authorization": f"Bearer {self._token}"}

    def _request(self, method: str, url: str, **kwargs) -> requests.Response:
        """执行 HTTP 请求（带重试）."""
        kwargs.setdefault("timeout", DEFAULT_TIMEOUT)
        headers = kwargs.pop("headers", {})
        headers.update(self.auth_header)

        last_exc: Optional[Exception] = None
        for attempt in range(self._max_retries):
            try:
                resp = requests.request(method, url, headers=headers, **kwargs)
                # 5xx 错误重试，4xx 不重试
                if resp.status_code >= 500 and attempt < self._max_retries - 1:
                    time.sleep(self._retry_delay)
                    continue
                return resp
            except requests.RequestException as e:
                last_exc = e
                if attempt < self._max_retries - 1:
                    time.sleep(self._retry_delay)
        raise last_exc  # type: ignore[misc]

    def get(self, url: str, **kwargs) -> requests.Response:
        return self._request("GET", url, **kwargs)

    def post(self, url: str, **kwargs) -> requests.Response:
        return self._request("POST", url, **kwargs)

    def put(self, url: str, **kwargs) -> requests.Response:
        return self._request("PUT", url, **kwargs)

    def delete(self, url: str, **kwargs) -> requests.Response:
        return self._request("DELETE", url, **kwargs)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture
def k3s_client():
    """K3s 服务 HTTP 客户端 fixture（自动携带 JWT + 重试）."""
    return K3sApiClient()


def _make_service_url_fixture(service_name: str):
    """工厂函数：为指定服务创建 URL fixture."""

    @pytest.fixture(scope="session")
    def _fixture():
        urls = get_service_urls()
        url = urls.get(service_name)
        if not url:
            pytest.skip(
                f"K3s 服务 {service_name} 未发现（kubectl 不可用或 Service 不存在）"
            )
        # 等待服务就绪（最多 15 秒）
        if not wait_for_k3s_service(service_name, url, timeout=15):
            pytest.skip(
                f"K3s 服务 {service_name} 健康检查超时（{url}）"
            )
        return url

    _fixture.__name__ = f"{service_name.replace('-', '_')}_url"
    return _fixture


# 为每个服务动态创建 URL fixture
for _svc_name in K3S_SERVICES:
    _fixture_name = f"{_svc_name.replace('-', '_')}_url"
    globals()[_fixture_name] = _make_service_url_fixture(_svc_name)


# ---------------------------------------------------------------------------
# 链路测试结果记录（用于生成报告）
# ---------------------------------------------------------------------------
# 模块级测试结果记录，供报告生成器读取
TEST_RESULTS: list[dict] = []


def record_test_result(
    chain: str, test_name: str, passed: bool, detail: str = "",
    duration_ms: float = 0.0,
) -> None:
    """记录单条测试结果（供报告生成器读取）.

    Args:
        chain: 链路名称（如 "链路1: NL2SQL→SQL网关→查询"）.
        test_name: 测试名称.
        passed: 是否通过.
        detail: 详细信息（错误原因等）.
        duration_ms: 耗时（毫秒）.
    """
    TEST_RESULTS.append({
        "chain": chain,
        "test": test_name,
        "passed": passed,
        "detail": detail,
        "duration_ms": duration_ms,
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
    })


# ---------------------------------------------------------------------------
# 钩子：服务不可用时自动跳过对应测试
# ---------------------------------------------------------------------------
# 链路测试文件前缀 → 依赖的服务列表
_CHAIN_SERVICE_MAP = {
    "test_chain1": ["nl2sql", "sql-gateway"],
    "test_chain2": ["infra-orchestrator", "knowledge-engine"],
    "test_chain3": ["sql-gateway"],
    "test_chain4": ["encaps-layer"],
}


def pytest_collection_modifyitems(config, items):
    """收集阶段钩子：在依赖服务不可用时自动跳过链路测试."""
    # 预先探测各服务可用性
    availability = {name: is_k3s_service_available(name) for name in K3S_SERVICES}

    for item in items:
        fspath = str(item.fspath)
        for prefix, services in _CHAIN_SERVICE_MAP.items():
            if fspath.endswith(prefix + ".py"):
                unavailable = [s for s in services if not availability.get(s, False)]
                if unavailable:
                    item.add_marker(
                        pytest.mark.skip(
                            reason=(
                                f"依赖服务不可用: {', '.join(unavailable)}。"
                                f"请检查 K3s Pod 状态（kubectl get pods -n {K3S_NAMESPACE}）"
                            )
                        )
                    )
                break