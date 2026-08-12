"""T040 开放 API 服务目录集成测试.

覆盖场景（≥20 个测试用例）：
    1. 一键生成场景（SQL/模型/函数三种生成方式）
    2. 订阅场景（Key 颁发 + Secret）
    3. 限流场景（超限返回 429）
    4. 计费场景（按次/按量/订阅三种计费方式，计量准确）
    5. APISIX 场景（插件链配置正确）

测试策略：
    - 默认使用 FastAPI TestClient（无需 Docker），验证业务逻辑正确性
    - 当环境变量 OPEN_API_CATALOG_URL 存在时，自动切换为真实 HTTP 集成测试
    - YAML 配置文件通过 yaml.safe_load 验证语法正确性
"""
from __future__ import annotations

import os
from pathlib import Path

import pytest

# ---------------------------------------------------------------------------
# 测试模式选择：TestClient（默认）或真实 HTTP
# ---------------------------------------------------------------------------
OPEN_API_CATALOG_URL = os.environ.get("OPEN_API_CATALOG_URL", "")
USE_HTTP = bool(OPEN_API_CATALOG_URL)

# 后端项目根目录
BACKEND_ROOT = Path(__file__).resolve().parents[3] / "platform" / "open-api-catalog"
APISIX_DIR = BACKEND_ROOT / "apisix"


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def catalog_base_url() -> str:
    """开放 API 服务目录基础 URL（仅 HTTP 模式使用）."""
    return OPEN_API_CATALOG_URL or "http://localhost:8090"


@pytest.fixture(scope="session")
def catalog_available() -> bool:
    """开放 API 服务目录是否可用."""
    if not USE_HTTP:
        return True  # TestClient 模式始终可用
    import requests
    try:
        resp = requests.get(f"{OPEN_API_CATALOG_URL}/api/v1/health", timeout=5)
        return resp.status_code == 200
    except Exception:
        return False


@pytest.fixture(scope="function")
def client(catalog_available):
    """提供测试客户端.

    - TestClient 模式：返回 FastAPI TestClient
    - HTTP 模式：返回 _HttpClient 包装器
    """
    if not catalog_available:
        pytest.skip("开放 API 服务目录不可用")

    if USE_HTTP:
        return _HttpClient(OPEN_API_CATALOG_URL)

    # TestClient 模式
    os.environ["OPENAPI_CATALOG_STORE_TYPE"] = "mock"

    # 动态导入（避免在模块加载时强制依赖 fastapi）
    from fastapi.testclient import TestClient
    from openapi_catalog.api.app import create_app
    from openapi_catalog.config.settings import Settings, reset_settings
    from openapi_catalog.repositories.mock import MockCatalogStore
    from openapi_catalog.services.api_call import APICallService
    from openapi_catalog.services.api_registry import APIRegistryService
    from openapi_catalog.services.apisix_config import APISIXConfigService
    from openapi_catalog.services.doc_generator import DocGeneratorService
    from openapi_catalog.services.metering import MeteringService
    from openapi_catalog.services.rate_limiter import RateLimiter
    from openapi_catalog.services.registry import ServiceRegistry
    from openapi_catalog.services.subscription import SubscriptionService

    reset_settings()
    settings = Settings(storeType="mock")
    store = MockCatalogStore()
    rate_limiter = RateLimiter()
    registry = ServiceRegistry(
        settings=settings,
        store=store,
        rateLimiter=rate_limiter,
        apiRegistryService=APIRegistryService(store),
        subscriptionService=SubscriptionService(store),
        meteringService=MeteringService(store),
        apisixConfigService=APISIXConfigService(store, settings),
        docGeneratorService=DocGeneratorService(store),
        apiCallService=APICallService(
            store, SubscriptionService(store), rate_limiter, MeteringService(store)
        ),
    )
    app = create_app(settings=settings, registry=registry)
    return TestClient(app)


class _HttpClient:
    """HTTP 客户端包装器（模拟 TestClient 接口）."""

    def __init__(self, base_url: str):
        import requests
        self._session = requests.Session()
        self._base = base_url.rstrip("/")

    def _url(self, path: str) -> str:
        return self._base + path

    def get(self, path, **kwargs):
        return self._session.get(self._url(path), **kwargs)

    def post(self, path, **kwargs):
        return self._session.post(self._url(path), **kwargs)

    def put(self, path, **kwargs):
        return self._session.put(self._url(path), **kwargs)

    def delete(self, path, **kwargs):
        return self._session.delete(self._url(path), **kwargs)


# ---------------------------------------------------------------------------
# 辅助函数
# ---------------------------------------------------------------------------
def _publish_api(client, api_id: str) -> dict:
    """走完发布流程：submit-review → approve → publish."""
    client.post(f"/api/v1/apis/{api_id}/submit-review")
    client.post(f"/api/v1/apis/{api_id}/approve")
    resp = client.post(f"/api/v1/apis/{api_id}/publish")
    return resp.json()


def _create_and_publish(client, name: str, **overrides) -> str:
    """创建 API 并发布，返回 api_id."""
    body = {
        "name": name,
        "version": "1.0.0",
        "method": "GET",
        "path": f"/test-{name}",
        "upstream": {
            "type": "trino",
            "url": "http://trino:8080/v1/statement",
            "method": "GET",
        },
        "providerTenantId": "tenant-provider",
        "costStrategy": "by_call",
        "costUnitPrice": 0.01,
    }
    body.update(overrides)
    resp = client.post("/api/v1/apis", json=body)
    assert resp.status_code == 201, f"创建 API 失败: {resp.text}"
    api_id = resp.json()["id"]
    _publish_api(client, api_id)
    return api_id


def _subscribe_and_approve(client, api_id: str, granted_quota: int = 100) -> dict:
    """订阅并审批通过，返回订阅信息（含 AK/SK）."""
    sub_resp = client.post(
        f"/api/v1/apis/{api_id}/subscribe",
        json={
            "subscriberId": "sub-test",
            "subscriberTenantId": "tenant-consumer",
            "purpose": "集成测试",
            "quotaExpect": granted_quota,
        },
    )
    assert sub_resp.status_code == 201, f"订阅失败: {sub_resp.text}"
    sub_id = sub_resp.json()["id"]

    approve_resp = client.post(
        f"/api/v1/subscriptions/{sub_id}/approve",
        json={"approve": True, "grantedQuota": granted_quota, "approver": "tester"},
    )
    assert approve_resp.status_code == 200, f"审批失败: {approve_resp.text}"
    return approve_resp.json()


# ===========================================================================
# 1. 健康检查
# ===========================================================================
class TestHealth:
    """健康检查测试."""

    def test_health_check(self, client):
        """测试 1: 健康检查端点返回 UP."""
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "UP"
        assert data["module"] == "open-api-catalog"


# ===========================================================================
# 2. 一键生成场景（SQL/模型/函数）
# ===========================================================================
class TestGenerateSql:
    """SQL 一键生成测试."""

    def test_generate_sql_basic(self, client):
        """测试 2: SQL 一键生成基础场景."""
        resp = client.post(
            "/api/v1/apis/generate/sql",
            json={
                "name": "weather-query",
                "sql": "SELECT * FROM weather WHERE city = :city",
                "datasource": "trino",
                "providerTenantId": "tenant-provider",
                "description": "天气查询",
            },
        )
        assert resp.status_code == 201, f"生成失败: {resp.text}"
        data = resp.json()
        assert data["name"] == "weather-query"
        assert data["category"] == "sql"
        assert data["method"] == "POST"
        assert data["path"] == "/sql/weather-query"
        assert "sql-generated" in data["tags"]
        assert "datasource:trino" in data["tags"]
        assert data["upstream"]["type"] == "trino"

    def test_generate_sql_with_params(self, client):
        """测试 3: SQL 参数自动解析."""
        resp = client.post(
            "/api/v1/apis/generate/sql",
            json={
                "name": "order-query",
                "sql": "SELECT * FROM orders WHERE user_id = :user_id AND status = :status AND is_paid = :is_paid",
                "datasource": "doris",
                "providerTenantId": "tenant-provider",
            },
        )
        assert resp.status_code == 201
        data = resp.json()
        # 应解析出 3 个参数
        param_names = {p["name"] for p in data["params"]}
        assert "user_id" in param_names
        assert "status" in param_names
        assert "is_paid" in param_names
        # 类型推断：user_id → integer
        user_id_param = next(p for p in data["params"] if p["name"] == "user_id")
        assert user_id_param["type"] == "integer"
        # is_paid → boolean
        is_paid_param = next(p for p in data["params"] if p["name"] == "is_paid")
        assert is_paid_param["type"] == "boolean"

    def test_generate_sql_forbidden_operation(self, client):
        """测试 4: SQL 包含 DROP 应被拒绝."""
        resp = client.post(
            "/api/v1/apis/generate/sql",
            json={
                "name": "danger-api",
                "sql": "DROP TABLE users",
                "datasource": "trino",
                "providerTenantId": "tenant-provider",
            },
        )
        assert resp.status_code == 422 or resp.status_code == 400

    def test_generate_sql_unsupported_datasource(self, client):
        """测试 5: 不支持的数据源应返回错误."""
        resp = client.post(
            "/api/v1/apis/generate/sql",
            json={
                "name": "bad-ds",
                "sql": "SELECT 1",
                "datasource": "oracle",
                "providerTenantId": "tenant-provider",
            },
        )
        assert resp.status_code == 422 or resp.status_code == 400

    def test_generate_sql_with_billing(self, client):
        """测试 6: SQL 生成时配置计费策略."""
        resp = client.post(
            "/api/v1/apis/generate/sql",
            json={
                "name": "billing-sql",
                "sql": "SELECT * FROM products WHERE category = :category",
                "datasource": "mysql",
                "providerTenantId": "tenant-provider",
                "costStrategy": "by_bytes",
                "costUnitPrice": 0.001,
            },
        )
        assert resp.status_code == 201
        data = resp.json()
        assert data["costStrategy"] == "by_bytes"
        assert data["costUnitPrice"] == 0.001


class TestGenerateModel:
    """模型一键生成测试."""

    def test_generate_model_llm(self, client):
        """测试 7: LLM 模型一键生成."""
        resp = client.post(
            "/api/v1/apis/generate/model",
            json={
                "name": "llm-chat",
                "modelId": "gpt-4o-mini",
                "modelType": "llm",
                "providerTenantId": "tenant-provider",
            },
        )
        assert resp.status_code == 201
        data = resp.json()
        assert data["category"] == "model"
        assert data["upstream"]["type"] == "llm"
        assert "model:gpt-4o-mini" in data["tags"]
        assert "model-type:llm" in data["tags"]
        # LLM 应有 messages 参数
        param_names = {p["name"] for p in data["params"]}
        assert "messages" in param_names
        assert "temperature" in param_names

    def test_generate_model_embedding(self, client):
        """测试 8: Embedding 模型一键生成."""
        resp = client.post(
            "/api/v1/apis/generate/model",
            json={
                "name": "text-embedding",
                "modelId": "bge-large-zh",
                "modelType": "embedding",
                "providerTenantId": "tenant-provider",
            },
        )
        assert resp.status_code == 201
        data = resp.json()
        param_names = {p["name"] for p in data["params"]}
        assert "input" in param_names

    def test_generate_model_with_input_schema(self, client):
        """测试 9: 模型生成时使用自定义 inputSchema."""
        resp = client.post(
            "/api/v1/apis/generate/model",
            json={
                "name": "custom-model",
                "modelId": "custom-llm",
                "modelType": "llm",
                "providerTenantId": "tenant-provider",
                "inputSchema": {
                    "properties": {
                        "prompt": {"type": "string", "description": "提示词"},
                        "max_length": {"type": "integer", "description": "最大长度"},
                    },
                    "required": ["prompt"],
                },
            },
        )
        assert resp.status_code == 201
        data = resp.json()
        param_names = {p["name"] for p in data["params"]}
        assert "prompt" in param_names
        assert "max_length" in param_names
        prompt_param = next(p for p in data["params"] if p["name"] == "prompt")
        assert prompt_param["required"] is True


class TestGenerateFunction:
    """函数一键生成测试."""

    def test_generate_function_python(self, client):
        """测试 10: Python 函数一键生成."""
        resp = client.post(
            "/api/v1/apis/generate/function",
            json={
                "name": "data-transform",
                "functionName": "transform_handler",
                "runtime": "python",
                "providerTenantId": "tenant-provider",
                "timeout": 30000,
                "memoryMB": 512,
            },
        )
        assert resp.status_code == 201
        data = resp.json()
        assert data["category"] == "function"
        assert data["upstream"]["type"] == "function"
        assert "function:transform_handler" in data["tags"]
        assert "runtime:python" in data["tags"]
        assert data["upstream"]["timeout"] == 30000

    def test_generate_function_nodejs(self, client):
        """测试 11: Node.js 函数一键生成."""
        resp = client.post(
            "/api/v1/apis/generate/function",
            json={
                "name": "image-resize",
                "functionName": "resize_handler",
                "runtime": "nodejs",
                "providerTenantId": "tenant-provider",
            },
        )
        assert resp.status_code == 201
        data = resp.json()
        assert "runtime:nodejs" in data["tags"]

    def test_generate_options(self, client):
        """测试 12: 获取生成选项."""
        resp = client.get("/api/v1/apis/generate/options")
        assert resp.status_code == 200
        data = resp.json()
        assert "trino" in data["datasources"]
        assert "llm" in data["modelTypes"]
        assert "python" in data["runtimes"]


# ===========================================================================
# 3. 订阅场景（Key 颁发 + Secret）
# ===========================================================================
class TestSubscription:
    """订阅与 Key 颁发测试."""

    def test_subscribe_and_approve(self, client):
        """测试 13: 订阅并审批通过，颁发 AK/SK."""
        api_id = _create_and_publish(client, "sub-test-api")
        sub_data = _subscribe_and_approve(client, api_id)
        assert sub_data["status"] == "active"
        assert sub_data["accessKey"] is not None
        assert sub_data["secretKey"] is not None
        assert sub_data["accessKey"].startswith("AK")
        assert sub_data["secretKey"].startswith("SK")

    def test_issue_new_key(self, client):
        """测试 14: 重新颁发 AK/SK."""
        api_id = _create_and_publish(client, "reissue-test")
        sub_data = _subscribe_and_approve(client, api_id)
        sub_id = sub_data["id"]
        old_ak = sub_data["accessKey"]

        # 重新颁发
        resp = client.post(
            f"/api/v1/subscriptions/{sub_id}/keys",
            json={"reason": "Key 泄露", "operator": "admin"},
        )
        assert resp.status_code == 201
        new_data = resp.json()
        assert new_data["accessKey"] != old_ak
        assert new_data["secretKey"] is not None

    def test_get_key_info(self, client):
        """测试 15: 查询 Key 信息（不返回 SK）."""
        api_id = _create_and_publish(client, "key-info-test")
        sub_data = _subscribe_and_approve(client, api_id)
        sub_id = sub_data["id"]

        resp = client.get(f"/api/v1/subscriptions/{sub_id}/keys")
        assert resp.status_code == 200
        data = resp.json()
        assert data["accessKey"] is not None
        assert data["hasSecretKey"] is True
        # 出于安全考虑，不返回 secretKey
        assert "secretKey" not in data or data.get("secretKey") is None


# ===========================================================================
# 4. 限流场景（超限返回 429）
# ===========================================================================
class TestRateLimit:
    """限流测试."""

    def test_rate_limit_config(self, client):
        """测试 16: 配置限流."""
        api_id = _create_and_publish(client, "rl-config-test")
        sub_data = _subscribe_and_approve(client, api_id)
        sub_id = sub_data["id"]

        resp = client.put(
            f"/api/v1/subscriptions/{sub_id}/rate-limit",
            json={"qps": 50, "concurrent": 10, "burst": 100},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["qps"] == 50
        assert data["concurrent"] == 10

    def test_rate_limit_query(self, client):
        """测试 17: 查询限流配置."""
        api_id = _create_and_publish(client, "rl-query-test")
        sub_data = _subscribe_and_approve(client, api_id)
        sub_id = sub_data["id"]

        # 先配置
        client.put(
            f"/api/v1/subscriptions/{sub_id}/rate-limit",
            json={"qps": 30, "concurrent": 5, "burst": 60},
        )
        # 再查询
        resp = client.get(f"/api/v1/subscriptions/{sub_id}/rate-limit")
        assert resp.status_code == 200
        data = resp.json()
        assert data["qps"] == 30

    def test_call_rate_limited_429(self, client):
        """测试 18: 超限调用返回 429."""
        api_id = _create_and_publish(client, "rl-429-test")
        sub_data = _subscribe_and_approve(client, api_id, granted_quota=100)
        ak = sub_data["accessKey"]

        # 配置极低限流：QPS=1
        client.put(
            f"/api/v1/subscriptions/{sub_data['id']}/rate-limit",
            json={"qps": 1, "concurrent": 0, "burst": 0},
        )

        # 第一次调用应成功
        r1 = client.post(
            f"/api/v1/apis/{api_id}/call",
            json={"payload": {}},
            headers={"X-API-Key": ak},
        )
        assert r1.status_code == 200
        assert r1.json()["statusCode"] == 200

        # 第二次调用应被限流（429）
        r2 = client.post(
            f"/api/v1/apis/{api_id}/call",
            json={"payload": {}},
            headers={"X-API-Key": ak},
        )
        assert r2.status_code == 200
        assert r2.json()["statusCode"] == 429


# ===========================================================================
# 5. 计费场景（按次/按量/订阅）
# ===========================================================================
class TestBilling:
    """计费策略测试."""

    def test_billing_config_by_call(self, client):
        """测试 19: 配置按次计费."""
        api_id = _create_and_publish(client, "billing-by-call")
        resp = client.put(
            f"/api/v1/apis/{api_id}/billing",
            json={"costStrategy": "by_call", "costUnitPrice": 0.05},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["costStrategy"] == "by_call"
        assert data["costUnitPrice"] == 0.05
        assert "按次" in data["description"]

    def test_billing_config_by_bytes(self, client):
        """测试 20: 配置按量计费."""
        api_id = _create_and_publish(client, "billing-by-bytes")
        resp = client.put(
            f"/api/v1/apis/{api_id}/billing",
            json={"costStrategy": "by_bytes", "costUnitPrice": 0.001},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["costStrategy"] == "by_bytes"
        assert "按量" in data["description"]

    def test_billing_config_monthly_package(self, client):
        """测试 21: 配置月包计费."""
        api_id = _create_and_publish(client, "billing-monthly")
        resp = client.put(
            f"/api/v1/apis/{api_id}/billing",
            json={
                "costStrategy": "monthly_package",
                "costUnitPrice": 100.0,
                "monthlyQuota": 10000,
            },
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["costStrategy"] == "monthly_package"
        assert data["monthlyQuota"] == 10000
        assert "订阅" in data["description"]

    def test_billing_monthly_without_quota_fails(self, client):
        """测试 22: 月包计费未指定配额应失败."""
        api_id = _create_and_publish(client, "billing-monthly-fail")
        resp = client.put(
            f"/api/v1/apis/{api_id}/billing",
            json={"costStrategy": "monthly_package", "costUnitPrice": 100.0},
        )
        assert resp.status_code == 400

    def test_billing_query(self, client):
        """测试 23: 查询计费配置."""
        api_id = _create_and_publish(client, "billing-query")
        client.put(
            f"/api/v1/apis/{api_id}/billing",
            json={"costStrategy": "by_call", "costUnitPrice": 0.02},
        )
        resp = client.get(f"/api/v1/apis/{api_id}/billing")
        assert resp.status_code == 200
        data = resp.json()
        assert data["costStrategy"] == "by_call"
        assert data["costUnitPrice"] == 0.02

    def test_billing_accuracy_by_call(self, client):
        """测试 24: 按次计费计量准确."""
        api_id = _create_and_publish(
            client, "billing-accurate-call", costStrategy="by_call", costUnitPrice=0.05
        )
        sub_data = _subscribe_and_approve(client, api_id)
        ak = sub_data["accessKey"]

        # 调用 3 次
        for _ in range(3):
            client.post(
                f"/api/v1/apis/{api_id}/call",
                json={"payload": {}},
                headers={"X-API-Key": ak},
            )

        # 查询计量
        resp = client.get(f"/api/v1/apis/{api_id}/metrics")
        assert resp.status_code == 200
        data = resp.json()
        assert data["callCount"] == 3
        # 总费用 = 3 * 0.05 = 0.15
        assert abs(data["totalCost"] - 0.15) < 0.001

    def test_billing_accuracy_by_bytes(self, client):
        """测试 25: 按量计费计量准确."""
        api_id = _create_and_publish(
            client, "billing-accurate-bytes", costStrategy="by_bytes", costUnitPrice=0.001
        )
        sub_data = _subscribe_and_approve(client, api_id)
        ak = sub_data["accessKey"]

        # 调用 2 次
        for _ in range(2):
            client.post(
                f"/api/v1/apis/{api_id}/call",
                json={"payload": {"data": "test"}},
                headers={"X-API-Key": ak},
            )

        resp = client.get(f"/api/v1/apis/{api_id}/metrics")
        assert resp.status_code == 200
        data = resp.json()
        assert data["callCount"] == 2
        # 按量计费，总费用 > 0
        assert data["totalCost"] > 0


# ===========================================================================
# 6. APISIX 场景（插件链配置正确）
# ===========================================================================
class TestAPISIXConfig:
    """APISIX 插件链配置测试."""

    def test_apisix_route_config(self, client):
        """测试 26: APISIX 路由配置生成正确."""
        api_id = _create_and_publish(client, "apisix-route-test")
        resp = client.get(f"/api/v1/apis/{api_id}/apisix-config")
        assert resp.status_code == 200
        data = resp.json()
        assert "uri" in data
        assert "methods" in data
        assert "upstream" in data
        assert "plugins" in data

    def test_apisix_plugin_chain_order(self, client):
        """测试 27: APISIX 插件链包含认证→限流→计量→路由."""
        api_id = _create_and_publish(client, "apisix-chain-test")
        resp = client.get(f"/api/v1/apis/{api_id}/apisix-config")
        data = resp.json()
        plugins = data["plugins"]
        # 认证插件
        assert "key-auth" in plugins
        # 限流插件
        assert "limit-req" in plugins
        assert plugins["limit-req"]["rejected_code"] == 429
        # 熔断插件
        assert "circuit-breaker" in plugins
        # 计量插件（Prometheus）
        assert "prometheus" in plugins
        # 路由重写插件（租户隔离）
        assert "proxy-rewrite" in plugins
        assert "X-Provider-Tenant" in plugins["proxy-rewrite"]["headers"]["set"]

    def test_apisix_deploy(self, client):
        """测试 28: APISIX 路由部署."""
        api_id = _create_and_publish(client, "apisix-deploy-test")
        resp = client.post(f"/api/v1/apis/{api_id}/apisix-deploy")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        assert data["routeId"] == api_id


# ===========================================================================
# 7. APISIX YAML 配置文件验证
# ===========================================================================
class TestAPISIXYamlConfig:
    """APISIX YAML 配置文件语法验证."""

    def test_routes_yaml_valid(self):
        """测试 29: routes.yaml 语法正确."""
        import yaml
        path = APISIX_DIR / "routes.yaml"
        assert path.exists(), f"routes.yaml 不存在: {path}"
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        assert "routes" in data
        assert len(data["routes"]) >= 3  # SQL/模型/函数三种路由模板

    def test_key_auth_yaml_valid(self):
        """测试 30: key-auth.yaml 语法正确."""
        import yaml
        path = APISIX_DIR / "plugins" / "key-auth.yaml"
        assert path.exists()
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        assert data["plugin_name"] == "key-auth"
        assert "config" in data
        assert data["config"]["header"] == "X-API-Key"

    def test_limit_req_yaml_valid(self):
        """测试 31: limit-req.yaml 语法正确."""
        import yaml
        path = APISIX_DIR / "plugins" / "limit-req.yaml"
        assert path.exists()
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        assert data["plugin_name"] == "limit-req"
        assert "default_api_level" in data
        assert data["default_api_level"]["rejected_code"] == 429

    def test_metering_yaml_valid(self):
        """测试 32: metering.yaml 语法正确."""
        import yaml
        path = APISIX_DIR / "plugins" / "metering.yaml"
        assert path.exists()
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        assert data["plugin_name"] == "serverless-metering"
        assert "billing_strategies" in data
        assert "by_call" in data["billing_strategies"]
        assert "by_bytes" in data["billing_strategies"]
        assert "monthly_package" in data["billing_strategies"]

    def test_apisix_config_yaml_valid(self):
        """测试 33: apisix-config.yaml 语法正确."""
        import yaml
        path = APISIX_DIR / "apisix-config.yaml"
        assert path.exists()
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        assert "plugins" in data
        assert "key-auth" in data["plugins"]
        assert "limit-req" in data["plugins"]
        assert "plugin_order" in data


# ===========================================================================
# 8. 端到端场景：一键生成 → 订阅 → 调用 → 计量
# ===========================================================================
class TestEndToEnd:
    """端到端集成测试."""

    def test_sql_generate_subscribe_call(self, client):
        """测试 34: SQL 一键生成 → 订阅 → 调用 → 计量 全链路."""
        # 1. SQL 一键生成
        gen_resp = client.post(
            "/api/v1/apis/generate/sql",
            json={
                "name": "e2e-sql-api",
                "sql": "SELECT * FROM users WHERE user_id = :user_id",
                "datasource": "trino",
                "providerTenantId": "tenant-provider",
                "costStrategy": "by_call",
                "costUnitPrice": 0.02,
            },
        )
        assert gen_resp.status_code == 201
        api_id = gen_resp.json()["id"]

        # 2. 发布
        _publish_api(client, api_id)

        # 3. 订阅并审批
        sub_data = _subscribe_and_approve(client, api_id)
        ak = sub_data["accessKey"]

        # 4. 调用
        call_resp = client.post(
            f"/api/v1/apis/{api_id}/call",
            json={"payload": {"user_id": 123}},
            headers={"X-API-Key": ak},
        )
        assert call_resp.status_code == 200
        call_data = call_resp.json()
        assert call_data["statusCode"] == 200
        assert call_data["result"] is not None

        # 5. 查询计量
        metrics_resp = client.get(f"/api/v1/apis/{api_id}/metrics")
        assert metrics_resp.status_code == 200
        metrics = metrics_resp.json()
        assert metrics["callCount"] == 1
        assert metrics["successCount"] == 1

    def test_model_generate_subscribe_call(self, client):
        """测试 35: 模型一键生成 → 订阅 → 调用 全链路."""
        gen_resp = client.post(
            "/api/v1/apis/generate/model",
            json={
                "name": "e2e-model-api",
                "modelId": "gpt-4o",
                "modelType": "llm",
                "providerTenantId": "tenant-provider",
                "costStrategy": "by_call",
                "costUnitPrice": 0.10,
            },
        )
        assert gen_resp.status_code == 201
        api_id = gen_resp.json()["id"]
        _publish_api(client, api_id)
        sub_data = _subscribe_and_approve(client, api_id)

        call_resp = client.post(
            f"/api/v1/apis/{api_id}/call",
            json={"payload": {"messages": [{"role": "user", "content": "hello"}]}},
            headers={"X-API-Key": sub_data["accessKey"]},
        )
        assert call_resp.status_code == 200
        assert call_resp.json()["statusCode"] == 200

    def test_function_generate_subscribe_call(self, client):
        """测试 36: 函数一键生成 → 订阅 → 调用 全链路."""
        gen_resp = client.post(
            "/api/v1/apis/generate/function",
            json={
                "name": "e2e-function-api",
                "functionName": "process_handler",
                "runtime": "python",
                "providerTenantId": "tenant-provider",
                "costStrategy": "by_call",
                "costUnitPrice": 0.001,
            },
        )
        assert gen_resp.status_code == 201
        api_id = gen_resp.json()["id"]
        _publish_api(client, api_id)
        sub_data = _subscribe_and_approve(client, api_id)

        call_resp = client.post(
            f"/api/v1/apis/{api_id}/call",
            json={"payload": {"input": "data"}},
            headers={"X-API-Key": sub_data["accessKey"]},
        )
        assert call_resp.status_code == 200
        assert call_resp.json()["statusCode"] == 200