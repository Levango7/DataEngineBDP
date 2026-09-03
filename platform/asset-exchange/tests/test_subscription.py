"""订阅/审批流程测试."""

from __future__ import annotations

from test_jwt_auth import admin_token, auth_headers, jwt_client, make_token


def _admin_headers():
    return auth_headers(admin_token())


# ---------- 辅助函数 ----------


def _list_asset(client, name="sub-test-asset", owner="tenant-A", headers=None):
    """上架资产，返回 asset_id."""
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": name,
            "type": "table",
            "owner": owner,
            "securityLevel": "internal",
            "qualityScore": 85.0,
            "pricing": {"mode": "by_call", "price": 0.05, "unit": "次"},
        },
        headers=headers or {},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def _subscribe(client, asset_id, subscriber_id="tenant-B", headers=None):
    """订阅资产，返回 subscription_id."""
    resp = client.post(
        f"/api/v1/assets/{asset_id}/subscribe",
        json={
            "subscriberId": subscriber_id,
            "period": "monthly",
            "durationDays": 30,
            "pullConfig": {"cron": "0 0 * * *"},
        },
        headers=headers or {},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


# ---------- 订阅 ----------


def test_subscribe_asset(client):
    aid = _list_asset(client)
    sid = _subscribe(client, aid)
    resp = client.get(f"/api/v1/assets/{aid}/subscriptions")
    body = resp.json()
    assert len(body) == 1
    assert body[0]["id"] == sid
    assert body[0]["status"] == "pending"
    assert body[0]["assetId"] == aid
    assert body[0]["subscriberId"] == "tenant-B"


def test_subscribe_nonexistent_asset(client):
    resp = client.post(
        "/api/v1/assets/nonexistent/subscribe",
        json={"subscriberId": "tenant-B"},
    )
    assert resp.status_code == 404


def test_subscribe_offline_asset(client):
    """下架资产不可订阅."""
    aid = _list_asset(client)
    client.delete(f"/api/v1/assets/{aid}")
    resp = client.post(
        f"/api/v1/assets/{aid}/subscribe",
        json={"subscriberId": "tenant-B"},
    )
    assert resp.status_code == 409


def test_subscribe_own_asset(client):
    """不允许订阅自己的资产."""
    aid = _list_asset(client, owner="tenant-A")
    resp = client.post(
        f"/api/v1/assets/{aid}/subscribe",
        json={"subscriberId": "tenant-A"},
    )
    assert resp.status_code == 422


# ---------- 审批 ----------


def test_approve_subscription(app, monkeypatch):
    """审批通过：approverId 取 JWT sub claim（admin token → root）."""
    c = jwt_client(monkeypatch, app)
    aid = _list_asset(c, headers=_admin_headers())
    sid = _subscribe(c, aid, headers=_admin_headers())
    resp = c.post(
        f"/api/v1/asset-subscriptions/{sid}/approve",
        json={"action": "approve"},
        headers=_admin_headers(),
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "active"
    assert body["approverId"] == "root"
    assert body["startTime"] is not None
    assert body["endTime"] is not None


def test_reject_subscription(app, monkeypatch):
    """审批驳回：approverId 取 JWT sub claim，reason 保留."""
    c = jwt_client(monkeypatch, app)
    aid = _list_asset(c, name="reject-asset", headers=_admin_headers())
    sid = _subscribe(c, aid, headers=_admin_headers())
    resp = c.post(
        f"/api/v1/asset-subscriptions/{sid}/approve",
        json={
            "action": "reject",
            "reason": "不符合安全要求",
        },
        headers=_admin_headers(),
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "rejected"
    assert body["approverId"] == "root"
    assert body["rejectReason"] == "不符合安全要求"


def test_approve_nonexistent_subscription(client):
    resp = client.post(
        "/api/v1/asset-subscriptions/nonexistent/approve",
        json={"action": "approve", "approverId": "admin"},
    )
    assert resp.status_code == 404


def test_approve_already_approved(client):
    """重复审批返回 409."""
    aid = _list_asset(client)
    sid = _subscribe(client, aid)
    # 第一次审批
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/approve",
        json={"action": "approve", "approverId": "admin"},
    )
    assert resp.status_code == 200
    # 第二次审批应失败
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/approve",
        json={"action": "approve", "approverId": "admin"},
    )
    assert resp.status_code == 409


def test_invalid_approval_action(client):
    aid = _list_asset(client)
    sid = _subscribe(client, aid)
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/approve",
        json={"action": "invalid", "approverId": "admin"},
    )
    assert resp.status_code == 422


# ---------- 订阅列表 ----------


def test_list_asset_subscriptions(client):
    aid = _list_asset(client)
    _subscribe(client, aid, subscriber_id="tenant-B")
    _subscribe(client, aid, subscriber_id="tenant-C")
    resp = client.get(f"/api/v1/assets/{aid}/subscriptions")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 2


def test_approve_increments_subscriber_count(client):
    """审批通过后资产订阅者数 +1."""
    aid = _list_asset(client)
    sid = _subscribe(client, aid)
    # 审批前
    resp = client.get(f"/api/v1/assets/{aid}")
    assert resp.json()["subscriberCount"] == 0
    # 审批
    client.post(
        f"/api/v1/asset-subscriptions/{sid}/approve",
        json={"action": "approve", "approverId": "admin"},
    )
    # 审批后
    resp = client.get(f"/api/v1/assets/{aid}")
    assert resp.json()["subscriberCount"] == 1


# ---------- 审批人身份取自 JWT ----------


class TestApproverIdentityFromToken:
    def test_approve_record_uses_token_identity_not_body(self, app, monkeypatch):
        """请求体伪造 approverId 不生效：订阅记录与审计日志均记 token 身份."""
        c = jwt_client(monkeypatch, app)
        aid = _list_asset(c, name="approver-identity-asset", headers=_admin_headers())
        sid = _subscribe(c, aid, subscriber_id="tenant-b", headers=_admin_headers())
        forger = make_token(sub="attacker", tenant="tenant-b", role="user")
        resp = c.post(
            f"/api/v1/asset-subscriptions/{sid}/approve",
            json={"action": "approve", "approverId": "platform-admin"},
            headers=auth_headers(forger),
        )
        assert resp.status_code == 200, resp.text
        assert resp.json()["approverId"] == "attacker"
        logs = c.get(f"/api/v1/assets/{aid}/audit-logs", headers=_admin_headers()).json()
        approve_logs = [log for log in logs if log["detail"].get("action") == "approve"]
        assert len(approve_logs) == 1
        assert approve_logs[0]["actorId"] == "attacker"
        spoofed = [log for log in logs if log["actorId"] == "platform-admin"]
        assert not spoofed

    def test_reject_record_uses_token_identity(self, app, monkeypatch):
        c = jwt_client(monkeypatch, app)
        aid = _list_asset(c, name="reject-identity-asset", headers=_admin_headers())
        sid = _subscribe(c, aid, subscriber_id="tenant-b", headers=_admin_headers())
        resp = c.post(
            f"/api/v1/asset-subscriptions/{sid}/approve",
            json={"action": "reject", "approverId": "someone-else", "reason": "policy"},
            headers=_admin_headers(),
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "rejected"
        assert body["approverId"] == "root"
        logs = c.get(f"/api/v1/assets/{aid}/audit-logs", headers=_admin_headers()).json()
        reject_logs = [log for log in logs if log["detail"].get("action") == "reject"]
        assert len(reject_logs) == 1
        assert reject_logs[0]["actorId"] == "root"
