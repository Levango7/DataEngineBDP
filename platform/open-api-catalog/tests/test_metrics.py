"""计量测试."""

from __future__ import annotations

from openapi_catalog.models import (
    CostStrategy,
)
import pytest


class TestMetering:
    """计量服务测试."""

    @pytest.mark.asyncio
    async def test_record_call(self, registry, make_api_def, publish_api):
        """测试记录调用计量."""
        api = make_api_def(name="metric-record")
        saved_api = await registry.apiRegistryService.register_api(api)
        await publish_api(registry, saved_api.id)

        # 记录一次调用
        metric = await registry.meteringService.record_call(
            api=saved_api,
            subscription_id="sub-1",
            consumer_tenant_id="tenant-consumer",
            latency_ms=150.5,
            request_bytes=200,
            response_bytes=1024,
            status_code=200,
        )

        assert metric.callId != ""
        assert metric.apiId == saved_api.id
        assert metric.latencyMs == 150.5
        assert metric.costAmount == 0.01  # by_call 单价

    @pytest.mark.asyncio
    async def test_record_error_call(self, registry, make_api_def, publish_api):
        """测试记录错误调用."""
        api = make_api_def(name="metric-error")
        saved_api = await registry.apiRegistryService.register_api(api)
        await publish_api(registry, saved_api.id)

        metric = await registry.meteringService.record_call(
            api=saved_api,
            subscription_id="sub-1",
            consumer_tenant_id="tenant-consumer",
            latency_ms=50.0,
            request_bytes=100,
            response_bytes=0,
            status_code=500,
            error_message="内部错误",
        )

        assert metric.statusCode == 500
        assert metric.errorMessage == "内部错误"

    @pytest.mark.asyncio
    async def test_get_metrics_empty(self, registry, make_api_def, publish_api):
        """测试获取空计量."""
        api = make_api_def(name="metric-empty")
        saved_api = await registry.apiRegistryService.register_api(api)

        metrics = await registry.meteringService.get_metrics(saved_api.id)
        assert metrics.callCount == 0
        assert metrics.errorRate == 0.0

    @pytest.mark.asyncio
    async def test_get_metrics_aggregated(self, registry, make_api_def, publish_api):
        """测试聚合计量."""
        api = make_api_def(name="metric-agg")
        saved_api = await registry.apiRegistryService.register_api(api)
        await publish_api(registry, saved_api.id)

        # 记录 10 次调用，2 次错误
        for i in range(8):
            await registry.meteringService.record_call(
                api=saved_api,
                subscription_id="sub-1",
                consumer_tenant_id="tenant-consumer",
                latency_ms=100.0 + i,
                request_bytes=100,
                response_bytes=500,
                status_code=200,
            )
        for i in range(2):
            await registry.meteringService.record_call(
                api=saved_api,
                subscription_id="sub-1",
                consumer_tenant_id="tenant-consumer",
                latency_ms=200.0,
                request_bytes=100,
                response_bytes=0,
                status_code=500,
            )

        metrics = await registry.meteringService.get_metrics(saved_api.id)
        assert metrics.callCount == 10
        assert metrics.successCount == 8
        assert metrics.errorCount == 2
        assert metrics.errorRate == pytest.approx(0.2)
        assert metrics.successRate == pytest.approx(0.8)
        assert metrics.avgLatencyMs > 0
        assert metrics.p99LatencyMs > 0
        assert metrics.totalTrafficBytes > 0
        assert metrics.totalCost > 0

    @pytest.mark.asyncio
    async def test_get_metrics_by_consumer(self, registry, make_api_def, publish_api):
        """测试按消费者聚合."""
        api = make_api_def(name="metric-by-consumer")
        saved_api = await registry.apiRegistryService.register_api(api)
        await publish_api(registry, saved_api.id)

        # 两个消费者
        for _ in range(5):
            await registry.meteringService.record_call(
                api=saved_api,
                subscription_id="sub-a",
                consumer_tenant_id="tenant-a",
                latency_ms=100.0,
                request_bytes=100,
                response_bytes=500,
                status_code=200,
            )
        for _ in range(3):
            await registry.meteringService.record_call(
                api=saved_api,
                subscription_id="sub-b",
                consumer_tenant_id="tenant-b",
                latency_ms=200.0,
                request_bytes=100,
                response_bytes=500,
                status_code=200,
            )

        metrics = await registry.meteringService.get_metrics(saved_api.id)
        assert metrics.callCount == 8
        assert len(metrics.byConsumer) == 2

        consumer_map = {c.consumerTenantId: c for c in metrics.byConsumer}
        assert consumer_map["tenant-a"].callCount == 5
        assert consumer_map["tenant-b"].callCount == 3

    @pytest.mark.asyncio
    async def test_cost_by_call(self, registry, make_api_def, publish_api):
        """测试按次计费."""
        api = make_api_def(name="cost-by-call")
        api.costStrategy = CostStrategy.BY_CALL
        api.costUnitPrice = 0.05
        saved_api = await registry.apiRegistryService.register_api(api)
        await publish_api(registry, saved_api.id)

        metric = await registry.meteringService.record_call(
            api=saved_api,
            subscription_id="sub-1",
            consumer_tenant_id="tenant-c",
            latency_ms=100.0,
            request_bytes=100,
            response_bytes=500,
            status_code=200,
        )
        assert metric.costAmount == 0.05

    @pytest.mark.asyncio
    async def test_cost_by_bytes(self, registry, make_api_def, publish_api):
        """测试按量计费."""
        api = make_api_def(name="cost-by-bytes")
        api.costStrategy = CostStrategy.BY_BYTES
        api.costUnitPrice = 0.001  # 元/KB
        saved_api = await registry.apiRegistryService.register_api(api)
        await publish_api(registry, saved_api.id)

        metric = await registry.meteringService.record_call(
            api=saved_api,
            subscription_id="sub-1",
            consumer_tenant_id="tenant-c",
            latency_ms=100.0,
            request_bytes=1024,  # 1KB
            response_bytes=2048,  # 2KB
            status_code=200,
        )
        # 总 3KB * 0.001 = 0.003
        assert metric.costAmount == pytest.approx(0.003)

    @pytest.mark.asyncio
    async def test_cost_monthly_package(self, registry, make_api_def, publish_api):
        """测试月包计费（配额内 0 元）."""
        api = make_api_def(name="cost-monthly")
        api.costStrategy = CostStrategy.MONTHLY_PACKAGE
        api.costUnitPrice = 100.0  # 月费
        api.monthlyQuota = 10000
        saved_api = await registry.apiRegistryService.register_api(api)
        await publish_api(registry, saved_api.id)

        metric = await registry.meteringService.record_call(
            api=saved_api,
            subscription_id="sub-1",
            consumer_tenant_id="tenant-c",
            latency_ms=100.0,
            request_bytes=100,
            response_bytes=500,
            status_code=200,
        )
        # 月包配额内不计单次费用
        assert metric.costAmount == 0.0

    @pytest.mark.asyncio
    async def test_metrics_timeseries(self, registry, make_api_def, publish_api):
        """测试时间序列."""
        api = make_api_def(name="metric-ts")
        saved_api = await registry.apiRegistryService.register_api(api)
        await publish_api(registry, saved_api.id)

        for i in range(5):
            await registry.meteringService.record_call(
                api=saved_api,
                subscription_id="sub-1",
                consumer_tenant_id="tenant-c",
                latency_ms=100.0 + i * 10,
                request_bytes=100,
                response_bytes=500,
                status_code=200,
            )

        metrics = await registry.meteringService.get_metrics(saved_api.id, range_str="1h")
        assert len(metrics.timeseries) >= 1
        total = sum(p.callCount for p in metrics.timeseries)
        assert total == 5


class TestMetricsHTTP:
    """计量 HTTP 端点测试."""

    def test_get_metrics_http(self, client):
        """测试通过 HTTP 获取计量."""
        # 注册并发布 API
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "metric-http-api",
                "version": "1.0.0",
                "method": "GET",
                "path": "/metric-http",
                "upstream": {
                    "type": "trino",
                    "url": "http://trino:8080",
                    "method": "GET",
                },
                "providerTenantId": "tenant-1",
            },
        )
        api_id = create_resp.json()["id"]
        client.post(f"/api/v1/apis/{api_id}/submit-review")
        client.post(f"/api/v1/apis/{api_id}/approve")
        client.post(f"/api/v1/apis/{api_id}/publish")

        # 调用几次（先订阅）
        sub_resp = client.post(
            f"/api/v1/apis/{api_id}/subscribe",
            json={
                "subscriberId": "sub-metric",
                "subscriberTenantId": "tenant-c",
                "purpose": "测试",
                "quotaExpect": 100,
            },
        )
        sub_id = sub_resp.json()["id"]
        approve_resp = client.post(
            f"/api/v1/subscriptions/{sub_id}/approve",
            json={"approve": True, "grantedQuota": 100, "approver": "admin"},
        )
        ak = approve_resp.json()["accessKey"]
        sk = approve_resp.json()["secretKey"]

        for _ in range(3):
            client.post(
                f"/api/v1/apis/{api_id}/call",
                json={"payload": {}},
                headers={"X-API-Key": ak, "X-API-Secret": sk},
            )

        # 获取计量
        response = client.get(f"/api/v1/apis/{api_id}/metrics")
        assert response.status_code == 200
        data = response.json()
        assert data["callCount"] == 3
        assert data["successCount"] == 3
