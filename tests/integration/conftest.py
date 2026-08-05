"""pytest 集成测试公共配置与 fixtures。

本模块是数擎大数据平台（ShuqingBigDataPlatform）集成测试的入口配置：
- 集中维护各组件 REST API 基础 URL；
- 提供 ``api_client`` 通用 HTTP 客户端 fixture；
- 提供 4 个组件的 URL fixture（session 级别，避免重复创建）；
- 提供 ``wait_for_service`` 工具函数，等待服务就绪；
- 通过 ``pytest_collection_modifyitems`` 钩子在服务不可用时自动跳过对应测试。

约定：所有组件均暴露 ``GET /api/v1/health``，返回 ``{"status": "UP", ...}``。
"""

from __future__ import annotations

import os
import time
from typing import Dict

import jwt
import pytest
import requests


# 各组件基础 URL。集中维护，便于环境变量或 CI 覆盖。
# 端口与 docker-compose.yml 保持一致（使用 18080-18083 避开本机 8080 占用）。
BASE_URLS: Dict[str, str] = {
    "encaps": "http://localhost:18080",
    "sql_gateway": "http://localhost:18081",
    "catalog": "http://localhost:18082",
    "rule_engine": "http://localhost:18083",
}

# HTTP 请求默认超时（秒），避免测试卡死。
DEFAULT_TIMEOUT = 10

# 健康检查路径（所有组件统一）。
HEALTH_PATH = "/api/v1/health"

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


def is_service_available(name: str) -> bool:
    """检查指定组件是否可用（短时间内探测一次）。

    与 ``wait_for_service`` 不同，本函数只做一次快速探测（3 秒内），
    用于在收集阶段决定是否跳过测试，避免长时间阻塞。
    """
    url = BASE_URLS.get(name)
    if not url:
        return False
    return wait_for_service(url, timeout=3, interval=0.2)


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
}


def pytest_collection_modifyitems(config, items):
    """收集阶段钩子：在对应服务不可用时自动跳过其测试。

    跳过原因会写入 ``pytest.mark.skip`` 的 reason 字段，便于在报告中查看。
    """
    # 预先探测各服务可用性，避免每个测试都探测一次。
    availability = {name: is_service_available(name) for name in BASE_URLS}

    for item in items:
        # item.name 形如 "test_health_check"，无法区分所属文件；
        # 改用 item.fspath 的文件名前缀判断。
        fspath = str(item.fspath)
        for service, prefix in _SERVICE_TEST_PREFIX.items():
            if fspath.endswith(prefix + ".py") and not availability[service]:
                # 服务不可用，跳过该文件下的所有测试。
                base_url = BASE_URLS[service]
                item.add_marker(
                    pytest.mark.skip(
                        reason=f"服务 {service} 不可用（{base_url} 健康检查失败），跳过集成测试。"
                    )
                )
                break