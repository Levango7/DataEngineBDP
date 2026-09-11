"""K3s 端到端集成测试公共配置与 fixtures.

本模块是数据引擎大数据平台 K3s 集成测试的入口配置：
- 通过 ``kubectl get svc`` 自动发现 K3s 集群内各服务的 ClusterIP；
- 支持通过环境变量 ``K3S_SVC_<NAME>`` 手动覆盖服务地址；
- 提供 ``k3s_client`` 通用 HTTP 客户端 fixture（自动携带 JWT）；
- 提供 ``wait_for_k3s_service`` 工具函数，带重试等待服务就绪；
- 通过 ``pytest_collection_modifyitems`` 钩子在服务不可用时自动跳过对应测试。

T-05: K3s 链路修复
- 添加 K3s 集群健康检查（kubectl 可用性 + 节点 Ready + namespace 存在）
- 添加自动部署逻辑（CI 中若 namespace 无 Pod 则自动 kubectl apply manifests）
- CI 环境（GITHUB_ACTIONS=true 或 K3S_STRICT=true）中服务不可用改为 FAIL 而非静默 SKIPPED
- 本地开发环境保持 SKIP 行为（避免无 K3s 时本地测试红）

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
from pathlib import Path
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
    "JWT_SECRET", "it-test-jwt-secret-at-least-32-bytes-long"
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
# T-05: K3s 集群健康检查与自动部署
# ---------------------------------------------------------------------------
# 项目根目录（用于定位 deploy/k3s/manifests/）
PROJECT_ROOT = Path(__file__).resolve().parents[3]

# 严格模式：CI 环境（GITHUB_ACTIONS=true）或显式设置 K3S_STRICT=true 时，
# 服务不可用改为 FAIL 而非静默 SKIPPED，避免"绿但空跑"
K3S_STRICT = os.environ.get("K3S_STRICT", "").lower() in ("1", "true", "yes") or \
    os.environ.get("GITHUB_ACTIONS", "").lower() in ("1", "true", "yes")


def check_k3s_cluster_health() -> dict:
    """检查 K3s 集群整体健康状态.

    Returns:
        健康状态字典:
        {
            "kubectl_available": bool,
            "nodes_ready": bool,
            "namespace_exists": bool,
            "pod_count": int,
            "ready_pod_count": int,
            "issues": list[str],  # 发现的问题列表
        }
    """
    status = {
        "kubectl_available": False,
        "nodes_ready": False,
        "namespace_exists": False,
        "pod_count": 0,
        "ready_pod_count": 0,
        "issues": [],
    }

    # 1. 检查 kubectl 是否可用
    try:
        result = subprocess.run(
            ["kubectl", "version", "--client"],
            capture_output=True, text=True, timeout=10,
        )
        status["kubectl_available"] = result.returncode == 0
    except (subprocess.SubprocessError, FileNotFoundError):
        status["issues"].append("kubectl 不可用（未安装或不在 PATH 中）")
        return status

    if not status["kubectl_available"]:
        status["issues"].append("kubectl version 命令失败")
        return status

    # 2. 检查节点是否 Ready
    try:
        result = subprocess.run(
            ["kubectl", "get", "nodes", "-o", "jsonpath={.items[*].status.conditions[?(@.type==\"Ready\")].status}"],
            capture_output=True, text=True, timeout=10,
        )
        if result.returncode == 0:
            statuses = result.stdout.strip().split()
            status["nodes_ready"] = bool(statuses) and all(s == "True" for s in statuses)
            if not status["nodes_ready"]:
                status["issues"].append(f"节点未全部 Ready: {result.stdout.strip()}")
        else:
            status["issues"].append("kubectl get nodes 失败")
    except subprocess.SubprocessError:
        status["issues"].append("检查节点状态时异常")

    # 3. 检查 namespace 是否存在
    try:
        result = subprocess.run(
            ["kubectl", "get", "namespace", K3S_NAMESPACE, "-o", "name"],
            capture_output=True, text=True, timeout=10,
        )
        status["namespace_exists"] = result.returncode == 0 and result.stdout.strip()
        if not status["namespace_exists"]:
            status["issues"].append(f"namespace '{K3S_NAMESPACE}' 不存在")
    except subprocess.SubprocessError:
        status["issues"].append(f"检查 namespace '{K3S_NAMESPACE}' 时异常")

    # 4. 统计 Pod 状态
    if status["namespace_exists"]:
        try:
            result = subprocess.run(
                ["kubectl", "get", "pods", "-n", K3S_NAMESPACE, "-o", "json"],
                capture_output=True, text=True, timeout=15,
            )
            if result.returncode == 0:
                data = json.loads(result.stdout)
                pods = data.get("items", [])
                status["pod_count"] = len(pods)
                for pod in pods:
                    containers = pod.get("status", {}).get("containerStatuses", [])
                    if containers and all(c.get("ready", False) for c in containers):
                        status["ready_pod_count"] += 1
                if status["pod_count"] == 0:
                    status["issues"].append(
                        f"namespace '{K3S_NAMESPACE}' 中无 Pod——服务未部署"
                    )
        except (subprocess.SubprocessError, json.JSONDecodeError):
            status["issues"].append("获取 Pod 状态时异常")

    return status


def ensure_k3s_services_deployed() -> bool:
    """T-05: 自动部署 K3s 服务 manifests（若 namespace 中无 Pod）.

    当检测到 namespace 存在但无 Pod 时，自动执行
    ``kubectl apply -f deploy/k3s/manifests/`` 部署服务。

    Returns:
        True 表示部署成功或已部署，False 表示部署失败。
    """
    health = check_k3s_cluster_health()

    # 集群不健康，无法部署
    if not health["kubectl_available"] or not health["nodes_ready"]:
        return False

    # namespace 不存在，先创建
    if not health["namespace_exists"]:
        manifests_dir = PROJECT_ROOT / "deploy" / "k3s" / "manifests"
        namespace_file = manifests_dir / "namespace.yaml"
        if namespace_file.exists():
            try:
                subprocess.run(
                    ["kubectl", "apply", "-f", str(namespace_file)],
                    capture_output=True, text=True, timeout=30,
                )
            except subprocess.SubprocessError:
                return False
        else:
            return False

    # 已有 Pod，无需部署
    if health["pod_count"] > 0:
        return True

    # 自动部署所有 manifests
    manifests_dir = PROJECT_ROOT / "deploy" / "k3s" / "manifests"
    if not manifests_dir.exists():
        return False

    print(f"[T-05] 自动部署 K3s manifests: {manifests_dir}")
    try:
        result = subprocess.run(
            ["kubectl", "apply", "-f", str(manifests_dir)],
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode == 0:
            print(f"[T-05] manifests 部署成功，等待 Pod 就绪...")
            # 等待 Pod 就绪（最多 180 秒）
            try:
                subprocess.run(
                    ["kubectl", "wait", "--for=condition=Ready",
                     "pods", "-n", K3S_NAMESPACE, "--all", "--timeout=180s"],
                    capture_output=True, text=True, timeout=200,
                )
            except subprocess.SubprocessError:
                pass  # 等待超时不阻断，后续健康检查会报告
            return True
        else:
            print(f"[T-05] manifests 部署失败: {result.stderr}")
            return False
    except subprocess.SubprocessError as e:
        print(f"[T-05] 部署异常: {e}")
        return False



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
            # T-05: CI 严格模式下 FAIL 而非静默 SKIP
            msg = (
                f"K3s 服务 {service_name} 未发现（kubectl 不可用或 Service 不存在）。"
                f"请检查: 1) K3s 是否启动 2) namespace '{K3S_NAMESPACE}' 是否存在 "
                f"3) deploy/k3s/manifests/ 是否已部署"
            )
            if K3S_STRICT:
                pytest.fail(msg, pytrace=False)
            else:
                pytest.skip(msg)
        # 等待服务就绪（最多 15 秒）
        if not wait_for_k3s_service(service_name, url, timeout=15):
            msg = f"K3s 服务 {service_name} 健康检查超时（{url}）"
            if K3S_STRICT:
                pytest.fail(msg, pytrace=False)
            else:
                pytest.skip(msg)
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
    """收集阶段钩子：在依赖服务不可用时自动跳过/失败链路测试.

    T-05: 修复策略
    1. 首先检查 K3s 集群健康状态，若集群不健康则报告详细原因
    2. 若 namespace 中无 Pod，尝试自动部署 deploy/k3s/manifests/
    3. CI 严格模式（K3S_STRICT/GITHUB_ACTIONS）下服务不可用 → FAIL（不静默 SKIP）
    4. 本地开发环境保持 SKIP 行为
    """
    # T-05: 先检查集群健康，尝试自动部署
    health = check_k3s_cluster_health()
    if health["kubectl_available"] and health["nodes_ready"]:
        if health["namespace_exists"] and health["pod_count"] == 0:
            # namespace 存在但无 Pod → 自动部署
            print(f"[T-05] 检测到 namespace '{K3S_NAMESPACE}' 无 Pod，尝试自动部署...")
            deployed = ensure_k3s_services_deployed()
            if deployed:
                # 部署后重新探测服务可用性（清除缓存）
                global _DISCOVERED_URLS
                _DISCOVERED_URLS = None
                print("[T-05] 自动部署完成，重新探测服务可用性")
            elif K3S_STRICT:
                # CI 中部署失败 → 不添加 skip，让测试运行时通过 fixture fail 明确报告
                fail_msg = (
                    f"K3s 服务自动部署失败。集群健康: kubectl={health['kubectl_available']}, "
                    f"nodes_ready={health['nodes_ready']}, issues={health['issues']}"
                )
                print(f"[T-05] FAIL: {fail_msg}")
                for item in items:
                    item.user_properties.append(("k3s_deploy_failed", fail_msg))
                return

    # 预先探测各服务可用性
    availability = {name: is_k3s_service_available(name) for name in K3S_SERVICES}

    for item in items:
        fspath = str(item.fspath)
        for prefix, services in _CHAIN_SERVICE_MAP.items():
            if fspath.endswith(prefix + ".py"):
                unavailable = [s for s in services if not availability.get(s, False)]
                if unavailable:
                    reason = (
                        f"依赖服务不可用: {', '.join(unavailable)}。"
                        f"请检查 K3s Pod 状态（kubectl get pods -n {K3S_NAMESPACE}）"
                    )
                    if K3S_STRICT:
                        # T-05: CI 严格模式 → 不添加 skip marker，让测试运行时通过
                        # fixture 的 pytest.fail() 明确失败（不静默 SKIP）
                        # 在测试节点上添加标记信息供报告识别
                        item.user_properties.append(("k3s_strict_fail_reason", reason))
                    else:
                        # 本地开发环境 → 静默 SKIP
                        item.add_marker(pytest.mark.skip(reason=reason))
                break