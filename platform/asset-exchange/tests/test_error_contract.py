"""HTTP 错误契约：稳定机器码 + 人读 message + 可选结构化 details."""

from __future__ import annotations

from fastapi.testclient import TestClient
from test_jwt_auth import (
    admin_token,
    auth_headers,
    jwt_client,
    list_asset_as_admin,
    user_token,
)


def test_unauthorized_uses_stable_code(app, monkeypatch) -> None:
    c = jwt_client(monkeypatch, app)
    resp = c.get("/api/v1/assets")
    assert resp.status_code == 401
    body = resp.json()
    assert body["error"] == "UNAUTHORIZED"
    assert "message" in body
    assert "detail" not in body
    assert "缺少" not in body["error"]


def test_forbidden_code_is_stable(app, monkeypatch) -> None:
    c = jwt_client(monkeypatch, app)
    aid = list_asset_as_admin(
        c,
        name="error-contract-forbidden",
        owner="tenant-b",
    )
    resp = c.post(
        f"/api/v1/assets/{aid}/publish",
        headers=auth_headers(user_token("tenant-b")),
    )
    assert resp.status_code == 403, resp.text
    body = resp.json()
    assert body == {"error": "FORBIDDEN", "message": "需要 admin 角色"}


def test_route_404_uses_same_envelope(app, monkeypatch) -> None:
    c = jwt_client(monkeypatch, app)
    resp = c.get("/api/v1/definitely-not-a-route")
    assert resp.status_code == 404
    assert resp.json() == {"error": "NOT_FOUND", "message": "Not Found"}


def test_conflict_code_is_stable(app, monkeypatch) -> None:
    c = jwt_client(monkeypatch, app)
    list_asset_as_admin(c, name="动态资产名-不应进入机器码", owner="tenant-b")
    # 第二次注册同名资产应返回 409。
    resp = c.post(
        "/api/v1/assets",
        json={
            "name": "动态资产名-不应进入机器码",
            "type": "table",
            "owner": "tenant-b",
            "qualityScore": 85.0,
        },
        headers=auth_headers(admin_token()),
    )
    assert resp.status_code == 409, resp.text
    body = resp.json()
    assert body["error"] == "CONFLICT"
    assert "动态资产名" in body["message"]
    assert "动态资产名" not in body["error"]
    assert "detail" not in body


def test_validation_error_envelope(app, monkeypatch) -> None:
    c = jwt_client(monkeypatch, app)
    resp = c.post(
        "/api/v1/assets/register",
        json={"name": "bad-type", "type": "not-a-real-type"},
        headers=auth_headers(admin_token()),
    )
    assert resp.status_code == 422, resp.text
    body = resp.json()
    assert body["error"] == "VALIDATION_ERROR"
    assert body["message"] == "请求参数校验失败"
    assert isinstance(body["details"], list) and body["details"]
    assert "detail" not in body


def test_unhandled_exception_does_not_leak_internal_detail(app) -> None:
    secret = "jdbc:mysql://internal-db:3306/private?password=must-not-leak"

    @app.get("/_test/unhandled-error")
    async def unhandled_error() -> None:
        raise RuntimeError(secret)

    c = TestClient(app, raise_server_exceptions=False)
    resp = c.get("/_test/unhandled-error")
    assert resp.status_code == 500
    assert resp.json() == {
        "error": "INTERNAL_ERROR",
        "message": "服务器内部错误，请联系管理员",
    }
    assert secret not in resp.text
    assert "must-not-leak" not in resp.text
