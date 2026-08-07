"""Docker 集成测试公共配置与 fixtures。

本模块是数擎大数据平台（ShuqingBigDataPlatform）Docker 集成测试的入口配置，
针对 Docker 直接运行模式（已从 K3s 切换到 Docker）：

- 集中维护 4 个核心模块（封装层/SQL网关/Catalog/规则引擎）的 Docker 容器信息；
- 提供 ``api_client`` 通用 HTTP 客户端 fixture（自动注入 JWT Bearer token）；
- 提供 4 个模块的 URL fixture（session 级别，避免重复创建）；
- 提供 ``wait_for_service`` 工具函数，等待服务就绪；
- 通过 ``pytest_collection_modifyitems`` 钩子在服务不可用时自动跳过对应测试；
- 提供 Docker 容器状态检查 fixture。

Docker 容器配置（与 docker-compose.yml / 部署脚本保持一致）：
    +----------------+-------------------+---------+------------------+
    | 容器名         | 镜像              | 端口    | 模块             |
    +----------------+-------------------+---------+------------------+
    | it-encaps-layer| sq/encaps-layer   | 18080   | 封装层(Java)     |
    | it-sql-gateway | sq/sql-gateway    | 18081   | SQL网关(Java)    |
    | it-catalog     | sq/catalog        | 18082   | Catalog(Go)      |
    | it-rule-engine | sq/rule-engine    | 18083   | 规则引擎(Java)   |
    +----------------+-------------------+---------+------------------+

约定：
- Java 组件健康检查：GET /actuator/health，返回 {"status":"UP",...}
- Go 组件（Catalog）健康检查：GET /api/v1/health，返回 {"status":"UP",...}
- 受保护端点要求 Bearer JWT token，使用 HMAC-SHA256 签名
"""

from __future__ import annotations

import os
import subprocess
import time
from typing import Dict

import jwt
import pytest
import requests


# ---------------------------------------------------------------------------
# Docker 容器与端口配置
# ---------------------------------------------------------------------------
# 各模块基础 URL（端口与 Docker 容器映射一致，使用 18080-18083 避开本机 8080 占用）。
# 可通过环境变量覆盖，便于 CI 或不同主机部署场景。
BASE_URLS: Dict[str, str] = {
    "encaps": os.environ.get("ENCAPS_URL", "http://localhost:18080"),
    "sql_gateway": os.environ.get("SQL_GATEWAY_URL", "http://localhost:18081"),
    "catalog": os.environ.get("CATALOG_URL", "http://localhost:18082"),
    "rule_engine": os.environ.get("RULE_ENGINE_URL", "http://localhost:18083"),
    "finops": os.environ.get("FINOPS_URL", "http://localhost:18084"),
    "llm_gateway": os.environ.get("LLM_GATEWAY_URL", "http://localhost:18085"),
    "evaluation": os.environ.get("EVALUATION_URL", "http://localhost:18086"),
    "finops_dashboard": os.environ.get("FINOPS_DASHBOARD_URL", "http://localhost:18087"),
}

# Docker 容器名（用于 docker ps 状态检查）。
DOCKER_CONTAINERS: Dict[str, str] = {
    "encaps": "it-encaps-layer",
    "sql_gateway": "it-sql-gateway",
    "catalog": "it-catalog",
    "rule_engine": "it-rule-engine",
    "finops": "it-cost-model",
    "llm_gateway": "it-llm-gateway",
    "evaluation": "it-evaluation",
    "finops_dashboard": "it-finops-dashboard",
}

# 各模块健康检查端点路径（Java 用 /actuator/health，Go 用 /api/v1/health）。
HEALTH_PATHS: Dict[str, str] = {
    "encaps": "/actuator/health",
    "sql_gateway": "/actuator/health",
    "catalog": "/api/v1/health",
    "rule_engine": "/actuator/health",
    "finops": "/api/v1/health",
    "llm_gateway": "/health",
    "evaluation": "/health",
    "finops_dashboard": "/api/v1/health",
}


# HTTP 请求默认超时（秒），避免测试卡死。
DEFAULT_TIMEOUT = 10


# ---------------------------------------------------------------------------
# JWT 配置（与各组件 application.yml / 环境变量默认值保持一致）
# ---------------------------------------------------------------------------
# HMAC-SHA 签名密钥，至少 32 字节（256 bit）；生产环境必须通过环境变量覆盖。
JWT_SECRET = os.environ.get(
    "JWT_SECRET", "dev-secret-key-change-in-production-at-least-256-bits"
)
JWT_ISSUER = os.environ.get("JWT_ISSUER", "shuqing-bigdata")


def generate_test_jwt(
    tenant_id: str = "docker-it-tenant",
    user_id: str = "docker-it-tester",
    expiry_seconds: int = 3600,
) -> str:
    """生成 Docker 集成测试用 JWT Bearer token。

    使用与各组件相同的 HMAC-SHA 密钥与 issuer 签发，确保后端能验证通过。
    包含 exp claim（Catalog 的 Go JWT 库要求必须有过期时间）。

    Args:
        tenant_id: 租户 ID，写入 ``tenantId`` claim。
        user_id:  用户 ID，写入 ``sub`` claim。
        expiry_seconds: token 有效期秒数，默认 1 小时。

    Returns:
        编码后的 JWT 字符串。
    """
    now = int(time.time())
    payload = {
        "iss": JWT_ISSUER,
        "sub": user_id,
        "tenantId": tenant_id,
        "iat": now,
        "exp": now + expiry_seconds,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm="HS256")


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def wait_for_service(
    base_url: str, health_path: str, timeout: int = 30, interval: float = 0.5
) -> bool:
    """轮询等待服务健康检查通过。

    Args:
        base_url: 服务基础 URL，例如 ``http://localhost:18080``。
        health_path: 健康检查路径，例如 ``/actuator/health``。
        timeout: 最长等待秒数，超时返回 ``False``。
        interval: 轮询间隔秒数。

    Returns:
        ``True`` 表示服务在超时前就绪；``False`` 表示超时仍未就绪。
    """
    deadline = time.time() + timeout
    health_url = base_url.rstrip("/") + health_path
    while time.time() < deadline:
        try:
            resp = requests.get(health_url, timeout=DEFAULT_TIMEOUT)
            if resp.status_code == 200:
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
    """检查指定模块是否可用（短时间内探测一次）。

    与 ``wait_for_service`` 不同，本函数只做一次快速探测（5 秒内），
    用于在收集阶段决定是否跳过测试，避免长时间阻塞。

    Args:
        name: 模块名，必须在 BASE_URLS 中。

    Returns:
        ``True`` 表示模块就绪；``False`` 表示不可用。
    """
    url = BASE_URLS.get(name)
    if not url:
        return False
    health_path = HEALTH_PATHS.get(name, "/actuator/health")
    return wait_for_service(url, health_path, timeout=5, interval=0.2)


def is_docker_container_running(container_name: str) -> bool:
    """检查指定 Docker 容器是否正在运行。

    通过 ``docker inspect`` 查询容器状态，避免依赖 ``docker ps`` 文本解析。

    Args:
        container_name: 容器名，例如 ``it-encaps-layer``。

    Returns:
        ``True`` 表示容器正在运行；``False`` 表示未运行或不存在。
    """
    try:
        result = subprocess.run(
            ["docker", "inspect", "--format", "{{.State.Running}}", container_name],
            capture_output=True,
            text=True,
            timeout=10,
        )
        return result.returncode == 0 and result.stdout.strip() == "true"
    except (subprocess.SubprocessError, FileNotFoundError):
        return False


# ---------------------------------------------------------------------------
# pytest fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def auth_token() -> str:
    """生成 session 级别的 JWT Bearer token。

    所有测试共享同一个 token，避免重复签发开销。
    """
    return generate_test_jwt()


@pytest.fixture(scope="session")
def api_client(auth_token) -> requests.Session:
    """提供预配置的 HTTP 客户端（requests.Session）。

    自动注入 ``Authorization: Bearer <token>`` 头，简化受保护端点调用。
    设置默认超时，避免测试卡死。

    Yields:
        配置好认证头的 requests.Session 实例。
    """
    session = requests.Session()
    session.headers.update(
        {
            "Authorization": f"Bearer {auth_token}",
            "Content-Type": "application/json",
        }
    )
    session.request = _request_with_timeout(session.request, DEFAULT_TIMEOUT)
    yield session
    session.close()


def _request_with_timeout(original_request, timeout):
    """包装 Session.request 以注入默认超时。"""

    def wrapper(method, url, **kwargs):
        kwargs.setdefault("timeout", timeout)
        return original_request(method, url, **kwargs)

    return wrapper


# 各模块 URL fixture（session 级别）。
@pytest.fixture(scope="session")
def encaps_url() -> str:
    """封装层基础 URL。"""
    return BASE_URLS["encaps"]


@pytest.fixture(scope="session")
def sql_gateway_url() -> str:
    """SQL 网关基础 URL。"""
    return BASE_URLS["sql_gateway"]


@pytest.fixture(scope="session")
def catalog_url() -> str:
    """Catalog 基础 URL。"""
    return BASE_URLS["catalog"]


@pytest.fixture(scope="session")
def rule_engine_url() -> str:
    """规则引擎基础 URL。"""
    return BASE_URLS["rule_engine"]


@pytest.fixture(scope="session")
def finops_url() -> str:
    """FinOps 成本模型服务基础 URL。"""
    return BASE_URLS["finops"]


@pytest.fixture(scope="session")
def llm_gateway_url() -> str:
    """LLM 网关基础 URL。"""
    return BASE_URLS["llm_gateway"]


@pytest.fixture(scope="session")
def evaluation_url() -> str:
    """模型评测平台基础 URL。"""
    return BASE_URLS["evaluation"]


# 各模块可用性 fixture（用于条件跳过）。
@pytest.fixture(scope="session")
def encaps_available() -> bool:
    """封装层是否可用。"""
    return is_service_available("encaps")


@pytest.fixture(scope="session")
def sql_gateway_available() -> bool:
    """SQL 网关是否可用。"""
    return is_service_available("sql_gateway")


@pytest.fixture(scope="session")
def catalog_available() -> bool:
    """Catalog 是否可用。"""
    return is_service_available("catalog")


@pytest.fixture(scope="session")
def rule_engine_available() -> bool:
    """规则引擎是否可用。"""
    return is_service_available("rule_engine")


@pytest.fixture(scope="session")
def finops_available() -> bool:
    """FinOps 成本模型服务是否可用。"""
    return is_service_available("finops")


@pytest.fixture(scope="session")
def llm_gateway_available() -> bool:
    """LLM 网关是否可用。"""
    return is_service_available("llm_gateway")


@pytest.fixture(scope="session")
def evaluation_available() -> bool:
    """模型评测平台是否可用。"""
    return is_service_available("evaluation")


@pytest.fixture(scope="session")
def finops_dashboard_available() -> bool:
    """FinOps 看板服务是否可用。"""
    return is_service_available("finops_dashboard")


# ---------------------------------------------------------------------------
# 测试收集阶段钩子：自动跳过服务不可用的测试
# ---------------------------------------------------------------------------
def pytest_collection_modifyitems(config, items):
    """根据服务可用性自动跳过对应测试。

    通过测试函数名前缀判断所属模块，若模块不可用则标记 skip。
    避免在 Docker 容器未启动时产生大量连接错误。
    """
    # 模块名 → 可用性检查函数的映射。
    module_availability = {
        "encaps": is_service_available("encaps"),
        "sql_gateway": is_service_available("sql_gateway"),
        "catalog": is_service_available("catalog"),
        "rule_engine": is_service_available("rule_engine"),
        "finops": is_service_available("finops"),
        "llm_gateway": is_service_available("llm_gateway"),
        "evaluation": is_service_available("evaluation"),
        "finops_dashboard": is_service_available("finops_dashboard"),
    }

    # 测试文件名前缀 → 模块名映射。
    # 注意：test_model_evaluation 包含核心组件测试（不需服务）与 HTTP API 测试，
    # 不在此整体跳过，由测试文件内部通过 evaluation_available fixture 控制 HTTP 测试跳过。
    file_module_map = {
        "test_docker_encaps": "encaps",
        "test_docker_sql_gateway": "sql_gateway",
        "test_docker_catalog": "catalog",
        "test_docker_rule_engine": "rule_engine",
        "test_finops": "finops",
        "test_finops_dashboard": "finops_dashboard",
        "test_multimodal_gateway": "llm_gateway",
        "test_docker_llm_gateway": "llm_gateway",
    }

    for item in items:
        # 从测试文件的模块名提取前缀。
        module_prefix = item.module.__name__.split(".")[-1]
        if module_prefix in file_module_map:
            module_name = file_module_map[module_prefix]
            if not module_availability.get(module_name, False):
                item.add_marker(
                    pytest.mark.skip(
                        reason=f"Docker 容器 {DOCKER_CONTAINERS[module_name]} 不可用"
                    )
                )
        # 跨服务测试需要所有模块可用。
        if module_prefix == "test_docker_cross_service":
            unavailable = [
                DOCKER_CONTAINERS[m]
                for m, available in module_availability.items()
                if not available
            ]
            if unavailable:
                item.add_marker(
                    pytest.mark.skip(
                        reason=f"跨服务测试需要所有模块可用，不可用: {unavailable}"
                    )
                )