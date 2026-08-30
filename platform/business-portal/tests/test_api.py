"""API 端点测试."""

from __future__ import annotations

import pytest


@pytest.fixture
def make_jwt():
    """签发 HS256 JWT（与 conftest.jwt_client 同密钥；本地定义避免顶层 tests 包名碰撞）."""
    import base64
    import hashlib
    import hmac
    import json
    import time

    secret = "unit-test-secret-key-at-least-32-bytes!!"

    def _enc(obj) -> str:
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()

    def _make(sub: str = "admin-1", tenant: str = "t-1", role: str = "admin") -> str:
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
            base64.urlsafe_b64encode(hmac.new(secret.encode(), si.encode(), hashlib.sha256).digest())
            .rstrip(b"=")
            .decode()
        )
        return f"{si}.{sig}"

    return _make


class TestHealth:
    """健康检查."""

    def test_health(self, client):
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "UP"
        assert data["store"] == "mock"
        assert data["module"] == "business-portal"
        assert data["level"] == "L5.4"


class TestAuthEnforcement:
    """鉴权强制测试：AUTH_MODE=jwt 下身份只认 Bearer token，裸头伪造无效."""

    def test_jwt_mode_missing_token_returns_401(self, jwt_client):
        resp = jwt_client.get("/api/v1/business-lines")
        assert resp.status_code == 401

    def test_jwt_mode_rejects_forged_headers_without_token(self, jwt_client):
        """伪造 X-Tenant-Id/X-User-Id 头而无有效 token → 仍 401（修复前的漏洞本体）."""
        resp = jwt_client.get(
            "/api/v1/business-lines",
            headers={"X-Tenant-Id": "t-1", "X-User-Id": "admin-1"},
        )
        assert resp.status_code == 401

    def test_jwt_mode_rejects_invalid_token(self, jwt_client):
        resp = jwt_client.get(
            "/api/v1/business-lines",
            headers={"Authorization": "Bearer not-a-jwt"},
        )
        assert resp.status_code == 401

    def test_jwt_mode_valid_token_passes(self, jwt_client, make_jwt):
        token = make_jwt(sub="admin-1", tenant="t-1")
        resp = jwt_client.get(
            "/api/v1/business-lines",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 200


class TestBusinessLineApi:
    """业务线管理 API 测试."""

    def _create_payload(self, name="风控线", tenant_id="t-1"):
        return {
            "name": name,
            "tenantId": tenant_id,
            "description": "测试业务线",
            "budget": {"total": 100000.0, "used": 30000.0, "cycle": "monthly", "softLimit": True},
            "config": {"dataIsolation": "strict", "permissionScope": "bl"},
            "ownerIds": ["admin-1"],
            "team$ownerIds": None,  # 占位
            "teamIds": ["team-1"],
            "memberIds": ["admin-1", "user-1"],
        }

    def test_create_business_line(self, client):
        payload = {
            "name": "风控线",
            "tenantId": "t-1",
            "ownerIds": ["admin-1"],
            "memberIds": ["admin-1", "user-1"],
        }
        resp = client.post("/api/v1/business-lines", json=payload)
        assert resp.status_code == 201, resp.text
        data = resp.json()
        assert data["name"] == "风控线"
        assert data["tenantId"] == "t-1"
        assert data["status"] == "active"
        assert "id" in data

    def test_create_duplicate_name_returns_409(self, client):
        payload = {"name": "风控线", "tenantId": "t-1", "ownerIds": ["a"]}
        client.post("/api/v1/business-lines", json=payload)
        resp = client.post("/api/v1/business-lines", json=payload)
        assert resp.status_code == 409

    def test_list_business_lines(self, jwt_client, make_jwt):
        t1_token = make_jwt(sub="u-0", tenant="t-1")
        auth = {"Authorization": f"Bearer {t1_token}"}
        jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "bl-1", "tenantId": "t-1", "memberIds": ["u-1"]},
            headers=auth,
        )
        jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "bl-2", "tenantId": "t-1", "memberIds": ["u-2"]},
            headers=auth,
        )
        jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "bl-3", "tenantId": "t-2", "memberIds": ["u-1"]},
            headers={"Authorization": f"Bearer {make_jwt(sub='u-0', tenant='t-2')}"},
        )
        # t-1 视角：仅本租户
        resp = jwt_client.get("/api/v1/business-lines", headers=auth)
        assert resp.status_code == 200
        assert len(resp.json()) == 2
        names = {bl["name"] for bl in resp.json()}
        assert names == {"bl-1", "bl-2"}
        # 按成员（本租户内进一步收窄）
        resp = jwt_client.get(
            "/api/v1/business-lines?memberId=u-1",
            headers=auth,
        )
        result = resp.json()
        assert len(result) == 1
        assert result[0]["name"] == "bl-1"

    def test_list_without_tenant_identity_returns_401(self, jwt_client, make_jwt):
        """token 无 tenantId 声明 → 401，不允许匿名枚举."""
        jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "bl-1", "tenantId": "t-1"},
            headers={"Authorization": f"Bearer {make_jwt(sub='u-0', tenant='t-1')}"},
        )
        resp = jwt_client.get(
            "/api/v1/business-lines",
            headers={"Authorization": f"Bearer {make_jwt(sub='u-0', tenant='')}"},
        )
        assert resp.status_code == 401

    def test_list_ignores_client_tenant_param(self, jwt_client, make_jwt):
        """客户端伪造 tenantId 参数不影响租户裁剪（以 token 声明为准）."""
        jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "bl-t1", "tenantId": "t-1"},
            headers={"Authorization": f"Bearer {make_jwt(sub='u-0', tenant='t-1')}"},
        )
        jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "bl-t2", "tenantId": "t-2"},
            headers={"Authorization": f"Bearer {make_jwt(sub='u-0', tenant='t-2')}"},
        )
        resp = jwt_client.get(
            "/api/v1/business-lines?tenantId=t-2",
            headers={"Authorization": f"Bearer {make_jwt(sub='u-0', tenant='t-1')}"},
        )
        assert resp.status_code == 200
        names = {bl["name"] for bl in resp.json()}
        assert names == {"bl-t1"}

    def test_get_business_line(self, jwt_client, make_jwt):
        auth = {"Authorization": f"Bearer {make_jwt(sub='admin-1', tenant='t-1')}"}
        create_resp = jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1", "memberIds": ["u-1"]},
            headers=auth,
        )
        bl_id = create_resp.json()["id"]
        resp = jwt_client.get(f"/api/v1/business-lines/{bl_id}", headers=auth)
        assert resp.status_code == 200
        assert resp.json()["id"] == bl_id

    def test_get_business_line_with_user_identity(self, jwt_client, make_jwt):
        """token sub 指定成员：成员可访问，非成员 403."""
        create_resp = jwt_client.post(
            "/api/v1/business-lines",
            json={
                "name": "风控线",
                "tenantId": "t-1",
                "ownerIds": ["admin-1"],
                "memberIds": ["admin-1", "user-1"],
            },
            headers={"Authorization": f"Bearer {make_jwt(sub='admin-1', tenant='t-1')}"},
        )
        bl_id = create_resp.json()["id"]
        # 成员访问
        resp = jwt_client.get(
            f"/api/v1/business-lines/{bl_id}",
            headers={"Authorization": f"Bearer {make_jwt(sub='user-1', tenant='t-1')}"},
        )
        assert resp.status_code == 200
        # 非成员访问
        resp = jwt_client.get(
            f"/api/v1/business-lines/{bl_id}",
            headers={"Authorization": f"Bearer {make_jwt(sub='intruder', tenant='t-1')}"},
        )
        assert resp.status_code == 403

    def test_get_business_line_not_found(self, client):
        resp = client.get("/api/v1/business-lines/nonexistent")
        assert resp.status_code == 404

    def test_update_business_line(self, jwt_client, make_jwt):
        auth = {"Authorization": f"Bearer {make_jwt(sub='admin-1', tenant='t-1')}"}
        create_resp = jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1", "ownerIds": ["admin-1"]},
            headers=auth,
        )
        bl_id = create_resp.json()["id"]
        resp = jwt_client.put(f"/api/v1/business-lines/{bl_id}", json={"name": "风控线-v2"}, headers=auth)
        assert resp.status_code == 200
        assert resp.json()["name"] == "风控线-v2"

    def test_update_by_non_owner_returns_403(self, jwt_client, make_jwt):
        create_resp = jwt_client.post(
            "/api/v1/business-lines",
            json={
                "name": "风控线",
                "tenantId": "t-1",
                "ownerIds": ["admin-1"],
                "memberIds": ["admin-1", "user-1"],
            },
            headers={"Authorization": f"Bearer {make_jwt(sub='admin-1', tenant='t-1')}"},
        )
        bl_id = create_resp.json()["id"]
        resp = jwt_client.put(
            f"/api/v1/business-lines/{bl_id}",
            json={"name": "x"},
            headers={"Authorization": f"Bearer {make_jwt(sub='user-1', tenant='t-1')}"},
        )
        assert resp.status_code == 403

    def test_delete_business_line(self, jwt_client, make_jwt):
        auth = {"Authorization": f"Bearer {make_jwt(sub='admin-1', tenant='t-1')}"}
        create_resp = jwt_client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
            headers=auth,
        )
        bl_id = create_resp.json()["id"]
        resp = jwt_client.delete(f"/api/v1/business-lines/{bl_id}", headers=auth)
        assert resp.status_code == 204
        # 二次获取应 404
        resp = jwt_client.get(f"/api/v1/business-lines/{bl_id}", headers=auth)
        assert resp.status_code == 404

    def test_delete_not_found(self, client):
        resp = client.delete("/api/v1/business-lines/nonexistent")
        assert resp.status_code == 404


class TestDashboardApi:
    """数据概览 API 测试."""

    def test_get_dashboard(self, client):
        create_resp = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        )
        bl_id = create_resp.json()["id"]
        resp = client.get(f"/api/v1/business-lines/{bl_id}/dashboard")
        assert resp.status_code == 200
        data = resp.json()
        assert data["blId"] == bl_id
        assert len(data["kpis"]) > 0
        assert len(data["trends"]) > 0
        assert len(data["realtime"]) > 0
        assert len(data["topProjects"]) > 0

    def test_get_dashboard_bl_not_found(self, client):
        resp = client.get("/api/v1/business-lines/nonexistent/dashboard")
        assert resp.status_code == 404


class TestWorkbenchApi:
    """工作台 API 测试."""

    def test_get_workbench(self, client):
        create_resp = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        )
        bl_id = create_resp.json()["id"]
        resp = client.get(f"/api/v1/business-lines/{bl_id}/workbench")
        assert resp.status_code == 200
        data = resp.json()
        assert data["blId"] == bl_id
        assert len(data["todos"]) > 0
        assert len(data["tools"]) > 0
        assert len(data["recentTasks"]) > 0


class TestCatalogApi:
    """数据目录 API 测试."""

    def test_get_catalog(self, client):
        create_resp = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        )
        bl_id = create_resp.json()["id"]
        resp = client.get(f"/api/v1/business-lines/{bl_id}/catalog")
        assert resp.status_code == 200
        data = resp.json()
        assert data["blId"] == bl_id
        assert len(data["nodes"]) > 0
        assert len(data["rootIds"]) > 0

    def test_add_catalog_node(self, client):
        create_resp = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        )
        bl_id = create_resp.json()["id"]
        # 先获取根节点 ID
        tree = client.get(f"/api/v1/business-lines/{bl_id}/catalog").json()
        root_id = tree["rootIds"][0]
        # 添加节点
        resp = client.post(
            f"/api/v1/business-lines/{bl_id}/catalog",
            json={
                "parentId": root_id,
                "name": "new_schema",
                "type": "schema",
            },
        )
        assert resp.status_code == 201, resp.text
        node = resp.json()
        assert node["blId"] == bl_id
        assert node["name"] == "new_schema"
        assert node["parentId"] == root_id

    def test_catalog_isolation_between_bl(self, client):
        """数据目录隔离：A 的目录与 B 完全独立."""
        bl_a = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        ).json()["id"]
        bl_b = client.post(
            "/api/v1/business-lines",
            json={"name": "增长线", "tenantId": "t-1"},
        ).json()["id"]
        tree_a = client.get(f"/api/v1/business-lines/{bl_a}/catalog").json()
        tree_b = client.get(f"/api/v1/business-lines/{bl_b}/catalog").json()
        ids_a = {n["id"] for n in tree_a["nodes"]}
        ids_b = {n["id"] for n in tree_b["nodes"]}
        assert ids_a.isdisjoint(ids_b)


class TestReportApi:
    """BI 报表 API 测试."""

    def test_create_and_list_report(self, client):
        bl_id = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        ).json()["id"]
        # 创建报表
        resp = client.post(
            f"/api/v1/business-lines/{bl_id}/reports",
            json={
                "name": "风控日报",
                "description": "风控线每日数据概览",
                "config": {"type": "chart", "chartType": "line"},
            },
        )
        assert resp.status_code == 201, resp.text
        report = resp.json()
        assert report["blId"] == bl_id
        assert report["name"] == "风控日报"

        # 列表
        resp = client.get(f"/api/v1/business-lines/{bl_id}/reports")
        assert resp.status_code == 200
        assert len(resp.json()) == 1

    def test_get_report(self, client):
        bl_id = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        ).json()["id"]
        report_id = client.post(
            f"/api/v1/business-lines/{bl_id}/reports",
            json={"name": "r1"},
        ).json()["id"]
        resp = client.get(f"/api/v1/business-lines/{bl_id}/reports/{report_id}")
        assert resp.status_code == 200
        assert resp.json()["id"] == report_id

    def test_update_report(self, client):
        bl_id = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        ).json()["id"]
        report_id = client.post(
            f"/api/v1/business-lines/{bl_id}/reports",
            json={"name": "r1"},
        ).json()["id"]
        resp = client.put(
            f"/api/v1/business-lines/{bl_id}/reports/{report_id}",
            json={"name": "r1-v2"},
        )
        assert resp.status_code == 200
        assert resp.json()["name"] == "r1-v2"

    def test_delete_report(self, client):
        bl_id = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        ).json()["id"]
        report_id = client.post(
            f"/api/v1/business-lines/{bl_id}/reports",
            json={"name": "r1"},
        ).json()["id"]
        resp = client.delete(f"/api/v1/business-lines/{bl_id}/reports/{report_id}")
        assert resp.status_code == 204
        # 二次获取应 404
        resp = client.get(f"/api/v1/business-lines/{bl_id}/reports/{report_id}")
        assert resp.status_code == 404

    def test_report_isolation_between_bl(self, client):
        """报表隔离：A 的报表在 B 不可见."""
        bl_a = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        ).json()["id"]
        bl_b = client.post(
            "/api/v1/business-lines",
            json={"name": "增长线", "tenantId": "t-1"},
        ).json()["id"]
        # 在 A 创建报表
        report_a_id = client.post(
            f"/api/v1/business-lines/{bl_a}/reports",
            json={"name": "r-a"},
        ).json()["id"]
        # B 列表为空
        reports_b = client.get(f"/api/v1/business-lines/{bl_b}/reports").json()
        assert len(reports_b) == 0
        # B 视角获取 A 的报表 → 404
        resp = client.get(f"/api/v1/business-lines/{bl_b}/reports/{report_a_id}")
        assert resp.status_code == 404

    def test_cross_bl_get_returns_404(self, client):
        """跨业务线获取报表返回 404（数据隔离）."""
        bl_a = client.post(
            "/api/v1/business-lines",
            json={"name": "风控线", "tenantId": "t-1"},
        ).json()["id"]
        bl_b = client.post(
            "/api/v1/business-lines",
            json={"name": "增长线", "tenantId": "t-1"},
        ).json()["id"]
        report_a_id = client.post(
            f"/api/v1/business-lines/{bl_a}/reports",
            json={"name": "r-a"},
        ).json()["id"]
        resp = client.get(f"/api/v1/business-lines/{bl_b}/reports/{report_a_id}")
        assert resp.status_code == 404


class TestOpenApi:
    """OpenAPI 文档测试."""

    def test_openapi_schema(self, client):
        resp = client.get("/openapi.json")
        assert resp.status_code == 200
        schema = resp.json()
        assert schema["info"]["title"] == "Business Portal"
        # 关键端点存在
        paths = schema["paths"]
        assert "/api/v1/health" in paths
        assert "/api/v1/business-lines" in paths
        assert "/api/v1/business-lines/{bl_id}/dashboard" in paths
        assert "/api/v1/business-lines/{bl_id}/workbench" in paths
        assert "/api/v1/business-lines/{bl_id}/catalog" in paths
        assert "/api/v1/business-lines/{bl_id}/reports" in paths

    def test_docs_page(self, client):
        resp = client.get("/docs")
        assert resp.status_code == 200
