"""pytest 集成测试公共配置与 fixtures。

本模块是数擎大数据平台（ShuqingBigDataPlatform）集成测试的入口配置：
- 集中维护各组件 REST API 基础 URL；
- 提供 ``api_client`` 通用 HTTP 客户端 fixture；
- 提供 4 个 Java/Go 组件的 URL fixture（session 级别，避免重复创建）；
- 提供 5 个 Python 组件的自动启动/停止 fixture（subprocess 方式，本地运行）；
- 提供 ``wait_for_service`` 工具函数，等待服务就绪；
- 通过 ``pytest_collection_modifyitems`` 钩子在服务不可用时自动跳过对应测试。

约定：
- Java/Go 组件均暴露 ``GET /api/v1/health``，返回 ``{"status": "UP", ...}``；
- Python 组件健康检查路径与 status 值见 ``PYTHON_COMPONENTS`` 配置。
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict

import jwt
import pytest
import requests


# 项目根目录（tests/integration 的上两级）。
PROJECT_ROOT = Path(__file__).resolve().parents[2]

# 各组件基础 URL。集中维护，便于环境变量或 CI 覆盖。
# 端口与 docker-compose.yml 保持一致（使用 18080-18083 避开本机 8080 占用）。
BASE_URLS: Dict[str, str] = {
    "encaps": "http://localhost:18080",
    "sql_gateway": "http://localhost:18081",
    "catalog": "http://localhost:18082",
    "rule_engine": "http://localhost:18083",
    # Python 组件（本地直接运行，使用原生端口）。
    "asset_exchange": "http://localhost:8087",
    "business_portal": "http://localhost:8088",
    "open_api_catalog": "http://localhost:8090",
    "industry_templates": "http://localhost:8091",
    "knowledge_engine": "http://localhost:8092",
}

# HTTP 请求默认超时（秒），避免测试卡死。
DEFAULT_TIMEOUT = 10

# 健康检查路径（Java/Go 组件统一）。
HEALTH_PATH = "/api/v1/health"

# ---------------------------------------------------------------------------
# Python 组件配置（本地 subprocess 方式运行，不走 Docker）
# ---------------------------------------------------------------------------
# 每个组件的配置：
#   dir:          组件目录（相对于 PROJECT_ROOT）
#   port:         监听端口
#   env_prefix:   环境变量前缀（用于设置 PORT/HOST 等）
#   health_path:  健康检查路径
#   health_status:健康检查期望的 status 字段值
#   extra_env:    额外环境变量（如 STORE_TYPE=mock）
PYTHON_COMPONENTS: Dict[str, Dict] = {
    "asset_exchange": {
        "dir": "platform/asset-exchange",
        "port": 8087,
        "env_prefix": "ASSET_EXCHANGE",
        "health_path": "/api/v1/health",
        "health_status": "UP",
        "extra_env": {"ASSET_EXCHANGE_STORE_TYPE": "mock"},
    },
    "business_portal": {
        "dir": "platform/business-portal",
        "port": 8088,
        "env_prefix": "BP",
        "health_path": "/api/v1/health",
        "health_status": "UP",
        "extra_env": {"BP_STORE_TYPE": "mock"},
    },
    "open_api_catalog": {
        "dir": "platform/open-api-catalog",
        "port": 8090,
        "env_prefix": "OPENAPI_CATALOG",
        "health_path": "/api/v1/health",
        "health_status": "UP",
        "extra_env": {"OPENAPI_CATALOG_STORE_TYPE": "mock"},
    },
    "industry_templates": {
        "dir": "platform/industry-templates",
        "port": 8091,
        "env_prefix": "INDUSTRY_TEMPLATES",
        "health_path": "/api/v1/health",
        "health_status": "UP",
        "extra_env": {"INDUSTRY_TEMPLATES_DEPLOY_MODE": "mock"},
    },
    "knowledge_engine": {
        "dir": "platform/knowledge-engine",
        "port": 8092,
        "env_prefix": "KE",
        "health_path": "/health",
        "health_status": "ok",
        "extra_env": {
            "KE_STORE_TYPE": "mock",
            "KE_EXTRACTOR_TYPE": "mock",
        },
    },
}

# ---------------------------------------------------------------------------
# JWT 配置（与各组件 application.yml / 环境变量默认值保持一致）
# ---------------------------------------------------------------------------
JWT_SECRET = os.environ.get(
    "JWT_SECRET", "dev-secret-key-change-in-production-at-least-256-bits"
)
JWT_ISSUER = os.environ.get("JWT_ISSUER", "shuqing-bigdata")


def _generate_test_jwt(tenant_id: str = "it-test-tenant", user_id: str = "it-tester") -> str:
    """生成集成测试用 JWT Bearer token。

    使用与各组件相同的 HMAC-SHA 密钥与 issuer 签发，确保后端能验证通过。

    Args:
        tenant_id: 租户 ID，写入 ``tenantId`` claim。
        user_id:  用户 ID，写入 ``sub`` claim。

    Returns:
        编码后的 JWT 字符串。
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
def wait_for_service(url: str, timeout: int = 30, interval: float = 0.5) -> bool:
    """轮询等待服务健康检查通过。

    Args:
        url: 服务基础 URL，例如 ``http://localhost:8080``。
        timeout: 最长等待秒数，超时返回 ``False``。
        interval: 轮询间隔秒数。

    Returns:
        ``True`` 表示服务在超时前就绪；``False`` 表示超时仍未就绪。
    """
    deadline = time.time() + timeout
    health_url = url.rstrip("/") + HEALTH_PATH
    while time.time() < deadline:
        try:
            resp = requests.get(health_url, timeout=DEFAULT_TIMEOUT)
            if resp.status_code == 200:
                # 进一步校验 status 字段（若存在）。
                try:
                    body = resp.json()
                    if body.get("status") == "UP":
                        return True
                except ValueError:
                    # 非 JSON 也视为就绪，只要 200。
                    return True
        except requests.RequestException:
            # 服务尚未启动，继续轮询。
            pass
        time.sleep(interval)
    return False


def wait_for_service_path(
    url: str, health_path: str, timeout: int = 30, interval: float = 0.5
) -> bool:
    """轮询等待服务健康检查通过（支持自定义健康检查路径）.

    Args:
        url: 服务基础 URL，例如 ``http://localhost:8080``。
        health_path: 健康检查路径，例如 ``/health`` 或 ``/api/v1/health``。
        timeout: 最长等待秒数，超时返回 ``False``。
        interval: 轮询间隔秒数。

    Returns:
        ``True`` 表示服务在超时前就绪；``False`` 表示超时仍未就绪。
    """
    deadline = time.time() + timeout
    health_url = url.rstrip("/") + health_path
    while time.time() < deadline:
        try:
            resp = requests.get(health_url, timeout=DEFAULT_TIMEOUT)
            if resp.status_code == 200:
                return True
        except requests.RequestException:
            pass
        time.sleep(interval)
    return False


def is_service_available(name: str) -> bool:
    """检查指定组件是否可用（短时间内探测一次）。

    与 ``wait_for_service`` 不同，本函数只做一次快速探测（3 秒内），
    用于在收集阶段决定是否跳过测试，避免长时间阻塞。

    对 Python 组件使用其自定义健康检查路径。
    """
    url = BASE_URLS.get(name)
    if not url:
        return False
    # Python 组件使用自定义健康检查路径
    if name in PYTHON_COMPONENTS:
        health_path = PYTHON_COMPONENTS[name]["health_path"]
        return wait_for_service_path(url, health_path, timeout=3, interval=0.2)
    return wait_for_service(url, timeout=3, interval=0.2)


# ---------------------------------------------------------------------------
# Python 组件进程管理
# ---------------------------------------------------------------------------
def _build_python_env(component_config: Dict) -> Dict[str, str]:
    """构建 Python 组件启动环境变量.

    合并当前环境 + 端口/主机环境变量 + 组件特有环境变量。
    """
    env = os.environ.copy()
    prefix = component_config["env_prefix"]
    # 设置 PORT 和 HOST（各组件 settings.py 通过 {PREFIX}_PORT / {PREFIX}_HOST 读取）
    env[f"{prefix}_PORT"] = str(component_config["port"])
    env[f"{prefix}_HOST"] = "127.0.0.1"
    # 合并额外环境变量（如 STORE_TYPE=mock）
    env.update(component_config.get("extra_env", {}))
    # 确保 Python 能找到组件包（将组件目录加入 PYTHONPATH）
    comp_dir = PROJECT_ROOT / component_config["dir"]
    existing_pp = env.get("PYTHONPATH", "")
    if existing_pp:
        env["PYTHONPATH"] = f"{comp_dir}{os.pathsep}{existing_pp}"
    else:
        env["PYTHONPATH"] = str(comp_dir)
    return env


def _start_python_component(name: str) -> subprocess.Popen:
    """启动一个 Python 组件子进程.

    Args:
        name: PYTHON_COMPONENTS 中的组件名。

    Returns:
        subprocess.Popen 进程句柄。

    Raises:
        RuntimeError: 组件配置不存在。
    """
    if name not in PYTHON_COMPONENTS:
        raise RuntimeError(f"未知的 Python 组件: {name}")
    cfg = PYTHON_COMPONENTS[name]
    comp_dir = PROJECT_ROOT / cfg["dir"]
    env = _build_python_env(cfg)
    # 使用当前 Python 解释器运行 main.py
    # stdout/stderr 重定向到 DEVNULL，避免 PIPE 缓冲区满导致进程阻塞
    proc = subprocess.Popen(
        [sys.executable, "main.py"],
        cwd=str(comp_dir),
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        # Windows 下创建新进程组，便于整组终止
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP
        if sys.platform == "win32"
        else 0,
    )
    return proc


def _stop_python_component(proc: subprocess.Popen) -> None:
    """停止 Python 组件子进程（先 terminate，超时再 kill）."""
    if proc.poll() is not None:
        return  # 进程已退出
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)


def _ensure_python_component_running(name: str) -> str:
    """确保 Python 组件正在运行并返回其基础 URL.

    若组件已在对应端口响应健康检查，则直接返回 URL（不重复启动）；
    否则启动子进程并等待健康检查通过。

    Returns:
        组件基础 URL，例如 ``http://localhost:8087``。

    Raises:
        RuntimeError: 启动后健康检查仍超时。
    """
    cfg = PYTHON_COMPONENTS[name]
    url = BASE_URLS[name]
    health_path = cfg["health_path"]

    # 若端口已被占用且健康检查通过，视为外部已启动，直接复用
    if wait_for_service_path(url, health_path, timeout=1, interval=0.2):
        return url

    # 启动子进程
    proc = _start_python_component(name)

    # 等待健康检查通过（最多 30 秒）
    if not wait_for_service_path(url, health_path, timeout=30, interval=0.5):
        # 启动失败：终止进程并报错
        _stop_python_component(proc)
        raise RuntimeError(
            f"Python 组件 {name} 启动超时（{url}{health_path} 健康检查失败）。"
            f"请检查组件依赖是否已安装（cd {cfg['dir']} && pip install -r requirements.txt）。"
        )

    # 将进程句柄缓存到模块级字典，session teardown 时统一清理
    _RUNNING_PROCESSES[name] = proc
    return url


# 模块级缓存：记录已启动的 Python 组件进程，session 结束时统一终止。
_RUNNING_PROCESSES: Dict[str, subprocess.Popen] = {}


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture
def api_client():
    """通用 API 客户端 fixture。

    返回一个轻量封装对象，提供 ``get/post/put/delete`` 方法，
    自动带上 ``DEFAULT_TIMEOUT``，并返回 ``requests.Response``。

    用法::

        def test_xxx(api_client, encaps_url):
            resp = api_client.get(encaps_url + "/api/v1/health")
            assert resp.status_code == 200
    """
    return _ApiClient()


class _ApiClient:
    """轻量 HTTP 客户端封装。

    自动携带 JWT Bearer token，使受保护端点可通过认证。
    健康检查等 permitAll 端点不受影响。
    """

    def __init__(self):
        self._token: str | None = None

    @property
    def auth_header(self) -> Dict[str, str]:
        """返回携带 Bearer token 的请求头。"""
        if self._token is None:
            self._token = _generate_test_jwt()
        return {"Authorization": f"Bearer {self._token}"}

    def get(self, url, **kwargs):
        kwargs.setdefault("timeout", DEFAULT_TIMEOUT)
        headers = kwargs.pop("headers", {})
        headers.update(self.auth_header)
        return requests.get(url, headers=headers, **kwargs)

    def post(self, url, **kwargs):
        kwargs.setdefault("timeout", DEFAULT_TIMEOUT)
        headers = kwargs.pop("headers", {})
        headers.update(self.auth_header)
        return requests.post(url, headers=headers, **kwargs)

    def put(self, url, **kwargs):
        kwargs.setdefault("timeout", DEFAULT_TIMEOUT)
        headers = kwargs.pop("headers", {})
        headers.update(self.auth_header)
        return requests.put(url, headers=headers, **kwargs)

    def delete(self, url, **kwargs):
        kwargs.setdefault("timeout", DEFAULT_TIMEOUT)
        headers = kwargs.pop("headers", {})
        headers.update(self.auth_header)
        return requests.delete(url, headers=headers, **kwargs)


@pytest.fixture(scope="session")
def encaps_url():
    """封装层基础 URL（session 级别）。"""
    return BASE_URLS["encaps"]


@pytest.fixture(scope="session")
def sql_gateway_url():
    """SQL 网关基础 URL（session 级别）。"""
    return BASE_URLS["sql_gateway"]


@pytest.fixture(scope="session")
def catalog_url():
    """Catalog 基础 URL（session 级别）。"""
    return BASE_URLS["catalog"]


@pytest.fixture(scope="session")
def rule_engine_url():
    """规则引擎基础 URL（session 级别）。"""
    return BASE_URLS["rule_engine"]


# ---------------------------------------------------------------------------
# Python 组件 URL fixtures（session 级别，自动启动/停止组件）
# ---------------------------------------------------------------------------
# 设计说明：
# - 每个 fixture 在首次调用时启动对应 Python 组件子进程，并等待健康检查通过；
# - 若环境变量 SQ_IT_SKIP_PYTHON_START=1 则跳过自动启动，假定组件已外部启动；
# - session 级别保证整个测试会话期间组件保持运行，避免重复启动开销；
# - 进程句柄缓存在 _RUNNING_PROCESSES，由 _python_components_finalizer 统一清理。
_SKIP_START = os.environ.get("SQ_IT_SKIP_PYTHON_START", "0") == "1"


@pytest.fixture(scope="session")
def asset_exchange_url():
    """Asset Exchange 基础 URL（session 级别，自动启动组件）.

    端口 8087，环境变量前缀 ASSET_EXCHANGE_。
    若设置环境变量 SQ_IT_SKIP_PYTHON_START=1 则跳过自动启动。
    """
    if _SKIP_START:
        return BASE_URLS["asset_exchange"]
    return _ensure_python_component_running("asset_exchange")


@pytest.fixture(scope="session")
def business_portal_url():
    """Business Portal 基础 URL（session 级别，自动启动组件）.

    端口 8088，环境变量前缀 BP_。
    """
    if _SKIP_START:
        return BASE_URLS["business_portal"]
    return _ensure_python_component_running("business_portal")


@pytest.fixture(scope="session")
def open_api_catalog_url():
    """Open API Catalog 基础 URL（session 级别，自动启动组件）.

    端口 8090，环境变量前缀 OPENAPI_CATALOG_。
    """
    if _SKIP_START:
        return BASE_URLS["open_api_catalog"]
    return _ensure_python_component_running("open_api_catalog")


@pytest.fixture(scope="session")
def industry_templates_url():
    """Industry Templates 基础 URL（session 级别，自动启动组件）.

    端口 8091，环境变量前缀 INDUSTRY_TEMPLATES_。
    """
    if _SKIP_START:
        return BASE_URLS["industry_templates"]
    return _ensure_python_component_running("industry_templates")


@pytest.fixture(scope="session")
def knowledge_engine_url():
    """Knowledge Engine 基础 URL（session 级别，自动启动组件）.

    端口 8080，环境变量前缀 KE_。
    注意：knowledge-engine 健康检查路径为 /health（无 /api/v1 前缀）。
    """
    if _SKIP_START:
        return BASE_URLS["knowledge_engine"]
    return _ensure_python_component_running("knowledge_engine")


@pytest.fixture(scope="session", autouse=True)
def _python_components_finalizer():
    """session 级别自动 fixture：在所有测试结束后清理 Python 组件进程.

    通过 yield 分隔 setup/teardown，teardown 阶段统一终止已启动的子进程。
    """
    yield
    # teardown：终止所有已启动的 Python 组件进程
    for name, proc in list(_RUNNING_PROCESSES.items()):
        try:
            _stop_python_component(proc)
        except Exception:
            pass
        _RUNNING_PROCESSES.pop(name, None)


# ---------------------------------------------------------------------------
# 测试数据 fixtures（创建后自动清理，保证测试相互独立）
# ---------------------------------------------------------------------------
@pytest.fixture
def sample_tenant(api_client, encaps_url):
    """创建一个示例租户，测试结束后自动删除。

    Yields:
        dict: 已创建租户的 JSON 表示（含 id）。
    """
    payload = {
        "name": "it-test-tenant",
        "displayName": "集成测试租户",
        "namespace": "ns-it-test",
        "quotaProfile": "medium",
    }
    resp = api_client.post(
        encaps_url + "/api/v1/tenants",
        json=payload,
    )
    tenant = resp.json()

    yield tenant

    # 清理：删除创建的租户（若仍存在）。
    try:
        api_client.delete(encaps_url + f"/api/v1/tenants/{tenant.get('id')}")
    except requests.RequestException:
        pass


@pytest.fixture
def sample_database(api_client, catalog_url):
    """创建一个示例数据库，测试结束后自动删除。

    Yields:
        dict: 已创建数据库的 JSON 表示（含 id）。
    """
    payload = {
        "name": "it_test_db",
        "description": "集成测试数据库",
    }
    resp = api_client.post(
        catalog_url + "/api/v1/catalog/databases",
        json=payload,
    )
    database = resp.json()

    yield database

    try:
        api_client.delete(
            catalog_url + f"/api/v1/catalog/databases/{database.get('id')}"
        )
    except requests.RequestException:
        pass


@pytest.fixture
def sample_table(api_client, catalog_url, sample_database):
    """创建一个示例表（依赖 sample_database），测试结束后自动删除。

    Yields:
        dict: 已创建表的 JSON 表示（含 id）。
    """
    payload = {
        "databaseName": sample_database.get("name", "it_test_db"),
        "tableName": "it_test_table",
        "columns": [
            {"name": "id", "type": "bigint", "nullable": False},
            {"name": "name", "type": "string", "nullable": True},
        ],
        "partitionKeys": ["dt"],
    }
    resp = api_client.post(
        catalog_url + "/api/v1/catalog/tables",
        json=payload,
    )
    table = resp.json()

    yield table

    try:
        api_client.delete(catalog_url + f"/api/v1/catalog/tables/{table.get('id')}")
    except requests.RequestException:
        pass


@pytest.fixture
def sample_rule(api_client, rule_engine_url):
    """创建一个示例规则，测试结束后自动删除。

    Yields:
        dict: 已创建规则的 JSON 表示（含 id）。
    """
    payload = {
        "name": "it-test-dq-not-null",
        "description": "集成测试：user_id 非空",
        "type": "DQ",
        "expression": "user_id IS NOT NULL",
        "severity": "ERROR",
        "enabled": True,
    }
    resp = api_client.post(
        rule_engine_url + "/api/v1/rules",
        json=payload,
    )
    rule = resp.json()

    yield rule

    try:
        api_client.delete(rule_engine_url + f"/api/v1/rules/{rule.get('id')}")
    except requests.RequestException:
        pass


# ---------------------------------------------------------------------------
# 钩子：服务不可用时自动跳过对应测试
# ---------------------------------------------------------------------------
# 组件名 -> 测试文件前缀 的映射，用于按服务可用性跳过测试。
_SERVICE_TEST_PREFIX = {
    "encaps": "test_encaps",
    "sql_gateway": "test_sql_gateway",
    "catalog": "test_catalog",
    "rule_engine": "test_rule_engine",
    # Python 组件
    "asset_exchange": "test_asset_exchange",
    "business_portal": "test_business_portal",
    "open_api_catalog": "test_open_api_catalog",
    "industry_templates": "test_industry_templates",
    "knowledge_engine": "test_knowledge_engine",
}


def pytest_collection_modifyitems(config, items):
    """收集阶段钩子：在对应服务不可用时自动跳过其测试.

    跳过原因会写入 ``pytest.mark.skip`` 的 reason 字段，便于在报告中查看。

    注意：
    - Java/Go 组件通过 Docker 启动，收集阶段探测端口可用性；
    - Python 组件默认由 fixture 自动启动，**不在此处跳过**（除非
      SQ_IT_SKIP_PYTHON_START=1 且组件未外部启动）。
    """
    # 预先探测各服务可用性，避免每个测试都探测一次。
    availability = {name: is_service_available(name) for name in BASE_URLS}

    for item in items:
        # item.name 形如 "test_health_check"，无法区分所属文件；
        # 改用 item.fspath 的文件名前缀判断。
        fspath = str(item.fspath)
        for service, prefix in _SERVICE_TEST_PREFIX.items():
            if fspath.endswith(prefix + ".py") and not availability[service]:
                # Python 组件：若未设置跳过自动启动，则不跳过（fixture 会启动）
                if service in PYTHON_COMPONENTS and not _SKIP_START:
                    continue
                # 服务不可用，跳过该文件下的所有测试。
                base_url = BASE_URLS[service]
                item.add_marker(
                    pytest.mark.skip(
                        reason=f"服务 {service} 不可用（{base_url} 健康检查失败），跳过集成测试。"
                    )
                )
                break