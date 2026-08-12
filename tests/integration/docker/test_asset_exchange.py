"""Asset Exchange（数据资产流通平台, L5.6 / T039）集成测试.

被测组件：platform/asset-exchange（FastAPI, 默认端口 8087）
启动方式：
    方式1（推荐）：使用 FastAPI TestClient 直接测试 ASGI app（无需启动服务）
    方式2：cd platform/asset-exchange && ASSET_EXCHANGE_PORT=8087 python main.py

测试覆盖（≥20 用例）：
    - 登记场景：资产元数据登记成功
    - 上架场景：上架审核 + 合规/质量/分级检查
    - 流通场景：订阅/下载/API 调用 + 三种定价
    - 变现场景：自动结算 + 按次/按量/订阅费用计算正确
    - 分账场景：分账到数据提供方与平台 + 比例可配置
    - 审计场景：全过程审计留痕 + 不可篡改
    - 闭环场景：登记→上架→流通→变现→分账完整闭环

健康检查：GET /api/v1/health → {"status": "UP", "store": "...", "version": "0.1.0"}
"""
from __future__ import annotations

import os
import sys
import uuid
from pathlib import Path

import pytest

# 将 platform/asset-exchange 加入 sys.path，使 asset_exchange 包可导入
_PLATFORM_DIR = Path(__file__).resolve().parents[3] / "platform" / "asset-exchange"
if str(_PLATFORM_DIR) not in sys.path:
    sys.path.insert(0, str(_PLATFORM_DIR))

# 在导入 create_app 之前设置环境变量，使用 Mock 存储避免污染
os.environ.setdefault("ASSET_EXCHANGE_STORE_TYPE", "mock")

from fastapi.testclient import TestClient  # noqa: E402

from asset_exchange.api.app import create_app  # noqa: E402
from asset_exchange.config.settings import Settings, reset_settings  # noqa: E402
from asset_exchange.services.registry import build_services  # noqa: E402


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture
def app():
    """创建 FastAPI 应用实例（Mock 存储，每次测试独立）."""
    reset_settings()
    settings = Settings(storeType="mock")
    registry = build_services(settings)
    application = create_app(settings=settings, registry=registry)
    return application


@pytest.fixture
def client(app):
    """创建 TestClient（基于 httpx，直接测试 ASGI app）."""
    with TestClient(app) as c:
        yield c


# API 路径常量
PREFIX = "/api/v1"
ASSETS = PREFIX + "/assets"
SUBS = PREFIX + "/subscriptions"
AUDIT = PREFIX + "/audit-logs"
HEALTH = PREFIX + "/health"


def _make_asset_payload(
    name: str | None = None,
    tenant_id: str = "tenant-provider-001",
    pricing_mode: str = "by_call",
    price: float = 1.0,
    quality_score: float = 85.0,
    security_level: str = "internal",
) -> dict:
    """构造资产登记/上架请求体."""
    return {
        "name": name or f"it-test-asset-{uuid.uuid4().hex[:8]}",
        "type": "table",
        "tenantId": tenant_id,
        "description": "集成测试资产",
        "securityLevel": security_level,
        "qualityScore": quality_score,
        "updateFrequency": "daily",
        "tags": {"env": "it"},
        "pricing": {"mode": pricing_mode, "price": price, "unit": "次"},
    }


def _register_and_publish(
    client: TestClient,
    **kwargs,
) -> dict:
    """辅助：登记 + 上架资产，返回上架后的资产."""
    payload = _make_asset_payload(**kwargs)
    # 登记
    resp = client.post(ASSETS + "/register", json=payload)
    assert resp.status_code == 201, resp.text
    asset = resp.json()
    # 上架
    resp = client.post(f"{ASSETS}/{asset['id']}/publish")
    assert resp.status_code == 200, resp.text
    return resp.json()


def _subscribe_and_approve(
    client: TestClient,
    asset_id: str,
    subscriber_id: str = "tenant-consumer-001",
) -> dict:
    """辅助：订阅 + 审批通过，返回订阅记录."""
    resp = client.post(
        f"{ASSETS}/{asset_id}/subscribe",
        json={"subscriberId": subscriber_id, "period": "monthly", "durationDays": 30},
    )
    assert resp.status_code == 201, resp.text
    sub = resp.json()
    # 审批
    resp = client.post(
        f"{SUBS}/{sub['id']}/approve",
        json={"action": "approve", "approverId": "admin-001"},
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# 1. 健康检查 & 基础冒烟（2 个）
# ---------------------------------------------------------------------------
def test_health_check(client):
    """健康检查返回 200 且 status=UP."""
    resp = client.get(HEALTH)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "UP"
    assert "version" in body


def test_openapi_schema(client):
    """OpenAPI schema 可访问."""
    resp = client.get("/openapi.json")
    assert resp.status_code == 200
    schema = resp.json()
    assert schema["info"]["title"] == "Asset Exchange Platform"


# ---------------------------------------------------------------------------
# 2. 登记场景（3 个）
# ---------------------------------------------------------------------------
def test_register_asset_success(client):
    """登记场景：资产元数据登记成功，状态为 draft."""
    payload = _make_asset_payload()
    resp = client.post(ASSETS + "/register", json=payload)
    assert resp.status_code == 201, resp.text
    asset = resp.json()
    assert asset["name"] == payload["name"]
    assert asset["status"] == "draft"
    assert asset["id"]


def test_register_asset_with_schema(client):
    """登记场景：带 Schema 的资产登记成功."""
    payload = _make_asset_payload()
    payload["schema"] = {
        "fields": [
            {"name": "id", "type": "bigint", "description": "主键"},
            {"name": "name", "type": "string", "description": "名称"},
        ]
    }
    resp = client.post(ASSETS + "/register", json=payload)
    assert resp.status_code == 201, resp.text
    asset = resp.json()
    assert asset["schema"]["fields"][0]["name"] == "id"


def test_register_asset_duplicate_name(client):
    """登记场景：同名资产登记失败（409）."""
    payload = _make_asset_payload(name="dup-asset-" + uuid.uuid4().hex[:8])
    resp = client.post(ASSETS + "/register", json=payload)
    assert resp.status_code == 201

    resp2 = client.post(ASSETS + "/register", json=payload)
    assert resp2.status_code == 409


# ---------------------------------------------------------------------------
# 3. 上架场景（4 个）
# ---------------------------------------------------------------------------
def test_publish_asset_success(client):
    """上架场景：登记后上架成功，状态为 listed."""
    payload = _make_asset_payload()
    resp = client.post(ASSETS + "/register", json=payload)
    asset_id = resp.json()["id"]

    resp = client.post(f"{ASSETS}/{asset_id}/publish")
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "listed"


def test_audit_approved_success(client):
    """上架场景：审核通过后状态为 listed."""
    payload = _make_asset_payload()
    resp = client.post(ASSETS + "/register", json=payload)
    asset_id = resp.json()["id"]

    resp = client.post(
        f"{ASSETS}/{asset_id}/audit",
        json={"result": "approved", "auditorId": "admin-001"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "listed"


def test_audit_rejected(client):
    """上架场景：审核驳回后状态为 rejected."""
    payload = _make_asset_payload()
    resp = client.post(ASSETS + "/register", json=payload)
    asset_id = resp.json()["id"]

    resp = client.post(
        f"{ASSETS}/{asset_id}/audit",
        json={"result": "rejected", "auditorId": "admin-001", "reason": "质量不足"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "rejected"


def test_publish_quality_check_failed(client):
    """上架场景：质量评分 < 60 上架失败（422）."""
    payload = _make_asset_payload(quality_score=30.0)
    resp = client.post(ASSETS + "/register", json=payload)
    asset_id = resp.json()["id"]

    resp = client.post(f"{ASSETS}/{asset_id}/publish")
    assert resp.status_code == 422, resp.text


def test_publish_sensitive_without_desensitize_failed(client):
    """上架场景：敏感资产未配置脱敏规则上架失败（422）."""
    payload = _make_asset_payload(security_level="sensitive")
    resp = client.post(ASSETS + "/register", json=payload)
    asset_id = resp.json()["id"]

    resp = client.post(f"{ASSETS}/{asset_id}/publish")
    assert resp.status_code == 422, resp.text


# ---------------------------------------------------------------------------
# 4. 流通场景（5 个）
# ---------------------------------------------------------------------------
def test_subscribe_asset_success(client):
    """流通场景：订阅资产成功，状态为 pending."""
    asset = _register_and_publish(client)
    resp = client.post(
        f"{ASSETS}/{asset['id']}/subscribe",
        json={"subscriberId": "tenant-consumer-001", "period": "monthly", "durationDays": 30},
    )
    assert resp.status_code == 201, resp.text
    sub = resp.json()
    assert sub["status"] == "pending"
    assert sub["assetId"] == asset["id"]


def test_download_asset_success(client):
    """流通场景：下载资产成功."""
    asset = _register_and_publish(client)
    resp = client.post(
        f"{ASSETS}/{asset['id']}/download",
        json={"subscriberId": "tenant-consumer-001", "rows": 1000},
    )
    assert resp.status_code == 200, resp.text
    result = resp.json()
    assert result["method"] == "download"
    assert result["rows"] == 1000


def test_invoke_asset_success(client):
    """流通场景：API 调用资产成功."""
    asset = _register_and_publish(client)
    resp = client.post(
        f"{ASSETS}/{asset['id']}/invoke",
        json={"subscriberId": "tenant-consumer-001", "params": {"q": "test"}},
    )
    assert resp.status_code == 200, resp.text
    result = resp.json()
    assert result["method"] == "invoke"
    assert result["result"]["status"] == "ok"


def test_subscribe_own_asset_failed(client):
    """流通场景：不允许订阅自己的资产（422）."""
    asset = _register_and_publish(client, tenant_id="tenant-001")
    resp = client.post(
        f"{ASSETS}/{asset['id']}/subscribe",
        json={"subscriberId": "tenant-001"},
    )
    assert resp.status_code == 422, resp.text


def test_three_pricing_modes(client):
    """流通场景：三种定价方式（按次/按量/订阅）均可配置."""
    # 按次
    asset1 = _register_and_publish(client, pricing_mode="by_call", price=0.5)
    assert asset1["pricing"]["mode"] == "by_call"
    # 按量
    asset2 = _register_and_publish(client, pricing_mode="by_data", price=2.0)
    assert asset2["pricing"]["mode"] == "by_data"
    # 订阅
    asset3 = _register_and_publish(client, pricing_mode="subscription", price=100.0)
    assert asset3["pricing"]["mode"] == "subscription"


# ---------------------------------------------------------------------------
# 5. 变现场景（4 个）
# ---------------------------------------------------------------------------
def test_settle_by_call(client):
    """变现场景：按次计费 + 自动结算，费用计算正确.

    按次：amount = unit_price * usage = 1.0 * 10 = 10.0
    提供方：10.0 * 0.8 = 8.0
    平台：10.0 * 0.2 = 2.0
    """
    asset = _register_and_publish(client, pricing_mode="by_call", price=1.0)
    sub = _subscribe_and_approve(client, asset["id"])

    # 计费
    resp = client.post(
        f"{SUBS}/{sub['id']}/charge",
        json={"usage": 10, "period": "2026-08"},
    )
    assert resp.status_code == 200, resp.text
    record = resp.json()
    assert record["amount"] == 10.0
    assert record["providerRevenue"] == 8.0
    assert record["platformRevenue"] == 2.0

    # 结算
    resp = client.post(f"{ASSETS}/{asset['id']}/settle", json={"period": "2026-08"})
    assert resp.status_code == 200, resp.text
    settlement = resp.json()
    assert settlement["totalAmount"] == 10.0
    assert settlement["providerRevenue"] == 8.0
    assert settlement["platformRevenue"] == 2.0
    assert settlement["status"] == "settled"


def test_settle_by_data(client):
    """变现场景：按量计费 + 自动结算，费用计算正确.

    按量：amount = unit_price * usage / 1000 = 2.0 * 5000 / 1000 = 10.0
    """
    asset = _register_and_publish(client, pricing_mode="by_data", price=2.0)
    sub = _subscribe_and_approve(client, asset["id"])

    resp = client.post(
        f"{SUBS}/{sub['id']}/charge",
        json={"usage": 5000, "period": "2026-08"},
    )
    assert resp.status_code == 200, resp.text
    record = resp.json()
    assert record["amount"] == 10.0

    resp = client.post(f"{ASSETS}/{asset['id']}/settle", json={"period": "2026-08"})
    assert resp.status_code == 200, resp.text
    assert resp.json()["totalAmount"] == 10.0


def test_settle_subscription(client):
    """变现场景：订阅计费 + 自动结算，费用计算正确.

    订阅：amount = unit_price * usage = 100.0 * 1 = 100.0
    """
    asset = _register_and_publish(client, pricing_mode="subscription", price=100.0)
    sub = _subscribe_and_approve(client, asset["id"])

    resp = client.post(
        f"{SUBS}/{sub['id']}/charge",
        json={"usage": 1, "period": "2026-08"},
    )
    assert resp.status_code == 200, resp.text
    record = resp.json()
    assert record["amount"] == 100.0

    resp = client.post(f"{ASSETS}/{asset['id']}/settle", json={"period": "2026-08"})
    assert resp.status_code == 200, resp.text
    settlement = resp.json()
    assert settlement["totalAmount"] == 100.0
    assert settlement["providerRevenue"] == 80.0
    assert settlement["platformRevenue"] == 20.0


def test_settle_with_custom_share(client):
    """变现场景：自定义分成比例结算.

    提供方 70% / 平台 30%
    """
    asset = _register_and_publish(client, pricing_mode="by_call", price=10.0)
    sub = _subscribe_and_approve(client, asset["id"])

    client.post(
        f"{SUBS}/{sub['id']}/charge",
        json={"usage": 1, "period": "2026-08"},
    )

    resp = client.post(
        f"{ASSETS}/{asset['id']}/settle",
        json={"period": "2026-08", "providerShare": 0.7, "platformShare": 0.3},
    )
    assert resp.status_code == 200, resp.text
    settlement = resp.json()
    assert settlement["totalAmount"] == 10.0
    assert settlement["providerRevenue"] == 7.0
    assert settlement["platformRevenue"] == 3.0


# ---------------------------------------------------------------------------
# 6. 分账场景（2 个）
# ---------------------------------------------------------------------------
def test_allocate_success(client):
    """分账场景：分账到数据提供方与平台成功."""
    asset = _register_and_publish(client, pricing_mode="by_call", price=1.0)
    sub = _subscribe_and_approve(client, asset["id"])
    client.post(f"{SUBS}/{sub['id']}/charge", json={"usage": 10, "period": "2026-08"})
    client.post(f"{ASSETS}/{asset['id']}/settle", json={"period": "2026-08"})

    resp = client.post(
        f"{ASSETS}/{asset['id']}/allocate",
        json={"providerAccountId": "acc-provider-001"},
    )
    assert resp.status_code == 200, resp.text
    allocation = resp.json()
    assert allocation["providerAmount"] == 8.0
    assert allocation["platformAmount"] == 2.0
    assert allocation["status"] == "allocated"
    assert allocation["providerAccountId"] == "acc-provider-001"


def test_allocate_list(client):
    """分账场景：查询分账列表."""
    asset = _register_and_publish(client, pricing_mode="by_call", price=1.0)
    sub = _subscribe_and_approve(client, asset["id"])
    client.post(f"{SUBS}/{sub['id']}/charge", json={"usage": 5, "period": "2026-08"})
    client.post(f"{ASSETS}/{asset['id']}/settle", json={"period": "2026-08"})
    client.post(f"{ASSETS}/{asset['id']}/allocate")

    resp = client.get(f"{ASSETS}/{asset['id']}/allocations")
    assert resp.status_code == 200, resp.text
    allocations = resp.json()
    assert len(allocations) >= 1
    assert allocations[0]["providerAmount"] == 4.0  # 5 * 0.8
    assert allocations[0]["platformAmount"] == 1.0  # 5 * 0.2


# ---------------------------------------------------------------------------
# 7. 审计场景（3 个）
# ---------------------------------------------------------------------------
def test_audit_log_recorded_on_register(client):
    """审计场景：登记操作产生审计日志."""
    payload = _make_asset_payload()
    resp = client.post(ASSETS + "/register", json=payload)
    asset_id = resp.json()["id"]

    # 查询资产审计日志
    resp = client.get(f"{ASSETS}/{asset_id}/audit-logs")
    assert resp.status_code == 200, resp.text
    logs = resp.json()
    assert len(logs) >= 1
    register_logs = [l for l in logs if l["action"] == "register"]
    assert len(register_logs) >= 1


def test_audit_log_integrity_verified(client):
    """审计场景：审计日志哈希链完整性校验通过."""
    # 产生一些审计日志
    asset = _register_and_publish(client)
    sub = _subscribe_and_approve(client, asset["id"])

    resp = client.get(AUDIT + "/integrity")
    assert resp.status_code == 200, resp.text
    report = resp.json()
    assert report["verified"] is True
    assert report["totalLogs"] >= 2  # 至少有 register + publish + subscribe + approve


def test_audit_log_full_process(client):
    """审计场景：全过程审计留痕（登记/上架/订阅/审批/计费/结算/分账）."""
    asset = _register_and_publish(client)
    sub = _subscribe_and_approve(client, asset["id"])
    client.post(f"{SUBS}/{sub['id']}/charge", json={"usage": 1, "period": "2026-08"})
    client.post(f"{ASSETS}/{asset['id']}/settle", json={"period": "2026-08"})
    client.post(f"{ASSETS}/{asset['id']}/allocate")

    resp = client.get(f"{ASSETS}/{asset['id']}/audit-logs")
    assert resp.status_code == 200, resp.text
    logs = resp.json()
    actions = {l["action"] for l in logs}
    # 验证关键动作都有留痕
    assert "register" in actions
    assert "publish" in actions
    assert "subscribe" in actions
    assert "settle" in actions
    assert "allocate" in actions

    # 完整性校验
    resp = client.get(AUDIT + "/integrity")
    assert resp.json()["verified"] is True


# ---------------------------------------------------------------------------
# 8. 闭环场景（2 个）
# ---------------------------------------------------------------------------
def test_full_lifecycle_by_call(client):
    """闭环场景：登记→上架→订阅→审批→计费→结算→分账完整闭环（按次定价）."""
    # 1. 登记
    payload = _make_asset_payload(pricing_mode="by_call", price=2.0)
    resp = client.post(ASSETS + "/register", json=payload)
    assert resp.status_code == 201
    asset_id = resp.json()["id"]
    assert resp.json()["status"] == "draft"

    # 2. 上架
    resp = client.post(f"{ASSETS}/{asset_id}/publish")
    assert resp.status_code == 200
    assert resp.json()["status"] == "listed"

    # 3. 订阅
    resp = client.post(
        f"{ASSETS}/{asset_id}/subscribe",
        json={"subscriberId": "tenant-consumer-001", "period": "monthly", "durationDays": 30},
    )
    assert resp.status_code == 201
    sub_id = resp.json()["id"]
    assert resp.json()["status"] == "pending"

    # 4. 审批
    resp = client.post(
        f"{SUBS}/{sub_id}/approve",
        json={"action": "approve", "approverId": "admin-001"},
    )
    assert resp.status_code == 200
    assert resp.json()["status"] == "active"

    # 5. 计费（按次：2.0 * 5 = 10.0）
    resp = client.post(
        f"{SUBS}/{sub_id}/charge",
        json={"usage": 5, "period": "2026-08"},
    )
    assert resp.status_code == 200
    assert resp.json()["amount"] == 10.0

    # 6. 结算
    resp = client.post(f"{ASSETS}/{asset_id}/settle", json={"period": "2026-08"})
    assert resp.status_code == 200
    settlement = resp.json()
    assert settlement["totalAmount"] == 10.0
    assert settlement["providerRevenue"] == 8.0
    assert settlement["platformRevenue"] == 2.0

    # 7. 分账
    resp = client.post(f"{ASSETS}/{asset_id}/allocate")
    assert resp.status_code == 200
    allocation = resp.json()
    assert allocation["providerAmount"] == 8.0
    assert allocation["platformAmount"] == 2.0

    # 8. 审计校验
    resp = client.get(AUDIT + "/integrity")
    assert resp.json()["verified"] is True


def test_full_lifecycle_subscription_pricing(client):
    """闭环场景：登记→上架→订阅→审批→计费→结算→分账完整闭环（订阅定价）."""
    # 1. 登记 + 上架
    asset = _register_and_publish(
        client, pricing_mode="subscription", price=200.0
    )

    # 2. 订阅 + 审批
    sub = _subscribe_and_approve(client, asset["id"])

    # 3. 计费（订阅：200.0 * 1 = 200.0）
    resp = client.post(
        f"{SUBS}/{sub['id']}/charge",
        json={"usage": 1, "period": "2026-08"},
    )
    assert resp.status_code == 200
    assert resp.json()["amount"] == 200.0

    # 4. 结算
    resp = client.post(f"{ASSETS}/{asset['id']}/settle", json={"period": "2026-08"})
    settlement = resp.json()
    assert settlement["totalAmount"] == 200.0
    assert settlement["providerRevenue"] == 160.0  # 200 * 0.8
    assert settlement["platformRevenue"] == 40.0  # 200 * 0.2

    # 5. 分账
    resp = client.post(f"{ASSETS}/{asset['id']}/allocate")
    allocation = resp.json()
    assert allocation["providerAmount"] == 160.0
    assert allocation["platformAmount"] == 40.0

    # 6. 审计校验
    resp = client.get(AUDIT + "/integrity")
    assert resp.json()["verified"] is True


# ---------------------------------------------------------------------------
# 9. 兼容性测试（1 个）
# ---------------------------------------------------------------------------
def test_legacy_list_asset_endpoint(client):
    """兼容性：旧接口 POST /assets 仍可用（等价于 register + publish）."""
    payload = _make_asset_payload()
    resp = client.post(ASSETS, json=payload)
    assert resp.status_code == 201, resp.text
    asset = resp.json()
    assert asset["status"] == "listed"