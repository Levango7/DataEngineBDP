"""E2E 测试专用 conftest：复用 docker/conftest 的服务管理能力并扩展跨领域 fixtures。

本模块是 ``tests/integration/e2e/`` 目录的 pytest 配置入口：

- 通过 ``conftest_path`` 钩子优先复用 ``tests/integration/docker/conftest.py`` 中的
  基础 fixtures（api_client / *_url / *_available）；
- 扩展 E2E 跨领域测试所需的额外服务 URL 与可用性 fixture：
    * karmada / knative / governance / observability / asset_exchange
    * open_api_catalog / industry_templates / stream_batch / nl2sql
- 提供 ``e2e_services_ready`` 总体可用性 fixture，用于跨领域全链路测试的前置判断；
- 提供 ``cross_domain_skipif`` 装饰器工厂，便于在多服务缺失时统一跳过；
- 通过 ``pytest_collection_modifyitems`` 钩子对 ``test_e2e_*`` 文件中
  标记了 ``@pytest.mark.cross_domain`` 的测试在关键服务不可用时自动跳过。

约定：
- 所有新增服务基础 URL 均可通过环境变量覆盖；
- 健康检查路径与 docker/conftest 保持一致风格（Java 用 /actuator/health，
  Go/Python 用 /api/v1/health 或 /health）。
"""

from __future__ import annotations

import os
import time
from typing import Dict

import pytest
import requests

# ---------------------------------------------------------------------------
# 复用 docker/conftest 的公共能力
# ---------------------------------------------------------------------------
# 通过显式导入保证 docker 目录下的 conftest 模块被加载，
# 其中的 api_client / *_url / *_available 等 session 级 fixture 自动可用。
# pytest 会自动发现父级及兄弟目录的 conftest，此处再导入一次以明确意图并便于类型检查。
try:
    from tests.integration.docker.conftest import (  # type: ignore[import-not-found]
        BASE_URLS as _DOCKER_BASE_URLS,
        HEALTH_PATHS as _DOCKER_HEALTH_PATHS,
        DEFAULT_TIMEOUT,
        generate_test_jwt,
        is_service_available as _docker_is_service_available,
        wait_for_service,
    )
except ImportError:  # pragma: no cover — 容错：直接运行 e2e 目录时也能工作
    _DOCKER_BASE_URLS = {}
    _DOCKER_HEALTH_PATHS = {}
    DEFAULT_TIMEOUT = 10

    def generate_test_jwt(*args, **kwargs):  # type: ignore[no-redef]
        import jwt

        now = int(time.time())
        payload = {
            "iss": "shuqing-bigdata",
            "sub": "e2e-tester",
            "tenantId": "e2e-tenant",
            "iat": now,
            "exp": now + 3600,
        }
        return jwt.encode(payload, "it-test-jwt-secret-at-least-32-bytes-long", algorithm="HS256")

    def wait_for_service(base_url, health_path, timeout=30, interval=0.5):  # type: ignore[no-redef]
        deadline = time.time() + timeout
        health_url = base_url.rstrip("/") + health_path
        while time.time() < deadline:
            try:
                resp = requests.get(health_url, timeout=DEFAULT_TIMEOUT)
                if resp.status_code == 200:
                    return True
            except requests.RequestException:
                pass
            try:
                time.sleep(interval)
            except ValueError:
                # HF datasets 库在离线/网络受限时可能 patch time.sleep 并抛错，
                # 服务探测应容忍该异常（等价于本次探测失败），继续重试。
                pass
        return False

    def _docker_is_service_available(name):  # type: ignore[no-redef]
        return False


# ---------------------------------------------------------------------------
# E2E 扩展服务 URL 与健康检查路径
# ---------------------------------------------------------------------------
# 跨领域 E2E 测试涉及的服务远多于父级 conftest 中的 4 个核心模块，
# 此处补充 Karmada / Knative / 治理 / 可观测 / 资产流通 / 开放API /
# 行业模板 / 流批调度 / NL2SQL 等服务的接入信息。
#
# 同时补充 docker/conftest 中定义但父级 tests/integration/conftest.py 未提供的
# 核心服务（finops / llm_gateway / evaluation / finetuning_loop / model_registry /
# finops_dashboard），确保 E2E 测试在仅加载本 conftest 时也能获取所有 fixture。
E2E_BASE_URLS: Dict[str, str] = {
    # docker/conftest 已定义但父级 conftest 未提供的服务（此处补全以保证 fixture 可用）
    "finops": os.environ.get("FINOPS_URL", "http://localhost:18084"),
    "llm_gateway": os.environ.get("LLM_GATEWAY_URL", "http://localhost:18085"),
    "evaluation": os.environ.get("EVALUATION_URL", "http://localhost:18086"),
    "finops_dashboard": os.environ.get("FINOPS_DASHBOARD_URL", "http://localhost:18087"),
    "finetuning_loop": os.environ.get("FINETUNING_LOOP_URL", "http://localhost:18088"),
    "model_registry": os.environ.get("MODEL_REGISTRY_URL", "http://localhost:18089"),
    # E2E 新增服务
    # Sprint 3.1.3 端口卫生：以下宿主机端口与 tests/integration/docker-compose.yml
    # 映射完全对齐（单一真相源）。karmada/observability 原规划端口 18090/18093 已
    # 让渡给入栈的 encaps-tenant/business-portal（二者未入栈，待入栈时重分配端口）。
    "encaps_tenant": os.environ.get("ENCAPS_TENANT_URL", "http://localhost:18090"),
    "knative": os.environ.get("KNATIVE_URL", "http://localhost:18091"),
    "governance": os.environ.get("GOVERNANCE_URL", "http://localhost:18092"),
    "business_portal": os.environ.get("BUSINESS_PORTAL_URL", "http://localhost:18093"),
    "asset_exchange": os.environ.get("ASSET_EXCHANGE_URL", "http://localhost:18094"),
    "open_api_catalog": os.environ.get("OPEN_API_CATALOG_URL", "http://localhost:18095"),
    "industry_templates": os.environ.get("INDUSTRY_TEMPLATES_URL", "http://localhost:18096"),
    "stream_batch": os.environ.get("STREAM_BATCH_URL", "http://localhost:18097"),
    "nl2sql": os.environ.get("NL2SQL_URL", "http://localhost:18098"),
    "finetuning": os.environ.get("FINETUNING_URL", "http://localhost:18099"),
    "materialized_view": os.environ.get("MATERIALIZED_VIEW_URL", "http://localhost:18100"),
}

E2E_HEALTH_PATHS: Dict[str, str] = {
    "finops": "/api/v1/health",
    "llm_gateway": "/health",
    "evaluation": "/health",
    "finops_dashboard": "/api/v1/health",
    "finetuning_loop": "/health",
    "model_registry": "/health",
    "encaps_tenant": "/actuator/health",
    "knative": "/api/v1/health",
    "governance": "/api/v1/health",
    "business_portal": "/api/v1/health",
    "asset_exchange": "/api/v1/health",
    "open_api_catalog": "/api/v1/health",
    "industry_templates": "/api/v1/health",
    "stream_batch": "/actuator/health",
    "nl2sql": "/health",
    "finetuning": "/health",
    "materialized_view": "/api/v1/health",
}

# 核心模块（封装层/SQL网关/Catalog/规则引擎）的基础 URL 与健康检查路径。
# 这些 URL 与父级 conftest 保持一致，但 *_available fixture 在父级未定义，
# 此处补全以便 E2E 测试的细粒度跳过判断能正常工作。
CORE_BASE_URLS: Dict[str, str] = {
    "encaps": os.environ.get("ENCAPS_URL", "http://localhost:18080"),
    "sql_gateway": os.environ.get("SQL_GATEWAY_URL", "http://localhost:18081"),
    "catalog": os.environ.get("CATALOG_URL", "http://localhost:18082"),
    "rule_engine": os.environ.get("RULE_ENGINE_URL", "http://localhost:18083"),
}

CORE_HEALTH_PATHS: Dict[str, str] = {
    "encaps": "/actuator/health",
    "sql_gateway": "/actuator/health",
    "catalog": "/api/v1/health",
    "rule_engine": "/actuator/health",
}

# 跨领域全链路测试所依赖的关键服务清单（任一缺失则跳过对应 cross_domain 测试）。
CROSS_DOMAIN_KEY_SERVICES = (
    "encaps",
    "sql_gateway",
    "catalog",
    "rule_engine",
    "llm_gateway",
    "nl2sql",
    "karmada",
    "finops",
    "finetuning",
    "evaluation",
)


def is_e2e_service_available(name: str) -> bool:
    """检查 E2E 服务是否可用（5 秒内快速探测）。

    对于 E2E_BASE_URLS 中定义的服务，使用本模块的 URL 与健康检查路径；
    对于核心模块（封装层/SQL网关/Catalog/规则引擎），使用 CORE_BASE_URLS。
    """
    if name in E2E_BASE_URLS:
        url = E2E_BASE_URLS[name]
        health_path = E2E_HEALTH_PATHS.get(name, "/api/v1/health")
        return wait_for_service(url, health_path, timeout=5, interval=0.2)
    if name in CORE_BASE_URLS:
        url = CORE_BASE_URLS[name]
        health_path = CORE_HEALTH_PATHS.get(name, "/actuator/health")
        return wait_for_service(url, health_path, timeout=5, interval=0.2)
    return False


# ---------------------------------------------------------------------------
# E2E 扩展 fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def e2e_auth_token() -> str:
    """E2E 测试专用 JWT token（tenantId=e2e-tenant）。"""
    return generate_test_jwt(tenant_id="e2e-tenant", user_id="e2e-tester")


@pytest.fixture(scope="session")
def e2e_api_client(e2e_auth_token) -> requests.Session:
    """E2E 专用 HTTP 客户端，自动注入 Bearer token 与默认超时。"""
    session = requests.Session()
    session.headers.update(
        {
            "Authorization": f"Bearer {e2e_auth_token}",
            "Content-Type": "application/json",
            "X-Tenant-Id": "e2e-tenant",
        }
    )

    original_request = session.request

    def request_with_timeout(method, url, **kwargs):
        kwargs.setdefault("timeout", DEFAULT_TIMEOUT)
        return original_request(method, url, **kwargs)

    session.request = request_with_timeout
    yield session
    session.close()


# 各 E2E 扩展服务 URL fixture。
@pytest.fixture(scope="session")
def karmada_url() -> str:
    return E2E_BASE_URLS["karmada"]


@pytest.fixture(scope="session")
def knative_url() -> str:
    return E2E_BASE_URLS["knative"]


@pytest.fixture(scope="session")
def governance_url() -> str:
    return E2E_BASE_URLS["governance"]


@pytest.fixture(scope="session")
def observability_url() -> str:
    return E2E_BASE_URLS["observability"]


@pytest.fixture(scope="session")
def asset_exchange_url() -> str:
    return E2E_BASE_URLS["asset_exchange"]


@pytest.fixture(scope="session")
def open_api_catalog_url() -> str:
    return E2E_BASE_URLS["open_api_catalog"]


@pytest.fixture(scope="session")
def industry_templates_url() -> str:
    return E2E_BASE_URLS["industry_templates"]


@pytest.fixture(scope="session")
def stream_batch_url() -> str:
    return E2E_BASE_URLS["stream_batch"]


@pytest.fixture(scope="session")
def nl2sql_url() -> str:
    return E2E_BASE_URLS["nl2sql"]


@pytest.fixture(scope="session")
def finetuning_url() -> str:
    return E2E_BASE_URLS["finetuning"]


@pytest.fixture(scope="session")
def materialized_view_url() -> str:
    return E2E_BASE_URLS["materialized_view"]


# 各 E2E 扩展服务可用性 fixture。
@pytest.fixture(scope="session")
def karmada_available() -> bool:
    return is_e2e_service_available("karmada")


@pytest.fixture(scope="session")
def knative_available() -> bool:
    return is_e2e_service_available("knative")


@pytest.fixture(scope="session")
def governance_available() -> bool:
    return is_e2e_service_available("governance")


@pytest.fixture(scope="session")
def observability_available() -> bool:
    return is_e2e_service_available("observability")


@pytest.fixture(scope="session")
def asset_exchange_available() -> bool:
    return is_e2e_service_available("asset_exchange")


@pytest.fixture(scope="session")
def open_api_catalog_available() -> bool:
    return is_e2e_service_available("open_api_catalog")


@pytest.fixture(scope="session")
def industry_templates_available() -> bool:
    return is_e2e_service_available("industry_templates")


@pytest.fixture(scope="session")
def stream_batch_available() -> bool:
    return is_e2e_service_available("stream_batch")


@pytest.fixture(scope="session")
def nl2sql_available() -> bool:
    return is_e2e_service_available("nl2sql")


@pytest.fixture(scope="session")
def finetuning_available() -> bool:
    return is_e2e_service_available("finetuning")


@pytest.fixture(scope="session")
def materialized_view_available() -> bool:
    return is_e2e_service_available("materialized_view")


# ---------------------------------------------------------------------------
# 补全父级 conftest 未提供的核心模块 available fixture 与 docker 服务 URL/available fixture。
# 父级 tests/integration/conftest.py 仅定义了 encaps_url/sql_gateway_url/catalog_url/
# rule_engine_url 与 api_client，但未定义对应的 *_available fixture，也未定义
# finops/llm_gateway/evaluation/finetuning_loop/model_registry/finops_dashboard 的任何 fixture。
# 此处补全以保证 E2E 测试中所有 fixture 引用均可解析。
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def encaps_available() -> bool:
    """封装层是否可用。"""
    return is_e2e_service_available("encaps")


@pytest.fixture(scope="session")
def sql_gateway_available() -> bool:
    """SQL 网关是否可用。"""
    return is_e2e_service_available("sql_gateway")


@pytest.fixture(scope="session")
def catalog_available() -> bool:
    """Catalog 是否可用。"""
    return is_e2e_service_available("catalog")


@pytest.fixture(scope="session")
def rule_engine_available() -> bool:
    """规则引擎是否可用。"""
    return is_e2e_service_available("rule_engine")


# docker/conftest 中定义但父级 conftest 未提供的服务 URL fixture。
@pytest.fixture(scope="session")
def finops_url() -> str:
    """FinOps 成本模型服务基础 URL。"""
    return E2E_BASE_URLS["finops"]


@pytest.fixture(scope="session")
def llm_gateway_url() -> str:
    """LLM 网关基础 URL。"""
    return E2E_BASE_URLS["llm_gateway"]


@pytest.fixture(scope="session")
def evaluation_url() -> str:
    """模型评测平台基础 URL。"""
    return E2E_BASE_URLS["evaluation"]


@pytest.fixture(scope="session")
def finops_dashboard_url() -> str:
    """FinOps 看板服务基础 URL。"""
    return E2E_BASE_URLS["finops_dashboard"]


@pytest.fixture(scope="session")
def finetuning_loop_url() -> str:
    """微调→评测→部署闭环编排服务基础 URL。"""
    return E2E_BASE_URLS["finetuning_loop"]


@pytest.fixture(scope="session")
def model_registry_url() -> str:
    """模型仓库注册部署服务基础 URL。"""
    return E2E_BASE_URLS["model_registry"]


# docker/conftest 中定义但父级 conftest 未提供的服务 available fixture。
@pytest.fixture(scope="session")
def finops_available() -> bool:
    """FinOps 成本模型服务是否可用。"""
    return is_e2e_service_available("finops")


@pytest.fixture(scope="session")
def llm_gateway_available() -> bool:
    """LLM 网关是否可用。"""
    return is_e2e_service_available("llm_gateway")


@pytest.fixture(scope="session")
def evaluation_available() -> bool:
    """模型评测平台是否可用。"""
    return is_e2e_service_available("evaluation")


@pytest.fixture(scope="session")
def finops_dashboard_available() -> bool:
    """FinOps 看板服务是否可用。"""
    return is_e2e_service_available("finops_dashboard")


@pytest.fixture(scope="session")
def finetuning_loop_available() -> bool:
    """微调→评测→部署闭环编排服务是否可用。"""
    return is_e2e_service_available("finetuning_loop")


@pytest.fixture(scope="session")
def model_registry_available() -> bool:
    """模型仓库注册部署服务是否可用。"""
    return is_e2e_service_available("model_registry")


@pytest.fixture(scope="session")
def e2e_services_ready() -> Dict[str, bool]:
    """一次性探测所有 E2E 关键服务可用性，供跨领域测试前置判断。

    Returns:
        服务名 → 是否可用的字典。测试可读取该字典决定是否跳过特定链路。
    """
    readiness: Dict[str, bool] = {}
    for name in CROSS_DOMAIN_KEY_SERVICES:
        readiness[name] = is_e2e_service_available(name)
    # 同时探测扩展服务。
    for name in E2E_BASE_URLS:
        readiness[name] = is_e2e_service_available(name)
    return readiness


# ---------------------------------------------------------------------------
# 自定义标记
# ---------------------------------------------------------------------------
def pytest_configure(config):
    """注册自定义 pytest 标记。"""
    config.addinivalue_line(
        "markers", "cross_domain: 跨领域全链路 E2E 测试，需要多个服务可用"
    )
    config.addinivalue_line(
        "markers", "requirement(req): 标记覆盖的具体需求编号/名称"
    )
    config.addinivalue_line(
        "markers", "p0: P0 优先级需求验收测试"
    )
    config.addinivalue_line(
        "markers", "p1: P1 优先级需求验收测试"
    )
    config.addinivalue_line(
        "markers", "p2: P2 优先级需求验收测试（骨架，Phase 3 实现）"
    )


# ---------------------------------------------------------------------------
# 收集阶段钩子：跨领域测试在关键服务缺失时自动跳过
# ---------------------------------------------------------------------------
def pytest_collection_modifyitems(config, items):
    """对标记了 cross_domain 的测试，若关键服务全部不可用则跳过。

    策略：若 CROSS_DOMAIN_KEY_SERVICES 中可用服务数 < 4（即大部分服务未启动），
    则跳过所有 cross_domain 测试，避免在无 Docker 环境中产生大量连接错误。
    单个测试内部仍可用具体 *_available fixture 做更细粒度跳过。
    """
    available_count = sum(
        1 for name in CROSS_DOMAIN_KEY_SERVICES if is_e2e_service_available(name)
    )
    for item in items:
        cross_domain_marker = item.get_closest_marker("cross_domain")
        if cross_domain_marker is not None and available_count < 4:
            item.add_marker(
                pytest.mark.skip(
                    reason=(
                        f"跨领域 E2E 测试需要至少 4 个关键服务可用，"
                        f"当前仅 {available_count} 个可用（Docker 未启动）"
                    )
                )
            )