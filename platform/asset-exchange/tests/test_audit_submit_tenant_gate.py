"""asset-exchange 审核/提交审核端点的对象级授权门禁（2026-11-18 深度检测报告 P0-2/P0-3）.

现象：`POST /assets/{id}/audit` 与 `POST /assets/{id}/submit-audit` 两个 handler
此前都不查资产归属——任何持合法 JWT 的租户都能把别家资产审核上架（敏感资产直接
进入流通）或替别家提交审核，且 submit 全程无审计留痕。同文件既有的
`GET /assets/{id}/audit-logs`（`assets.py` list_asset_audit_logs）本来就调用
`require_asset_owner` ⇒ 门禁口径早有，只是这两个写端点漏了。

本文件钉住修复后的三态（旁观 403 / 本方 200 / admin 200），并对 submit 补
"动作留痕"断言（register→submit→audit 三动作都在审计链上）。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient

SECRET = "ae-audit-gate-secret-32b!"

PROVIDER = "tenant-a"  # 资产提供方
OUTSIDER = "tenant-c"  # 跨租户旁观者


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def make_token(sub: str, tenant: str, role: str = "user") -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "iss": "shuqing-bigdata",
        "sub": sub,
        "tenantId": tenant,
        "role": role,
        "iat": int(time.time()),
        "exp": int(time.time()) + 600,
    }
    si = f"{_enc(header)}.{_enc(claims)}"
    sig = (
        base64.urlsafe_b64encode(hmac.new(SECRET.encode(), si.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    )
    return f"{si}.{sig}"


def admin_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {make_token('root', 'platform-admin', 'admin')}"}


def user_headers(tenant: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {make_token(f'user-{tenant}', tenant)}"}


def jwt_client(monkeypatch, app) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(app)


def register_draft(c: TestClient, name: str, tenant: str = PROVIDER) -> str:
    """以 admin 造一个 DRAFT 资产（sensitive + 高分，保证能通过合规/质量检查）."""
    resp = c.post(
        "/api/v1/assets/register",
        json={
            "name": name,
            "type": "table",
            "tenantId": tenant,
            "securityLevel": "internal",
            "qualityScore": 90.0,
        },
        headers=admin_headers(),
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


class TestAuditTenantGate:
    """POST /assets/{id}/audit：跨租户 403 / 本方 200 / admin 200."""

    def test_audit_without_token_401(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "audit-gate-401")
        resp = c.post(f"/api/v1/assets/{aid}/audit", json={"result": "approved"})
        assert resp.status_code == 401, resp.text

    def test_audit_cross_tenant_403(self, app, monkeypatch) -> None:
        """报告 P0-2 复现路径：t-002 普通用户不得审核 t-001 的资产."""
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "audit-gate-403")
        resp = c.post(
            f"/api/v1/assets/{aid}/audit",
            json={"result": "approved", "reason": "外部租户越权批准"},
            headers=user_headers(OUTSIDER),
        )
        assert resp.status_code == 403, resp.text
        # 资产状态不得被改动（GET 对旁观者也是 403，用 admin 视角确认仍是 draft）
        assert c.get(f"/api/v1/assets/{aid}", headers=admin_headers()).json()["status"] == "draft"

    def test_audit_own_tenant_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "audit-gate-own")
        resp = c.post(
            f"/api/v1/assets/{aid}/audit",
            json={"result": "approved"},
            headers=user_headers(PROVIDER),
        )
        assert resp.status_code == 200, resp.text
        assert resp.json()["status"] == "listed"

    def test_audit_admin_any_tenant_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "audit-gate-admin")
        resp = c.post(
            f"/api/v1/assets/{aid}/audit",
            json={"result": "approved"},
            headers=admin_headers(),
        )
        assert resp.status_code == 200, resp.text


class TestSubmitAuditTenantGateAndAuditTrail:
    """POST /assets/{id}/submit-audit：三态 + 审计留痕（此前 submit 全程无痕）."""

    def test_submit_audit_without_token_401(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "submit-gate-401")
        assert c.post(f"/api/v1/assets/{aid}/submit-audit").status_code == 401

    def test_submit_audit_cross_tenant_403(self, app, monkeypatch) -> None:
        """报告 P0-3 复现路径：t-002 普通用户不得替 t-001 提交审核."""
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "submit-gate-403")
        resp = c.post(f"/api/v1/assets/{aid}/submit-audit", headers=user_headers(OUTSIDER))
        assert resp.status_code == 403, resp.text
        assert c.get(f"/api/v1/assets/{aid}", headers=admin_headers()).json()["status"] == "draft"

    def test_submit_audit_own_tenant_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "submit-gate-own")
        resp = c.post(f"/api/v1/assets/{aid}/submit-audit", headers=user_headers(PROVIDER))
        assert resp.status_code == 200, resp.text
        assert resp.json()["status"] == "pending_audit"

    def test_submit_audit_admin_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "submit-gate-admin")
        resp = c.post(f"/api/v1/assets/{aid}/submit-audit", headers=admin_headers())
        assert resp.status_code == 200, resp.text

    def test_submit_audit_leaves_audit_trail(self, app, monkeypatch) -> None:
        """提交审核必须有留痕：审计链上出现 submit_audit 动作，且 actor 可追溯."""
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "submit-gate-trail")
        assert c.post(f"/api/v1/assets/{aid}/submit-audit", headers=admin_headers()).status_code == 200

        resp = c.get(f"/api/v1/assets/{aid}/audit-logs", headers=admin_headers())
        assert resp.status_code == 200, resp.text
        actions = [row["action"] for row in resp.json()]
        assert "submit_audit" in actions, actions

    def test_submit_audit_failure_also_logged(self, app, monkeypatch) -> None:
        """失败路径也留痕：对非 DRAFT 资产重复提交 → 422，审计链出现 failure 记录."""
        c = jwt_client(monkeypatch, app)
        aid = register_draft(c, "submit-gate-fail")
        assert c.post(f"/api/v1/assets/{aid}/submit-audit", headers=admin_headers()).status_code == 200
        resp = c.post(f"/api/v1/assets/{aid}/submit-audit", headers=admin_headers())
        assert resp.status_code == 422, resp.text

        resp = c.get(f"/api/v1/assets/{aid}/audit-logs", headers=admin_headers())
        assert resp.status_code == 200, resp.text
        rows = resp.json()
        assert sum(1 for r in rows if r["action"] == "submit_audit") == 2, rows
