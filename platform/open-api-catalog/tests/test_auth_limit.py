"""鉴权和限流测试."""

from __future__ import annotations

from openapi_catalog.models import (
    APISubscription,
    ApproveRequest,
    SubscriptionStatus,
)
from openapi_catalog.repositories import (
    InvalidAPIKeyError,
    QuotaExceededError,
    RateLimitExceededError,
)
import pytest


class TestSubscription:
    """订阅服务测试."""

    @pytest.mark.asyncio
    async def test_apply_subscription(self, registry, make_api_def):
        """测试申请订阅."""
        api = make_api_def(name="sub-test")
        saved_api = await registry.apiRegistryService.register_api(api)

        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-1",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="数据查询",
            quotaExpect=100,
        )
        result = await registry.subscriptionService.apply_subscription(saved_api.id, sub)

        assert result.id != ""
        assert result.status == SubscriptionStatus.PENDING
        assert result.apiId == saved_api.id

    @pytest.mark.asyncio
    async def test_approve_subscription(self, registry, make_api_def):
        """测试审批订阅（发放 AK/SK）."""
        api = make_api_def(name="approve-test")
        saved_api = await registry.apiRegistryService.register_api(api)

        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-2",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="数据查询",
            quotaExpect=100,
        )
        saved_sub = await registry.subscriptionService.apply_subscription(saved_api.id, sub)

        # 审批通过
        approve_req = ApproveRequest(
            approve=True,
            reason="同意",
            grantedQuota=200,
            approver="admin",
        )
        approved = await registry.subscriptionService.approve_subscription(saved_sub.id, approve_req)

        assert approved.status == SubscriptionStatus.ACTIVE
        assert approved.accessKey is not None
        assert approved.secretKey is not None
        assert approved.accessKey.startswith("AK")
        assert approved.secretKey.startswith("SK")
        assert approved.grantedQuota == 200

    @pytest.mark.asyncio
    async def test_reject_subscription(self, registry, make_api_def):
        """测试驳回订阅."""
        api = make_api_def(name="reject-sub-test")
        saved_api = await registry.apiRegistryService.register_api(api)

        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-3",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="数据查询",
            quotaExpect=100,
        )
        saved_sub = await registry.subscriptionService.apply_subscription(saved_api.id, sub)

        reject_req = ApproveRequest(
            approve=False,
            reason="配额不足",
            approver="admin",
        )
        rejected = await registry.subscriptionService.approve_subscription(saved_sub.id, reject_req)

        assert rejected.status == SubscriptionStatus.REJECTED
        assert rejected.accessKey is None

    @pytest.mark.asyncio
    async def test_authenticate_valid_key(self, registry, make_api_def):
        """测试有效 AK 鉴权."""
        api = make_api_def(name="auth-valid")
        saved_api = await registry.apiRegistryService.register_api(api)

        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-auth",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="测试",
            quotaExpect=100,
        )
        saved_sub = await registry.subscriptionService.apply_subscription(saved_api.id, sub)
        approved = await registry.subscriptionService.approve_subscription(
            saved_sub.id,
            ApproveRequest(approve=True, approver="admin"),
        )

        # 鉴权
        result = await registry.subscriptionService.authenticate(approved.accessKey)
        assert result.id == approved.id

    @pytest.mark.asyncio
    async def test_authenticate_invalid_key(self, registry):
        """测试无效 AK 鉴权."""
        with pytest.raises(InvalidAPIKeyError):
            await registry.subscriptionService.authenticate("invalid-key")

    @pytest.mark.asyncio
    async def test_authenticate_revoked_key(self, registry, make_api_def):
        """测试已吊销 AK 鉴权."""
        api = make_api_def(name="auth-revoked")
        saved_api = await registry.apiRegistryService.register_api(api)

        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-revoke",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="测试",
            quotaExpect=100,
        )
        saved_sub = await registry.subscriptionService.apply_subscription(saved_api.id, sub)
        approved = await registry.subscriptionService.approve_subscription(
            saved_sub.id,
            ApproveRequest(approve=True, approver="admin"),
        )
        ak = approved.accessKey

        # 吊销
        await registry.subscriptionService.revoke_subscription(saved_sub.id)

        # 鉴权应失败
        with pytest.raises(InvalidAPIKeyError):
            await registry.subscriptionService.authenticate(ak)

    @pytest.mark.asyncio
    async def test_suspend_resume_subscription(self, registry, make_api_def):
        """测试暂停/恢复订阅."""
        api = make_api_def(name="suspend-test")
        saved_api = await registry.apiRegistryService.register_api(api)

        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-suspend",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="测试",
            quotaExpect=100,
        )
        saved_sub = await registry.subscriptionService.apply_subscription(saved_api.id, sub)
        approved = await registry.subscriptionService.approve_subscription(
            saved_sub.id,
            ApproveRequest(approve=True, approver="admin"),
        )

        # 暂停
        suspended = await registry.subscriptionService.suspend_subscription(approved.id)
        assert suspended.status == SubscriptionStatus.SUSPENDED

        # 恢复
        resumed = await registry.subscriptionService.resume_subscription(approved.id)
        assert resumed.status == SubscriptionStatus.ACTIVE

    @pytest.mark.asyncio
    async def test_duplicate_subscription(self, registry, make_api_def):
        """测试重复订阅同一 API."""
        api = make_api_def(name="dup-sub-test")
        saved_api = await registry.apiRegistryService.register_api(api)

        sub1 = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-dup",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="测试",
            quotaExpect=100,
        )
        await registry.subscriptionService.apply_subscription(saved_api.id, sub1)

        sub2 = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-dup",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="测试",
            quotaExpect=200,
        )
        from openapi_catalog.repositories import SubscriptionAlreadyExistsError

        with pytest.raises(SubscriptionAlreadyExistsError):
            await registry.subscriptionService.apply_subscription(saved_api.id, sub2)


class TestRateLimiter:
    """限流器测试."""

    def test_api_rate_limit_pass(self, rate_limiter):
        """测试 API 限流通过."""
        rate_limiter.configure_api("api-1", rate_per_second=10)
        # 应该能通过
        for _ in range(5):
            rate_limiter.check_api("api-1")

    def test_api_rate_limit_exceeded(self, rate_limiter):
        """测试 API 限流触发."""
        rate_limiter.configure_api("api-2", rate_per_second=2)
        # 桶容量 2，前 2 次通过
        rate_limiter.check_api("api-2")
        rate_limiter.check_api("api-2")
        # 第 3 次应触发限流
        with pytest.raises(RateLimitExceededError):
            rate_limiter.check_api("api-2")

    def test_subscription_quota_pass(self, rate_limiter):
        """测试订阅配额通过."""
        rate_limiter.configure_subscription("sub-1", quota_per_minute=100)
        for _ in range(50):
            rate_limiter.check_subscription("sub-1")

    def test_subscription_quota_exceeded(self, rate_limiter):
        """测试订阅配额超限."""
        rate_limiter.configure_subscription("sub-2", quota_per_minute=5)
        for _ in range(5):
            rate_limiter.check_subscription("sub-2")
        with pytest.raises(QuotaExceededError):
            rate_limiter.check_subscription("sub-2")

    def test_unconfigured_api_passes(self, rate_limiter):
        """未配置限流的 API 应放行."""
        rate_limiter.check_api("unconfigured-api")


class TestAPICall:
    """API 调用测试（鉴权 + 限流 + 计量）."""

    @pytest.mark.asyncio
    async def test_call_api_success(self, registry, make_api_def):
        """测试成功调用 API."""
        api = make_api_def(name="call-success")
        saved_api = await registry.apiRegistryService.register_api(api)
        await registry.apiRegistryService.submit_for_review(saved_api.id)
        await registry.apiRegistryService.approve(saved_api.id)
        await registry.apiRegistryService.publish(saved_api.id)

        # 配置限流
        registry.rateLimiter.configure_api(saved_api.id, 100)
        registry.rateLimiter.configure_subscription("test-sub", 100)

        # 订阅
        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-call",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="测试",
            quotaExpect=100,
        )
        saved_sub = await registry.subscriptionService.apply_subscription(saved_api.id, sub)
        approved = await registry.subscriptionService.approve_subscription(
            saved_sub.id,
            ApproveRequest(approve=True, approver="admin", grantedQuota=100),
        )
        registry.rateLimiter.configure_subscription(approved.id, 100)

        # 调用
        result = await registry.apiCallService.call_api(
            api_id=saved_api.id,
            access_key=approved.accessKey,
            payload={"q": "test"},
        )

        assert result.statusCode == 200
        assert result.error is None
        assert result.result is not None
        assert result.result["apiId"] == saved_api.id

    @pytest.mark.asyncio
    async def test_call_api_no_auth(self, registry, make_api_def):
        """测试无鉴权调用."""
        api = make_api_def(name="call-no-auth")
        saved_api = await registry.apiRegistryService.register_api(api)
        await registry.apiRegistryService.submit_for_review(saved_api.id)
        await registry.apiRegistryService.approve(saved_api.id)
        await registry.apiRegistryService.publish(saved_api.id)

        result = await registry.apiCallService.call_api(
            api_id=saved_api.id,
            access_key="invalid-key",
            payload={},
        )

        assert result.statusCode == 401
        assert result.error is not None

    @pytest.mark.asyncio
    async def test_call_api_not_running(self, registry, make_api_def):
        """测试调用未运行的 API."""
        api = make_api_def(name="call-not-running")
        saved_api = await registry.apiRegistryService.register_api(api)
        # 未发布，状态 DRAFT

        from openapi_catalog.repositories import APIStatusTransitionError

        with pytest.raises(APIStatusTransitionError):
            await registry.apiCallService.call_api(
                api_id=saved_api.id,
                access_key="some-key",
                payload={},
            )

    @pytest.mark.asyncio
    async def test_call_api_rate_limited(self, registry, make_api_def):
        """测试调用被限流."""
        api = make_api_def(name="call-rate-limited")
        saved_api = await registry.apiRegistryService.register_api(api)
        await registry.apiRegistryService.submit_for_review(saved_api.id)
        await registry.apiRegistryService.approve(saved_api.id)
        await registry.apiRegistryService.publish(saved_api.id)

        # 配置极低限流
        registry.rateLimiter.configure_api(saved_api.id, 1)

        sub = APISubscription(
            id="",
            apiId=saved_api.id,
            subscriberId="sub-rl",
            subscriberTenantId="tenant-consumer",
            providerTenantId="tenant-provider",
            purpose="测试",
            quotaExpect=100,
        )
        saved_sub = await registry.subscriptionService.apply_subscription(saved_api.id, sub)
        approved = await registry.subscriptionService.approve_subscription(
            saved_sub.id,
            ApproveRequest(approve=True, approver="admin", grantedQuota=100),
        )
        registry.rateLimiter.configure_subscription(approved.id, 100)

        # 第一次调用通过
        r1 = await registry.apiCallService.call_api(
            api_id=saved_api.id,
            access_key=approved.accessKey,
            payload={},
        )
        assert r1.statusCode == 200

        # 第二次应被限流
        r2 = await registry.apiCallService.call_api(
            api_id=saved_api.id,
            access_key=approved.accessKey,
            payload={},
        )
        assert r2.statusCode == 429
        assert r2.error is not None


class TestAPICallHTTP:
    """API 调用 HTTP 端点测试."""

    def test_call_api_no_key_http(self, client):
        """测试 HTTP 调用缺少鉴权凭证."""
        # 先注册并发布
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "call-http-no-key",
                "version": "1.0.0",
                "method": "GET",
                "path": "/call-no-key",
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

        # 调用不带 key
        response = client.post(
            f"/api/v1/apis/{api_id}/call",
            json={"payload": {}},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["statusCode"] == 401

    def test_call_api_with_key_http(self, client):
        """测试 HTTP 调用带 AK."""
        # 注册并发布 API
        create_resp = client.post(
            "/api/v1/apis",
            json={
                "name": "call-http-with-key",
                "version": "1.0.0",
                "method": "GET",
                "path": "/call-with-key",
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

        # 订阅
        sub_resp = client.post(
            f"/api/v1/apis/{api_id}/subscribe",
            json={
                "subscriberId": "sub-http",
                "subscriberTenantId": "tenant-consumer",
                "purpose": "测试",
                "quotaExpect": 100,
            },
        )
        sub_id = sub_resp.json()["id"]

        # 审批
        approve_resp = client.post(
            f"/api/v1/subscriptions/{sub_id}/approve",
            json={
                "approve": True,
                "reason": "同意",
                "grantedQuota": 100,
                "approver": "admin",
            },
        )
        ak = approve_resp.json()["accessKey"]

        # 调用
        response = client.post(
            f"/api/v1/apis/{api_id}/call",
            json={"payload": {"q": "test"}},
            headers={"X-API-Key": ak},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["statusCode"] == 200
        assert data["result"] is not None
