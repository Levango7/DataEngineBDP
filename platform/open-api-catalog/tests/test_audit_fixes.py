"""审计遗留 bug 回归测试：并发计量、SQL 黑名单、aware 时间戳、级联删除事务、range 校验."""

from __future__ import annotations

import asyncio
from datetime import datetime
import sqlite3
import types
import uuid

import pytest

from openapi_catalog.models import (
    APISubscription,
    ApproveRequest,
    CallMetric,
    CostStrategy,
    SubscriptionFilter,
)
from openapi_catalog.models.base import utc_now
from openapi_catalog.repositories import APINotFoundError, ValidationError
from openapi_catalog.repositories.sqlite import SQLiteCatalogStore, SQLiteConnection
from openapi_catalog.services.api_generator import APIGeneratorService, SqlGenerateRequest


def build_sqlite_store(tmp_path) -> SQLiteCatalogStore:
    conn = SQLiteConnection(str(tmp_path / "audit_regression.db"))
    conn.init_schema()
    return SQLiteCatalogStore(conn)


def make_sub(api_id: str, sub_id: str = "sub-audit") -> APISubscription:
    return APISubscription(
        id=sub_id,
        apiId=api_id,
        subscriberId="consumer-a",
        subscriberTenantId="tenant-consumer",
        providerTenantId="tenant-provider",
        purpose="audit-regression",
        quotaExpect=100000,
    )


def make_metric(api_id: str, sub_id: str, i: int) -> CallMetric:
    return CallMetric(
        callId=str(uuid.uuid4()),
        apiId=api_id,
        apiVersion="1.0.0",
        subscriptionId=sub_id,
        consumerTenantId="tenant-consumer",
        providerTenantId="tenant-provider",
        latencyMs=10.0 + i,
        requestBytes=100,
        responseBytes=50,
        statusCode=500 if i % 5 == 0 else 200,
        costStrategy=CostStrategy.BY_CALL,
        costAmount=0.01,
    )


def assert_aware(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    assert parsed.tzinfo is not None
    return parsed


class TestConcurrentSaveMetric:
    """bug1：并发 save_metric 聚合更新不丢失."""

    async def test_concurrent_save_metric_exact_counts(self, tmp_path, make_api_def):
        store = build_sqlite_store(tmp_path)
        api = await store.save_api(make_api_def(name="concurrent-metric-api"))
        sub = make_sub(api.id)
        await store.save_subscription(sub)

        n = 60
        metrics = [make_metric(api.id, sub.id, i) for i in range(n)]
        await asyncio.gather(*(store.save_metric(m) for m in metrics))

        expected_errors = sum(1 for m in metrics if m.statusCode >= 400)
        got_api = await store.get_api(api.id)
        assert got_api.callCount == n
        assert got_api.errorCount == expected_errors
        assert got_api.totalLatencyMs == pytest.approx(sum(m.latencyMs for m in metrics))
        assert got_api.totalTrafficBytes == sum(m.requestBytes + m.responseBytes for m in metrics)

        got_sub = await store.get_subscription(sub.id)
        assert got_sub.callCount == n
        assert got_sub.errorCount == expected_errors
        assert got_sub.lastCalledAt is not None

    async def test_sqlite_connection_busy_timeout(self, tmp_path):
        conn = SQLiteConnection(str(tmp_path / "pragma.db"))
        conn.init_schema()
        row = conn.conn.execute("PRAGMA busy_timeout;").fetchone()
        assert row[0] > 0


class TestSqlBlacklist:
    """bug2：黑名单子串绕过被拒，合法 SELECT 含关键词放行."""

    @pytest.mark.parametrize(
        "sql",
        [
            "DROP TABLE users",
            "\tDROP\n TABLE users",
            "DELETE/*c*/FROM users",
            "SELECT 1; DROP TABLE users",
            "select 1 -- ok\n; delete from t",
            "insert into t values (1)",
            "-- comment\nUPDATE t SET x = 1",
            "/* leading */ TRUNCATE TABLE t",
            "select 1;select 2",
        ],
    )
    async def test_bypass_payloads_rejected(self, sql, registry):
        generator = APIGeneratorService(registry.store, registry.apiRegistryService)
        req = SqlGenerateRequest(
            name="evil-api",
            sql=sql,
            datasource="trino",
            providerTenantId="tenant-provider",
        )
        with pytest.raises(ValidationError):
            await generator.generate_from_sql(req)

    async def test_legitimate_selects_with_keywords_pass(self, registry):
        generator = APIGeneratorService(registry.store, registry.apiRegistryService)
        payloads = [
            "SELECT id FROM users WHERE remark = 'drop table users'",
            "select count(*) from logs where msg like '%delete %'",
            "  select\n id, name\tfrom orders where note = 'update it' ",
            "SELECT 'create' AS action FROM events",
        ]
        for idx, sql in enumerate(payloads):
            req = SqlGenerateRequest(
                name=f"legal-api-{idx}",
                sql=sql,
                datasource="trino",
                providerTenantId="tenant-provider",
            )
            api = await generator.generate_from_sql(req)
            assert api.name == f"legal-api-{idx}"
            assert any("sql-generated" in tag for tag in api.tags)


class TestAwareTimestamps:
    """bug3：服务层时间戳统一为带 tzinfo 的 utc_now."""

    async def test_subscription_service_timestamps_aware(self, registry, make_api_def):
        api = await registry.apiRegistryService.register_api(make_api_def(name="tz-sub-api"))
        sub = await registry.subscriptionService.apply_subscription(api.id, make_sub("tz-sub-1"))
        approved = await registry.subscriptionService.approve_subscription(
            sub.id, ApproveRequest(approve=True, approver="op", grantedQuota=10)
        )
        suspended = await registry.subscriptionService.suspend_subscription(approved.id)
        resumed = await registry.subscriptionService.resume_subscription(suspended.id)
        revoked = await registry.subscriptionService.revoke_subscription(resumed.id)
        for record in (sub, approved, suspended, resumed, revoked):
            assert record.updatedAt.tzinfo is not None

    async def test_registry_service_timestamps_aware(self, registry, make_api_def):
        saved = await registry.apiRegistryService.register_api(make_api_def(name="tz-registry-api"))
        assert saved.createdAt.tzinfo is not None
        reviewed = await registry.apiRegistryService.submit_for_review(saved.id)
        await registry.apiRegistryService.approve(reviewed.id)
        published = await registry.apiRegistryService.publish(saved.id)
        assert reviewed.updatedAt.tzinfo is not None
        assert published.updatedAt.tzinfo is not None

    def test_billing_router_timestamps_aware(self, client):
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "tz-billing-api",
                "version": "1.0.0",
                "method": "GET",
                "path": "/tz-billing",
                "upstream": {"type": "trino", "url": "http://trino:8080", "method": "GET"},
                "providerTenantId": "tenant-provider",
            },
        )
        api_id = create_resp.json()["id"]

        billing_resp = client.put(
            f"/api/v1/apis/{api_id}/billing",
            json={"costStrategy": "by_call", "costUnitPrice": 0.02},
        )
        assert billing_resp.status_code == 200
        assert_aware(billing_resp.json()["updatedAt"])

        sub_resp = client.post(
            f"/api/v1/apis/{api_id}/subscribe",
            json={
                "subscriberId": "tz-subscriber",
                "subscriberTenantId": "tenant-consumer",
                "purpose": "时区回归",
                "quotaExpect": 100,
            },
        )
        sub_id = sub_resp.json()["id"]
        approve_resp = client.post(
            f"/api/v1/subscriptions/{sub_id}/approve",
            json={"approve": True, "grantedQuota": 100, "approver": "admin"},
        )
        assert approve_resp.status_code == 200

        rate_limit_resp = client.put(
            f"/api/v1/subscriptions/{sub_id}/rate-limit",
            json={"qps": 5},
        )
        assert rate_limit_resp.status_code == 200
        assert_aware(rate_limit_resp.json()["updatedAt"])

        keys_resp = client.post(
            f"/api/v1/subscriptions/{sub_id}/keys",
            json={"operator": "admin"},
        )
        assert keys_resp.status_code == 201
        assert_aware(keys_resp.json()["issuedAt"])


class TestDeleteApiTransaction:
    """bug4：delete_api 级联删除在显式事务内，失败整体回滚."""

    async def test_delete_api_cascades_success(self, tmp_path, make_api_def):
        store = build_sqlite_store(tmp_path)
        api = await store.save_api(make_api_def(name="cascade-ok-api"))
        await store.save_subscription(make_sub(api.id))

        await store.delete_api(api.id)

        with pytest.raises(APINotFoundError):
            await store.get_api(api.id)
        subs = await store.list_subscriptions(SubscriptionFilter(apiId=api.id))
        assert subs == []

    async def test_delete_api_rolls_back_on_failure(self, tmp_path, make_api_def):
        store = build_sqlite_store(tmp_path)
        api = await store.save_api(make_api_def(name="cascade-rb-api"))
        await store.save_subscription(make_sub(api.id))

        real_conn = store._conn.conn
        original_ns = store._conn

        class _FailOnDeleteAPI:
            def execute(self, sql, parameters=()):
                if " ".join(sql.split()).upper().startswith("DELETE FROM APIS"):
                    raise sqlite3.OperationalError("forced failure")
                return real_conn.execute(sql, parameters)

            def __getattr__(self, name):
                return getattr(real_conn, name)

        store._conn = types.SimpleNamespace(conn=_FailOnDeleteAPI())
        try:
            with pytest.raises(sqlite3.OperationalError):
                await store.delete_api(api.id)
        finally:
            store._conn = original_ns

        kept = await store.get_api(api.id)
        assert kept.id == api.id
        subs = await store.list_subscriptions(SubscriptionFilter(apiId=api.id))
        assert len(subs) == 1


class TestRangeValidation:
    """bug5：range 参数校验 ^\\d+[smhdw]$，非法返回 400/ValidationError."""

    @pytest.mark.parametrize("bad_range", ["7x", "abc", "", "-1h", "1.5d", "7 h", "h7"])
    async def test_invalid_range_service_raises(self, bad_range, registry):
        with pytest.raises(ValidationError):
            await registry.meteringService.get_metrics("any-api", bad_range)

    @pytest.mark.parametrize("bad_range", ["7x", "abc", ""])
    def test_invalid_range_http_returns_400(self, bad_range, client):
        resp = client.get("/api/v1/apis/whatever/metrics", params={"range": bad_range})
        assert resp.status_code == 400
        assert "无效的时间范围" in resp.json()["message"]

    async def test_valid_ranges_accepted(self, registry, make_api_def):
        api = await registry.apiRegistryService.register_api(make_api_def(name="range-valid-api"))
        await registry.meteringService.record_call(
            api=api,
            subscription_id="sub-x",
            consumer_tenant_id="tenant-c",
            latency_ms=20.0,
            request_bytes=10,
            response_bytes=10,
            status_code=200,
        )
        for rng in ("30m", "90s", "12h", "7d", "2w"):
            metrics = await registry.meteringService.get_metrics(api.id, rng)
            assert metrics.callCount == 1
            assert metrics.lastCalledAt is not None
            assert metrics.lastCalledAt.tzinfo is not None

    def test_valid_range_http_returns_200(self, client):
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "range-http-api",
                "version": "1.0.0",
                "method": "GET",
                "path": "/range-http",
                "upstream": {"type": "trino", "url": "http://trino:8080", "method": "GET"},
                "providerTenantId": "tenant-provider",
            },
        )
        api_id = create_resp.json()["id"]
        resp = client.get(f"/api/v1/apis/{api_id}/metrics", params={"range": "24h"})
        assert resp.status_code == 200
        assert resp.json()["callCount"] == 0
