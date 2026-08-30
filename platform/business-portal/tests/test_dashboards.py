"""BI 看板路由测试（/api/v1/dashboards，前端 analyze.ts 契约）.

覆盖：
- CRUD 全流程（创建/详情/列表分页/更新/删除/404）
- keyword 模糊过滤
- 面板数据原样存取（服务端不篡改数据）
- JWT 鉴权强制（AUTH_MODE=jwt 下无 token 401）
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient
import pytest

SECRET = "unit-test-secret-key-at-least-32-bytes!!"


def make_jwt(sub: str = "u1", tenant: str = "t-1") -> str:
    """签发 HS256 JWT（本地定义，避免顶层 tests 包名碰撞）."""
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "iss": "shuqing-bigdata",
        "sub": sub,
        "tenantId": tenant,
        "role": "admin",
        "iat": int(time.time()),
        "exp": int(time.time()) + 600,
    }

    def _enc(obj) -> str:
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()

    si = f"{_enc(header)}.{_enc(claims)}"
    sig = base64.urlsafe_b64encode(
        hmac.new(SECRET.encode(), si.encode(), hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    return f"{si}.{sig}"


@pytest.fixture
def dash_client(app, monkeypatch) -> TestClient:
    """匿名放行（测试环境），专注 CRUD 行为；鉴权由专用用例覆盖."""
    monkeypatch.setenv("AUTH_MODE", "none")
    return TestClient(app)


@pytest.fixture
def jwt_dash_client(app, monkeypatch) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", "unit-test-secret-key-at-least-32-bytes!!")
    return TestClient(app)


def _mk_payload(name: str = "零售 GMV 看板", panels: list | None = None) -> dict:
    return {
        "name": name,
        "description": "月度 GMV 趋势与渠道占比",
        "panels": panels if panels is not None else [
            {
                "id": "p1",
                "title": "GMV 趋势",
                "type": "line",
                "config": {"xField": "month", "yField": "gmv"},
                "data": {"rows": [{"month": "1月", "gmv": 120}]},
            }
        ],
    }


class TestDashboardCrud:
    def test_create_returns_201_with_fields(self, dash_client):
        resp = dash_client.post("/api/v1/dashboards", json=_mk_payload())
        assert resp.status_code == 201, resp.text
        body = resp.json()
        assert body["name"] == "零售 GMV 看板"
        assert len(body["panels"]) == 1
        assert body["panels"][0]["data"]["rows"][0]["gmv"] == 120
        assert body["createdAt"] and body["updatedAt"]

    def test_get_detail_and_404(self, dash_client):
        did = dash_client.post("/api/v1/dashboards", json=_mk_payload()).json()["id"]
        assert dash_client.get(f"/api/v1/dashboards/{did}").status_code == 200
        assert dash_client.get("/api/v1/dashboards/nonexistent").status_code == 404

    def test_list_pagination_and_keyword(self, dash_client):
        for i in range(5):
            dash_client.post("/api/v1/dashboards", json=_mk_payload(name=f"看板-{i}"))
        dash_client.post("/api/v1/dashboards", json=_mk_payload(name="专项-风控"))

        resp = dash_client.get("/api/v1/dashboards", params={"page": 1, "pageSize": 3})
        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 6
        assert len(body["list"]) == 3
        assert body["page"] == 1 and body["pageSize"] == 3

        resp = dash_client.get("/api/v1/dashboards", params={"keyword": "专项"})
        assert resp.json()["total"] == 1
        assert resp.json()["list"][0]["name"].startswith("专项")

    def test_update_name_and_panels(self, dash_client):
        did = dash_client.post("/api/v1/dashboards", json=_mk_payload()).json()["id"]
        resp = dash_client.put(
            f"/api/v1/dashboards/{did}",
            json={"name": "改名看板", "panels": []},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["name"] == "改名看板"
        assert body["panels"] == []

    def test_update_404(self, dash_client):
        resp = dash_client.put(
            "/api/v1/dashboards/nonexistent", json={"name": "x"}
        )
        assert resp.status_code == 404

    def test_delete_then_404(self, dash_client):
        did = dash_client.post("/api/v1/dashboards", json=_mk_payload()).json()["id"]
        assert dash_client.delete(f"/api/v1/dashboards/{did}").status_code == 204
        assert dash_client.get(f"/api/v1/dashboards/{did}").status_code == 404

    def test_panel_data_roundtrip_untouched(self, dash_client):
        """服务端不篡改面板数据（前端渲染以创建者数据为准）."""
        rows = [{"month": f"{m}月", "gmv": 100 + m} for m in range(1, 7)]
        did = dash_client.post(
            "/api/v1/dashboards", json=_mk_payload(panels=[
                {"id": "p1", "title": "GMV", "type": "line", "config": {}, "data": {"rows": rows}}
            ])
        ).json()["id"]
        got = dash_client.get(f"/api/v1/dashboards/{did}").json()
        assert got["panels"][0]["data"]["rows"] == rows


class TestDashboardAuth:
    def test_jwt_mode_requires_token(self, jwt_dash_client):
        assert jwt_dash_client.get("/api/v1/dashboards").status_code == 401

    def test_jwt_mode_valid_token_ok(self, jwt_dash_client):
        token = make_jwt(sub="u1", tenant="t-1")
        resp = jwt_dash_client.get(
            "/api/v1/dashboards",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 200


class TestRealtimeEndpoint:
    def test_realtime_returns_list(self, dash_client):
        """实时指标端点存在且返回数组（无指标源时为空数组，不报错）."""
        resp = dash_client.get("/api/v1/dashboards/realtime")
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)
