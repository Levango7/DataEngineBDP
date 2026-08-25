"""计量计费测试.

覆盖 4 种计费方式：
- BY_CALL:   按调用量
- BY_DATA:   按数据量
- BY_TIME:   按时间
- ONE_TIME:  一次性买断

以及内部/外部租户间结算、结算分成比例校验。
"""

from __future__ import annotations

import asyncio

import pytest

from asset_exchange.models.asset import Asset, AssetPricing
from asset_exchange.models.base import (
    AssetType,
    BillingMode,
    SecurityLevel,
    SubscriptionStatus,
)
from asset_exchange.models.settlement import SettleRequest
from asset_exchange.models.subscription import Subscription
from asset_exchange.repositories import ValidationError

# ---------- 辅助函数 ----------


def _setup_asset_with_pricing(client, name="billing-asset", owner="tenant-A", mode="by_call", price=0.05, unit="次"):
    """上架带定价的资产，返回 asset_id."""
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": name,
            "type": "table",
            "owner": owner,
            "securityLevel": "internal",
            "qualityScore": 85.0,
            "pricing": {"mode": mode, "price": price, "unit": unit},
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def _setup_active_subscription(client, asset_id, subscriber="tenant-B"):
    """订阅并审批通过，返回 subscription_id."""
    resp = client.post(
        f"/api/v1/assets/{asset_id}/subscribe",
        json={"subscriberId": subscriber, "durationDays": 30},
    )
    assert resp.status_code == 201, resp.text
    sid = resp.json()["id"]
    resp = client.post(
        f"/api/v1/subscriptions/{sid}/approve",
        json={"action": "approve", "approverId": "admin"},
    )
    assert resp.status_code == 200
    return sid


def _charge(client, subscription_id, usage=1.0, period=None):
    """触发计费."""
    body = {"usage": usage}
    if period:
        body["period"] = period
    resp = client.post(
        f"/api/v1/subscriptions/{subscription_id}/charge",
        json=body,
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


# ---------- 按调用量计费 ----------


def test_charge_by_call(client):
    aid = _setup_asset_with_pricing(client, name="by-call-asset", mode="by_call", price=0.05)
    sid = _setup_active_subscription(client, aid)
    record = _charge(client, sid, usage=100)
    assert record["mode"] == "by_call"
    assert record["usage"] == 100
    assert record["unitPrice"] == 0.05
    # 金额 = 0.05 * 100 = 5.0
    assert record["amount"] == 5.0
    # 外部租户：提供方 80%，平台 20%
    assert abs(record["providerRevenue"] - 4.0) < 1e-6
    assert abs(record["platformRevenue"] - 1.0) < 1e-6
    assert record["isInternal"] is False


# ---------- 按数据量计费 ----------


def test_charge_by_data(client):
    aid = _setup_asset_with_pricing(client, name="by-data-asset", mode="by_data", price=2.0, unit="千行")
    sid = _setup_active_subscription(client, aid)
    record = _charge(client, sid, usage=5000)  # 5000 行
    # 金额 = 2.0 * 5000 / 1000 = 10.0
    assert record["amount"] == 10.0
    assert abs(record["providerRevenue"] - 8.0) < 1e-6
    assert abs(record["platformRevenue"] - 2.0) < 1e-6


# ---------- 按时间计费 ----------


def test_charge_by_time(client):
    aid = _setup_asset_with_pricing(client, name="by-time-asset", mode="by_time", price=100.0, unit="月")
    sid = _setup_active_subscription(client, aid)
    record = _charge(client, sid, usage=1)  # 1 个月
    # 金额 = 100.0 * 1 = 100.0
    assert record["amount"] == 100.0
    assert abs(record["providerRevenue"] - 80.0) < 1e-6
    assert abs(record["platformRevenue"] - 20.0) < 1e-6


# ---------- 一次性买断 ----------


def test_charge_one_time(client):
    aid = _setup_asset_with_pricing(client, name="one-time-asset", mode="one_time", price=5000.0, unit="买断")
    sid = _setup_active_subscription(client, aid)
    record = _charge(client, sid, usage=1)
    # 一次性买断：金额 = 5000.0
    assert record["amount"] == 5000.0
    assert abs(record["providerRevenue"] - 4000.0) < 1e-6
    assert abs(record["platformRevenue"] - 1000.0) < 1e-6


# ---------- 内部租户间结算 ----------


def test_internal_settlement_same_tenant(registry):
    """内部价命中：订阅方租户与提供方租户一致时走内部结算（成本系数 0.3）.

    Phase A 后订阅方 subscriberId 即租户 ID，内部判定为租户相等。
    订阅自有资产被业务禁止，故直接向仓储注入同租户 ACTIVE 订阅构造场景。
    """

    async def scenario():
        asset = await registry.assetService.list_asset(
            Asset(
                name="internal-asset",
                type=AssetType.TABLE,
                tenantId="tenant-A",
                securityLevel=SecurityLevel.INTERNAL,
                qualityScore=85.0,
                pricing=AssetPricing(mode=BillingMode.BY_CALL, price=1.0, unit="次"),
            )
        )
        sub = Subscription(
            assetId=asset.id,
            subscriberId=asset.tenantId,
            status=SubscriptionStatus.ACTIVE,
        )
        sid = await registry.subRepo.save(sub)
        return await registry.billingService.charge(sid, usage=100)

    record = asyncio.run(scenario())
    assert record.amount == 100.0
    assert record.isInternal is True
    # 内部结算：成本系数 0.3
    # 提供方收益 = 100 * 0.3 * 0.8 = 24.0
    # 平台抽成 = 100 * 0.3 * 0.2 = 6.0
    assert abs(record.providerRevenue - 24.0) < 1e-6
    assert abs(record.platformRevenue - 6.0) < 1e-6


def test_internal_not_hit_for_different_tenants(client):
    """内部价不命中：不同租户（含同冒号前缀）一律外部结算."""
    aid = _setup_asset_with_pricing(client, name="prefix-asset", owner="org1:001", mode="by_call", price=1.0)
    sid = _setup_active_subscription(client, aid, subscriber="org1:002")
    record = _charge(client, sid, usage=100)
    assert record["isInternal"] is False
    # 外部结算：提供方 80%，平台 20%
    assert abs(record["providerRevenue"] - 80.0) < 1e-6
    assert abs(record["platformRevenue"] - 20.0) < 1e-6


def test_external_settlement(client):
    """外部租户间流通走外部结算."""
    aid = _setup_asset_with_pricing(client, name="external-asset", owner="tenant-A", mode="by_call", price=1.0)
    sid = _setup_active_subscription(client, aid, subscriber="tenant-B")
    record = _charge(client, sid, usage=100)
    assert record["isInternal"] is False
    # 外部结算：提供方 80%，平台 20%
    assert abs(record["providerRevenue"] - 80.0) < 1e-6
    assert abs(record["platformRevenue"] - 20.0) < 1e-6


# ---------- 计费记录查询 ----------


def test_get_asset_billing(client):
    """查询资产计费记录汇总."""
    aid = _setup_asset_with_pricing(client, name="billing-query-asset", mode="by_call", price=0.1)
    sid = _setup_active_subscription(client, aid)
    _charge(client, sid, usage=50, period="2026-08")
    _charge(client, sid, usage=30, period="2026-08")

    resp = client.get(f"/api/v1/assets/{aid}/billing")
    assert resp.status_code == 200
    body = resp.json()
    assert body["assetId"] == aid
    assert body["recordCount"] == 2
    # 总金额 = 0.1 * 50 + 0.1 * 30 = 8.0
    assert abs(body["totalAmount"] - 8.0) < 1e-6
    assert body["totalProviderRevenue"] > 0
    assert body["totalPlatformRevenue"] > 0


def test_get_asset_billing_empty(client):
    """无计费记录的资产返回空汇总."""
    aid = _setup_asset_with_pricing(client, name="no-billing-asset")
    resp = client.get(f"/api/v1/assets/{aid}/billing")
    assert resp.status_code == 200
    body = resp.json()
    assert body["recordCount"] == 0
    assert body["totalAmount"] == 0.0


# ---------- 计费周期 ----------


def test_charge_with_period(client):
    """指定计费周期."""
    aid = _setup_asset_with_pricing(client, name="period-asset", mode="by_call", price=1.0)
    sid = _setup_active_subscription(client, aid)
    record = _charge(client, sid, usage=10, period="2026-07")
    assert record["period"] == "2026-07"


def test_charge_default_period(client):
    """不指定周期时默认取当前年月."""
    aid = _setup_asset_with_pricing(client, name="default-period-asset", mode="by_call", price=1.0)
    sid = _setup_active_subscription(client, aid)
    record = _charge(client, sid, usage=10)
    # 周期格式 YYYY-MM
    assert len(record["period"]) == 7
    assert record["period"][4] == "-"


# ---------- 使用统计关联 ----------


def test_usage_after_subscription(client):
    """订阅审批后使用统计更新."""
    aid = _setup_asset_with_pricing(client, name="usage-asset", mode="by_call", price=0.1)
    sid = _setup_active_subscription(client, aid)
    # 触发计费
    _charge(client, sid, usage=100)

    resp = client.get(f"/api/v1/assets/{aid}/usage")
    assert resp.status_code == 200
    body = resp.json()
    assert body["assetId"] == aid
    assert body["subscriberCount"] == 1
    assert body["activeSubscriptions"] == 1


# ---------- 结算分成比例校验 ----------


def _settle(registry, asset_id: str, provider_share=None, platform_share=None, raw=False):
    if raw:
        req = SettleRequest.model_construct(providerShare=provider_share, platformShare=platform_share, period=None)
    else:
        req = SettleRequest(providerShare=provider_share, platformShare=platform_share)
    return asyncio.run(registry.settlementService.settle(asset_id, req))


class TestSettleShareValidation:
    def test_shares_sum_ne_one_rejected(self, client, registry):
        aid = _setup_asset_with_pricing(client, name="settle-sum-asset", mode="by_call", price=1.0)
        with pytest.raises(ValidationError):
            _settle(registry, aid, provider_share=0.5, platform_share=0.6)

    def test_negative_share_rejected(self, client, registry):
        """负数比例在服务层被拒（model_construct 绕过 pydantic 以直测服务守卫）."""
        aid = _setup_asset_with_pricing(client, name="settle-negative-asset", mode="by_call", price=1.0)
        with pytest.raises(ValidationError):
            _settle(registry, aid, provider_share=-0.1, platform_share=1.1, raw=True)

    def test_share_within_tolerance_accepted(self, client, registry):
        aid = _setup_asset_with_pricing(client, name="settle-tolerance-asset", mode="by_call", price=1.0)
        settlement = _settle(registry, aid, provider_share=0.8, platform_share=0.2 + 5e-10)
        assert settlement.status.value == "settled"

    def test_share_beyond_tolerance_rejected(self, client, registry):
        aid = _setup_asset_with_pricing(client, name="settle-beyond-asset", mode="by_call", price=1.0)
        with pytest.raises(ValidationError):
            _settle(registry, aid, provider_share=0.8, platform_share=0.200001)

    def test_api_settle_invalid_shares_returns_422(self, client):
        aid = _setup_asset_with_pricing(client, name="settle-api-asset", mode="by_call", price=1.0)
        resp = client.post(
            f"/api/v1/assets/{aid}/settle",
            json={"providerShare": 0.5, "platformShare": 0.4},
        )
        assert resp.status_code == 422

    def test_api_settle_negative_share_returns_422(self, client):
        aid = _setup_asset_with_pricing(client, name="settle-api-neg-asset", mode="by_call", price=1.0)
        resp = client.post(
            f"/api/v1/assets/{aid}/settle",
            json={"providerShare": -0.2, "platformShare": 1.2},
        )
        assert resp.status_code == 422

    def test_api_settle_default_shares_succeed(self, client):
        aid = _setup_asset_with_pricing(client, name="settle-api-ok-asset", mode="by_call", price=1.0)
        resp = client.post(f"/api/v1/assets/{aid}/settle", json={})
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["providerShare"] == 0.8
        assert body["platformShare"] == 0.2
