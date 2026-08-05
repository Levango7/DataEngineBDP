"""API 注册和管理测试."""
from __future__ import annotations

import pytest

from openapi_catalog.models import (
    APIFilter,
    APIStatus,
    APIUpdateRequest,
    CostStrategy,
    HttpMethod,
    SLALevel,
)
from openapi_catalog.repositories import (
    APIAlreadyExistsError,
    APINotFoundError,
    APIStatusTransitionError,
)


class TestAPIRegistry:
    """API 注册服务测试."""

    @pytest.mark.asyncio
    async def test_register_api(self, registry, make_api_def):
        """测试注册 API."""
        api = make_api_def(name="weather-query", path="/weather")
        result = await registry.apiRegistryService.register_api(api)

        assert result.id != ""
        assert result.name == "weather-query"
        assert result.status == APIStatus.DRAFT
        assert result.version == "1.0.0"

    @pytest.mark.asyncio
    async def test_register_api_duplicate(self, registry, make_api_def):
        """测试重复注册同名同版本 API."""
        api1 = make_api_def(name="dup-api", path="/dup")
        await registry.apiRegistryService.register_api(api1)

        api2 = make_api_def(name="dup-api", path="/dup")
        with pytest.raises(APIAlreadyExistsError):
            await registry.apiRegistryService.register_api(api2)

    @pytest.mark.asyncio
    async def test_get_api(self, registry, make_api_def):
        """测试获取 API 详情."""
        api = make_api_def(name="get-test")
        saved = await registry.apiRegistryService.register_api(api)

        got = await registry.apiRegistryService.get_api(saved.id)
        assert got.id == saved.id
        assert got.name == "get-test"

    @pytest.mark.asyncio
    async def test_get_api_not_found(self, registry):
        """测试获取不存在的 API."""
        with pytest.raises(APINotFoundError):
            await registry.apiRegistryService.get_api("non-existent-id")

    @pytest.mark.asyncio
    async def test_list_apis(self, registry, make_api_def):
        """测试列出 API."""
        # 注册多个 API
        for i in range(5):
            api = make_api_def(name=f"list-api-{i}", path=f"/list-{i}")
            await registry.apiRegistryService.register_api(api)

        # 列出全部
        apis = await registry.apiRegistryService.list_apis(APIFilter())
        assert len(apis) == 5

        # 按名称过滤
        apis = await registry.apiRegistryService.list_apis(
            APIFilter(name="list-api-1")
        )
        assert len(apis) == 1
        assert apis[0].name == "list-api-1"

    @pytest.mark.asyncio
    async def test_list_apis_by_category(self, registry, make_api_def):
        """测试按分类过滤."""
        api1 = make_api_def(name="cat-1")
        api1.category = "weather"
        api2 = make_api_def(name="cat-2", path="/c2")
        api2.category = "finance"

        await registry.apiRegistryService.register_api(api1)
        await registry.apiRegistryService.register_api(api2)

        apis = await registry.apiRegistryService.list_apis(
            APIFilter(category="weather")
        )
        assert len(apis) == 1
        assert apis[0].name == "cat-1"

    @pytest.mark.asyncio
    async def test_list_apis_keyword(self, registry, make_api_def):
        """测试关键词搜索."""
        api = make_api_def(name="weather-forecast")
        api.description = "天气预报查询 API"
        await registry.apiRegistryService.register_api(api)

        # 按名称搜索
        apis = await registry.apiRegistryService.list_apis(
            APIFilter(keyword="weather")
        )
        assert len(apis) == 1

        # 按描述搜索
        apis = await registry.apiRegistryService.list_apis(
            APIFilter(keyword="预报")
        )
        assert len(apis) == 1

    @pytest.mark.asyncio
    async def test_update_api(self, registry, make_api_def):
        """测试更新 API."""
        api = make_api_def(name="update-test")
        saved = await registry.apiRegistryService.register_api(api)

        update = APIUpdateRequest(
            description="更新后的描述",
            sla=SLALevel.PLATINUM,
            costUnitPrice=0.05,
        )
        updated = await registry.apiRegistryService.update_api(saved.id, update)

        assert updated.description == "更新后的描述"
        assert updated.sla == SLALevel.PLATINUM
        assert updated.costUnitPrice == 0.05

    @pytest.mark.asyncio
    async def test_delete_api(self, registry, make_api_def):
        """测试注销 API."""
        api = make_api_def(name="delete-test")
        saved = await registry.apiRegistryService.register_api(api)

        await registry.apiRegistryService.delete_api(saved.id)

        with pytest.raises(APINotFoundError):
            await registry.apiRegistryService.get_api(saved.id)

    @pytest.mark.asyncio
    async def test_delete_running_api_fails(self, registry, make_api_def):
        """测试删除运行中的 API 应失败."""
        api = make_api_def(name="delete-running")
        saved = await registry.apiRegistryService.register_api(api)
        # 走完审核流程再发布
        await registry.apiRegistryService.submit_for_review(saved.id)
        await registry.apiRegistryService.approve(saved.id)
        await registry.apiRegistryService.publish(saved.id)

        with pytest.raises(APIStatusTransitionError):
            await registry.apiRegistryService.delete_api(saved.id)

    @pytest.mark.asyncio
    async def test_status_transition_publish(self, registry, make_api_def):
        """测试发布流程：DRAFT → REVIEWING → APPROVED → RUNNING."""
        api = make_api_def(name="publish-flow")
        saved = await registry.apiRegistryService.register_api(api)
        assert saved.status == APIStatus.DRAFT

        # 提交审核
        reviewed = await registry.apiRegistryService.submit_for_review(saved.id)
        assert reviewed.status == APIStatus.REVIEWING

        # 审核通过
        approved = await registry.apiRegistryService.approve(saved.id)
        assert approved.status == APIStatus.APPROVED

        # 发布
        published = await registry.apiRegistryService.publish(saved.id)
        assert published.status == APIStatus.RUNNING

    @pytest.mark.asyncio
    async def test_status_transition_reject(self, registry, make_api_def):
        """测试审核驳回：DRAFT → REVIEWING → REJECTED."""
        api = make_api_def(name="reject-flow")
        saved = await registry.apiRegistryService.register_api(api)

        await registry.apiRegistryService.submit_for_review(saved.id)
        rejected = await registry.apiRegistryService.reject(saved.id)
        assert rejected.status == APIStatus.REJECTED

    @pytest.mark.asyncio
    async def test_status_transition_deprecate(self, registry, make_api_def):
        """测试废弃流程：RUNNING → DEPRECATED → ARCHIVED."""
        api = make_api_def(name="deprecate-flow")
        saved = await registry.apiRegistryService.register_api(api)
        await registry.apiRegistryService.submit_for_review(saved.id)
        await registry.apiRegistryService.approve(saved.id)
        await registry.apiRegistryService.publish(saved.id)

        deprecated = await registry.apiRegistryService.deprecate(saved.id)
        assert deprecated.status == APIStatus.DEPRECATED

        archived = await registry.apiRegistryService.archive(saved.id)
        assert archived.status == APIStatus.ARCHIVED

    @pytest.mark.asyncio
    async def test_invalid_status_transition(self, registry, make_api_def):
        """测试非法状态转换."""
        api = make_api_def(name="invalid-transition")
        saved = await registry.apiRegistryService.register_api(api)

        # DRAFT 不能直接到 RUNNING
        with pytest.raises(APIStatusTransitionError):
            await registry.apiRegistryService.publish(saved.id)


class TestAPIRegistryHTTP:
    """API 注册 HTTP 端点测试."""

    def test_register_api_http(self, client):
        """测试通过 HTTP 注册 API."""
        response = client.post(
            "/api/v1/apis",
            json={
                "name": "http-test-api",
                "version": "1.0.0",
                "description": "HTTP 测试 API",
                "category": "test",
                "tags": ["test", "http"],
                "method": "GET",
                "path": "/http-test",
                "params": [
                    {
                        "name": "q",
                        "location": "query",
                        "type": "string",
                        "required": True,
                        "description": "查询关键词",
                    }
                ],
                "responses": [
                    {
                        "statusCode": 200,
                        "description": "成功",
                    }
                ],
                "authType": "api_key",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080/v1/statement",
                    "method": "GET",
                    "timeout": 30000,
                    "retries": 2,
                },
                "sla": "gold",
                "costStrategy": "by_call",
                "costUnitPrice": 0.01,
                "providerTenantId": "tenant-1",
            },
        )
        assert response.status_code == 201
        data = response.json()
        assert data["name"] == "http-test-api"
        assert data["status"] == "draft"
        assert data["id"] != ""

    def test_list_apis_http(self, client):
        """测试通过 HTTP 列出 API."""
        # 先注册
        client.post(
            "/api/v1/apis",
            json={
                "name": "list-http-api",
                "version": "1.0.0",
                "method": "GET",
                "path": "/list-http",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )

        response = client.get("/api/v1/apis")
        assert response.status_code == 200
        data = response.json()
        assert len(data) >= 1

    def test_get_api_http(self, client):
        """测试通过 HTTP 获取 API 详情."""
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "get-http-api",
                "version": "1.0.0",
                "method": "GET",
                "path": "/get-http",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )
        api_id = create_resp.json()["id"]

        response = client.get(f"/api/v1/apis/{api_id}")
        assert response.status_code == 200
        assert response.json()["id"] == api_id

    def test_get_api_not_found_http(self, client):
        """测试获取不存在的 API 返回 404."""
        response = client.get("/api/v1/apis/non-existent")
        assert response.status_code == 404

    def test_delete_api_http(self, client):
        """测试通过 HTTP 注销 API."""
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "delete-http-api",
                "version": "1.0.0",
                "method": "GET",
                "path": "/delete-http",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )
        api_id = create_resp.json()["id"]

        response = client.delete(f"/api/v1/apis/{api_id}")
        assert response.status_code == 204

    def test_publish_flow_http(self, client):
        """测试通过 HTTP 完成发布流程."""
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "publish-http-api",
                "version": "1.0.0",
                "method": "GET",
                "path": "/publish-http",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )
        api_id = create_resp.json()["id"]

        # 提交审核
        r = client.post(f"/api/v1/apis/{api_id}/submit-review")
        assert r.status_code == 200
        assert r.json()["status"] == "reviewing"

        # 审核通过
        r = client.post(f"/api/v1/apis/{api_id}/approve")
        assert r.status_code == 200
        assert r.json()["status"] == "approved"

        # 发布
        r = client.post(f"/api/v1/apis/{api_id}/publish")
        assert r.status_code == 200
        assert r.json()["status"] == "running"

    def test_health_check(self, client):
        """测试健康检查."""
        response = client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert data["module"] == "open-api-catalog"