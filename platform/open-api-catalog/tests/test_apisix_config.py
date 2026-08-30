"""APISIX 配置生成测试."""

from __future__ import annotations

from openapi_catalog.models import (
    AuthType,
    HttpMethod,
    SLALevel,
)
import pytest


class TestAPISIXConfig:
    """APISIX 配置生成服务测试."""

    @pytest.mark.asyncio
    async def test_generate_route_basic(self, registry, make_api_def):
        """测试生成基础路由配置."""
        api = make_api_def(name="apisix-basic", path="/test/basic")
        saved_api = await registry.apiRegistryService.register_api(api)

        route = await registry.apisixConfigService.generate_route(saved_api.id)

        assert route.id == saved_api.id
        assert route.uri == "/test/basic"
        assert route.methods == [HttpMethod.GET]
        assert route.upstream.type == "roundrobin"
        assert "http://trino:8080/v1/statement" in route.upstream.nodes

    @pytest.mark.asyncio
    async def test_generate_route_plugins(self, registry, make_api_def):
        """测试路由插件链."""
        api = make_api_def(name="apisix-plugins")
        saved_api = await registry.apiRegistryService.register_api(api)

        route = await registry.apisixConfigService.generate_route(saved_api.id)

        # 应包含认证插件
        assert "key-auth" in route.plugins
        # 应包含限流插件
        assert "limit-req" in route.plugins
        assert route.plugins["limit-req"]["rejected_code"] == 429
        # 应包含熔断插件
        assert "circuit-breaker" in route.plugins
        # 应包含计量插件
        assert "prometheus" in route.plugins
        # 应包含日志插件
        assert "http-logger" in route.plugins
        # 应包含重写插件（租户隔离）
        assert "proxy-rewrite" in route.plugins
        assert "X-Provider-Tenant" in route.plugins["proxy-rewrite"]["headers"]["set"]

    @pytest.mark.asyncio
    async def test_generate_route_jwt_auth(self, registry, make_api_def):
        """测试 JWT 认证路由."""
        api = make_api_def(name="apisix-jwt")
        api.authType = AuthType.JWT
        saved_api = await registry.apiRegistryService.register_api(api)

        route = await registry.apisixConfigService.generate_route(saved_api.id)

        assert "jwt-auth" in route.plugins
        assert "key-auth" not in route.plugins

    @pytest.mark.asyncio
    async def test_generate_route_oauth2(self, registry, make_api_def):
        """测试 OAuth2 认证路由."""
        api = make_api_def(name="apisix-oauth2")
        api.authType = AuthType.OAUTH2
        saved_api = await registry.apiRegistryService.register_api(api)

        route = await registry.apisixConfigService.generate_route(saved_api.id)

        assert "oauth2" in route.plugins

    @pytest.mark.asyncio
    async def test_generate_route_labels(self, registry, make_api_def):
        """测试路由标签."""
        api = make_api_def(name="apisix-labels")
        api.sla = SLALevel.PLATINUM
        saved_api = await registry.apiRegistryService.register_api(api)

        route = await registry.apisixConfigService.generate_route(saved_api.id)

        assert route.labels["api_id"] == saved_api.id
        assert route.labels["api_name"] == "apisix-labels"
        assert route.labels["version"] == "1.0.0"
        assert route.labels["provider_tenant"] == "tenant-provider"
        assert route.labels["sla"] == "platinum"

    @pytest.mark.asyncio
    async def test_route_priority(self, registry, make_api_def):
        """测试路由优先级（SLA 越高优先级越高）."""
        # 铂金 API
        api_platinum = make_api_def(name="priority-platinum", path="/p-platinum")
        api_platinum.sla = SLALevel.PLATINUM
        saved_platinum = await registry.apiRegistryService.register_api(api_platinum)

        # 银 API
        api_silver = make_api_def(name="priority-silver", path="/p-silver")
        api_silver.sla = SLALevel.SILVER
        saved_silver = await registry.apiRegistryService.register_api(api_silver)

        route_platinum = await registry.apisixConfigService.generate_route(saved_platinum.id)
        route_silver = await registry.apisixConfigService.generate_route(saved_silver.id)

        assert route_platinum.priority > route_silver.priority

    @pytest.mark.asyncio
    async def test_route_priority_version(self, registry, make_api_def):
        """测试版本号影响优先级."""
        api_v1 = make_api_def(name="priority-v1", version="1.0.0", path="/v1")
        saved_v1 = await registry.apiRegistryService.register_api(api_v1)

        api_v2 = make_api_def(name="priority-v2", version="2.0.0", path="/v2")
        saved_v2 = await registry.apiRegistryService.register_api(api_v2)

        route_v1 = await registry.apisixConfigService.generate_route(saved_v1.id)
        route_v2 = await registry.apisixConfigService.generate_route(saved_v2.id)

        assert route_v2.priority > route_v1.priority

    @pytest.mark.asyncio
    async def test_to_apisix_payload(self, registry, make_api_def):
        """测试转换为 APISIX 提交格式."""
        api = make_api_def(name="apisix-payload")
        saved_api = await registry.apiRegistryService.register_api(api)

        route = await registry.apisixConfigService.generate_route(saved_api.id)
        payload = route.to_apisix_payload()

        assert payload["uri"] == "/test"
        assert payload["methods"] == ["GET"]
        assert "upstream" in payload
        assert "plugins" in payload
        assert "labels" in payload
        assert isinstance(payload["upstream"]["nodes"], dict)

    @pytest.mark.asyncio
    async def test_generate_consumer(self, registry, make_api_def):
        """测试生成 APISIX Consumer."""
        api = make_api_def(name="apisix-consumer")
        saved_api = await registry.apiRegistryService.register_api(api)

        # 订阅并审批
        from openapi_catalog.models import APISubscription, ApproveRequest

        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-consumer",
            subscriberTenantId="tenant-c",
            providerTenantId="tenant-provider",
            purpose="测试",
            quotaExpect=100,
        )
        saved_sub = await registry.subscriptionService.apply_subscription(saved_api.id, sub)
        approved = await registry.subscriptionService.approve_subscription(
            saved_sub.id,
            ApproveRequest(approve=True, approver="admin", grantedQuota=100),
        )

        consumer = await registry.apisixConfigService.generate_consumer(approved.id)

        assert consumer.username == f"sub-{approved.id}"
        assert "key-auth" in consumer.plugins
        assert consumer.plugins["key-auth"]["key"] == approved.accessKey
        assert "limit-req" in consumer.plugins

    @pytest.mark.asyncio
    async def test_deploy_route(self, registry, make_api_def, monkeypatch):
        """测试部署路由（mock APISIX 200 响应，校验真实 HTTP 调用路径）."""
        api = make_api_def(name="apisix-deploy")
        saved_api = await registry.apiRegistryService.register_api(api)

        captured: dict = {}

        class _Resp:
            status_code = 201
            text = '{"code":0}'

        class _Client:
            def __init__(self, *a, **kw):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

            async def put(self, url, json=None, headers=None):
                captured["url"] = url
                captured["json"] = json
                captured["headers"] = headers
                return _Resp()

        import httpx

        monkeypatch.setattr(httpx, "AsyncClient", _Client)

        result = await registry.apisixConfigService.deploy_route(saved_api.id)

        assert result["action"] == "deploy"
        assert result["routeId"] == saved_api.id
        assert result["status"] == "success"
        assert result["deployed"] is True
        assert "payload" in result
        # 校验真实调用形态：PUT {admin_url}/routes/{id} + X-API-Key
        assert captured["url"].endswith(f"/routes/{saved_api.id}")
        assert "X-API-Key" in captured["headers"]

    @pytest.mark.asyncio
    async def test_deploy_route_unreachable_reports_failure(self, registry, make_api_def, monkeypatch):
        """APISIX 不可达时明确 failed（不再伪造 success）——修复前的漏洞本体回归用例."""
        api = make_api_def(name="apisix-deploy-down")
        saved_api = await registry.apiRegistryService.register_api(api)

        import httpx

        class _Boom:
            def __init__(self, *a, **kw):
                raise ConnectionError("connection refused")

        monkeypatch.setattr(httpx, "AsyncClient", _Boom)

        result = await registry.apisixConfigService.deploy_route(saved_api.id)

        assert result["status"] == "failed"
        assert result["deployed"] is False
        assert "不可达" in result["error"]

    @pytest.mark.asyncio
    async def test_undeploy_route(self, registry, make_api_def, monkeypatch):
        """测试删除路由（mock APISIX 204）."""
        api = make_api_def(name="apisix-undeploy")
        saved_api = await registry.apiRegistryService.register_api(api)

        captured: dict = {}

        class _Resp:
            status_code = 204
            text = ""

        class _Client:
            def __init__(self, *a, **kw):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

            async def delete(self, url, headers=None):
                captured["url"] = url
                captured["headers"] = headers
                return _Resp()

        import httpx

        monkeypatch.setattr(httpx, "AsyncClient", _Client)

        result = await registry.apisixConfigService.undeploy_route(saved_api.id)

        assert result["action"] == "undeploy"
        assert result["routeId"] == saved_api.id
        assert result["status"] == "success"
        assert captured["url"].endswith(f"/routes/{saved_api.id}")

    @pytest.mark.asyncio
    async def test_undeploy_route_unreachable_reports_failure(self, registry, make_api_def, monkeypatch):
        """删除路由在 APISIX 不可达时明确 failed."""
        api = make_api_def(name="apisix-undeploy-down")
        saved_api = await registry.apiRegistryService.register_api(api)

        import httpx

        class _Boom:
            def __init__(self, *a, **kw):
                raise ConnectionError("connection refused")

        monkeypatch.setattr(httpx, "AsyncClient", _Boom)

        result = await registry.apisixConfigService.undeploy_route(saved_api.id)

        assert result["status"] == "failed"
        assert result["deployed"] is False

    @pytest.mark.asyncio
    async def test_upstream_timeout(self, registry, make_api_def):
        """测试 upstream 超时配置."""
        api = make_api_def(name="apisix-timeout")
        api.upstream.timeout = 60000  # 60s
        saved_api = await registry.apiRegistryService.register_api(api)

        route = await registry.apisixConfigService.generate_route(saved_api.id)

        assert route.upstream.timeout["read"] >= 60

    @pytest.mark.asyncio
    async def test_platinum_sla_response_rewrite(self, registry, make_api_def):
        """测试铂金 SLA 添加响应重写插件."""
        api = make_api_def(name="apisix-platinum")
        api.sla = SLALevel.PLATINUM
        saved_api = await registry.apiRegistryService.register_api(api)

        route = await registry.apisixConfigService.generate_route(saved_api.id)

        assert "response-rewrite" in route.plugins
        assert route.plugins["response-rewrite"]["headers"]["add"]["X-SLA"] == "platinum"


class TestAPISIXConfigHTTP:
    """APISIX 配置 HTTP 端点测试."""

    def test_get_apisix_config_http(self, client):
        """测试通过 HTTP 获取 APISIX 配置."""
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "apisix-http-config",
                "version": "1.0.0",
                "method": "GET",
                "path": "/apisix-http",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )
        api_id = create_resp.json()["id"]

        response = client.get(f"/api/v1/apis/{api_id}/apisix-config")
        assert response.status_code == 200
        data = response.json()
        assert data["uri"] == "/apisix-http"
        assert "plugins" in data
        assert "key-auth" in data["plugins"]

    def test_deploy_apisix_route_http(self, client, monkeypatch):
        """测试通过 HTTP 部署 APISIX 路由（mock APISIX 201；此前假实现无 APISIX 也返回 success）."""
        import httpx

        class _Resp:
            status_code = 201
            text = '{"code":0}'

        class _Client:
            def __init__(self, *a, **kw):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

            async def put(self, url, json=None, headers=None):
                return _Resp()

        monkeypatch.setattr(httpx, "AsyncClient", _Client)

        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "apisix-http-deploy",
                "version": "1.0.0",
                "method": "GET",
                "path": "/apisix-deploy",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )
        api_id = create_resp.json()["id"]

        response = client.post(f"/api/v1/apis/{api_id}/apisix-deploy")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "success"
        assert data["deployed"] is True


class TestDocGenerator:
    """API 文档生成测试."""

    @pytest.mark.asyncio
    async def test_generate_openapi_spec(self, registry, make_api_def):
        """测试生成 OpenAPI 3.0 Spec."""
        api = make_api_def(name="doc-openapi")
        saved_api = await registry.apiRegistryService.register_api(api)

        spec = await registry.docGeneratorService.generate_openapi_spec(saved_api.id)

        assert spec["openapi"] == "3.0.3"
        assert spec["info"]["title"] == "doc-openapi"
        assert spec["info"]["version"] == "1.0.0"
        assert "/test" in spec["paths"]
        assert "get" in spec["paths"]["/test"]
        assert "parameters" in spec["paths"]["/test"]["get"]
        assert "responses" in spec["paths"]["/test"]["get"]

    @pytest.mark.asyncio
    async def test_openapi_security_schemes(self, registry, make_api_def):
        """测试 OpenAPI 安全方案."""
        api = make_api_def(name="doc-security")
        saved_api = await registry.apiRegistryService.register_api(api)

        spec = await registry.docGeneratorService.generate_openapi_spec(saved_api.id)

        assert "securitySchemes" in spec["components"]
        assert "ApiKeyAuth" in spec["components"]["securitySchemes"]
        assert spec["components"]["securitySchemes"]["ApiKeyAuth"]["type"] == "apiKey"

    @pytest.mark.asyncio
    async def test_openapi_jwt_security(self, registry, make_api_def):
        """测试 JWT 安全方案."""
        api = make_api_def(name="doc-jwt")
        api.authType = AuthType.JWT
        saved_api = await registry.apiRegistryService.register_api(api)

        spec = await registry.docGeneratorService.generate_openapi_spec(saved_api.id)

        assert "BearerAuth" in spec["components"]["securitySchemes"]
        assert spec["components"]["securitySchemes"]["BearerAuth"]["scheme"] == "bearer"

    @pytest.mark.asyncio
    async def test_openapi_parameters(self, registry, make_api_def):
        """测试 OpenAPI 参数生成."""
        api = make_api_def(name="doc-params")
        saved_api = await registry.apiRegistryService.register_api(api)

        spec = await registry.docGeneratorService.generate_openapi_spec(saved_api.id)

        params = spec["paths"]["/test"]["get"]["parameters"]
        assert len(params) >= 1
        # 应包含 limit 参数
        limit_param = next(p for p in params if p["name"] == "limit")
        assert limit_param["in"] == "query"
        assert limit_param["schema"]["type"] == "integer"

    @pytest.mark.asyncio
    async def test_openapi_responses(self, registry, make_api_def):
        """测试 OpenAPI 响应生成."""
        api = make_api_def(name="doc-responses")
        saved_api = await registry.apiRegistryService.register_api(api)

        spec = await registry.docGeneratorService.generate_openapi_spec(saved_api.id)

        responses = spec["paths"]["/test"]["get"]["responses"]
        assert "200" in responses
        assert responses["200"]["description"] == "成功"

    @pytest.mark.asyncio
    async def test_openapi_metadata(self, registry, make_api_def):
        """测试 OpenAPI 元数据扩展."""
        api = make_api_def(name="doc-metadata")
        saved_api = await registry.apiRegistryService.register_api(api)

        spec = await registry.docGeneratorService.generate_openapi_spec(saved_api.id)

        assert "x-api-metadata" in spec
        assert spec["x-api-metadata"]["apiId"] == saved_api.id
        assert spec["x-api-metadata"]["sla"] == "gold"
        assert spec["x-api-metadata"]["costStrategy"] == "by_call"

    @pytest.mark.asyncio
    async def test_generate_markdown_doc(self, registry, make_api_def):
        """测试生成 Markdown 文档."""
        api = make_api_def(name="doc-markdown")
        saved_api = await registry.apiRegistryService.register_api(api)

        md = await registry.docGeneratorService.generate_markdown_doc(saved_api.id)

        assert "# doc-markdown" in md
        assert "## 描述" in md
        assert "## 调用方式" in md
        assert "## 参数" in md
        assert "## 响应" in md
        assert "## 计费" in md


class TestDocHTTP:
    """文档 HTTP 端点测试."""

    def test_get_docs_http(self, client):
        """测试通过 HTTP 获取 API 文档."""
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "doc-http-api",
                "version": "1.0.0",
                "method": "GET",
                "path": "/doc-http",
                "params": [
                    {
                        "name": "q",
                        "location": "query",
                        "type": "string",
                        "required": True,
                    }
                ],
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )
        api_id = create_resp.json()["id"]

        response = client.get(f"/api/v1/apis/{api_id}/docs")
        assert response.status_code == 200
        data = response.json()
        assert data["openapi"] == "3.0.3"
        assert data["info"]["title"] == "doc-http-api"

    def test_get_docs_markdown_http(self, client):
        """测试通过 HTTP 获取 Markdown 文档."""
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "doc-md-http",
                "version": "1.0.0",
                "method": "GET",
                "path": "/doc-md",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )
        api_id = create_resp.json()["id"]

        response = client.get(f"/api/v1/apis/{api_id}/docs?format=markdown")
        assert response.status_code == 200
        # Markdown 返回字符串
        assert "# doc-md-http" in response.text
