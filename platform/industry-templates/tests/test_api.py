"""API 端点测试."""
from __future__ import annotations


# ---------- health ----------

def test_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["deployMode"] == "mock"
    assert body["templateCount"] == 3


# ---------- 列表 ----------

def test_list_templates(client):
    resp = client.get("/api/v1/templates")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 3
    ids = {t["id"] for t in body}
    assert ids == {
        "fin-risk-scorecard",
        "retail-user-profile",
        "mfg-quality-inspection",
    }


def test_list_templates_filter_industry(client):
    resp = client.get("/api/v1/templates", params={"industry": "finance"})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 1
    assert body[0]["id"] == "fin-risk-scorecard"


def test_list_templates_filter_keyword(client):
    resp = client.get("/api/v1/templates", params={"keyword": "风控"})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 1
    assert body[0]["id"] == "fin-risk-scorecard"


# ---------- 详情 ----------

def test_get_template(client):
    resp = client.get("/api/v1/templates/fin-risk-scorecard")
    assert resp.status_code == 200
    body = resp.json()
    assert body["meta"]["id"] == "fin-risk-scorecard"
    assert body["meta"]["name"] == "风控评分卡"
    assert len(body["parameters"]) > 0
    assert len(body["dataFlow"]["nodes"]) > 0
    assert len(body["computeLogic"]["steps"]) > 0
    assert len(body["visualization"]["panels"]) > 0


def test_get_template_not_found(client):
    resp = client.get("/api/v1/templates/nonexistent")
    assert resp.status_code == 404


# ---------- 部署 ----------

def _deploy_fin(client, tenant_id="tenant-001"):
    return client.post(
        "/api/v1/templates/fin-risk-scorecard/deploy",
        json={
            "tenantId": tenant_id,
            "releaseName": "test-release",
            "values": {
                "datasource.order_db": "jdbc:mysql://order:3306/order",
                "datasource.user_db": "jdbc:mysql://user:3306/user",
            },
        },
    )


def test_deploy_template(client):
    resp = _deploy_fin(client)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["templateId"] == "fin-risk-scorecard"
    assert body["status"] == "running"
    assert body["jobRunId"] is not None
    assert body["dashboardSnapshotUrl"] is not None


def test_deploy_not_found(client):
    resp = client.post(
        "/api/v1/templates/nonexistent/deploy",
        json={
            "tenantId": "t1",
            "releaseName": "r1",
            "values": {},
        },
    )
    assert resp.status_code == 404


def test_deploy_missing_required(client):
    """缺少必填参数返回 422."""
    resp = client.post(
        "/api/v1/templates/fin-risk-scorecard/deploy",
        json={
            "tenantId": "t1",
            "releaseName": "r1",
            "values": {},
        },
    )
    assert resp.status_code == 422


def test_deploy_retail(client):
    resp = client.post(
        "/api/v1/templates/retail-user-profile/deploy",
        json={
            "tenantId": "tenant-002",
            "releaseName": "retail-rel",
            "values": {
                "datasource.trade_db": "jdbc:mysql://trade:3306/trade",
                "datasource.user_db": "jdbc:mysql://user:3306/user",
                "datasource.behavior_log": "/data/log",
            },
        },
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["status"] == "running"


def test_deploy_mfg(client):
    resp = client.post(
        "/api/v1/templates/mfg-quality-inspection/deploy",
        json={
            "tenantId": "tenant-003",
            "releaseName": "mfg-rel",
            "values": {
                "datasource.image_stream": "kafka://img",
                "datasource.mes_db": "jdbc:mysql://mes:3306/mes",
                "datasource.iotdb": "iotdb://localhost:6667",
            },
        },
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["status"] == "running"


# ---------- 预览 ----------

def test_preview_template(client):
    resp = client.get("/api/v1/templates/fin-risk-scorecard/preview")
    assert resp.status_code == 200
    body = resp.json()
    assert body["templateId"] == "fin-risk-scorecard"
    assert body["templateName"] == "风控评分卡"
    assert body["industry"] == "finance"
    assert body["stats"]["dataFlowNodes"] > 0
    assert len(body["architecture"]["nodes"]) > 0


def test_preview_not_found(client):
    resp = client.get("/api/v1/templates/nonexistent/preview")
    assert resp.status_code == 404


# ---------- 分类 ----------

def test_categories(client):
    resp = client.get("/api/v1/templates/categories")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 3
    industries = {c["industry"] for c in body}
    assert industries == {"finance", "retail", "manufacturing"}


# ---------- 部署记录 ----------

def test_list_deployments(client):
    _deploy_fin(client, tenant_id="tenant-x")
    resp = client.get(
        "/api/v1/templates/fin-risk-scorecard/deployments",
        params={"tenantId": "tenant-x"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) >= 1
    for r in body:
        assert r["tenantId"] == "tenant-x"


# ---------- docs ----------

def test_openapi_docs_accessible(client):
    """FastAPI 自动文档可访问."""
    resp = client.get("/docs")
    assert resp.status_code == 200

    resp = client.get("/openapi.json")
    assert resp.status_code == 200
    spec = resp.json()
    assert spec["info"]["title"] == "Industry Templates Platform"
    paths = spec["paths"]
    assert "/api/v1/templates" in paths
    assert "/api/v1/templates/{template_id}" in paths
    assert "/api/v1/templates/{template_id}/deploy" in paths
    assert "/api/v1/templates/{template_id}/preview" in paths
    assert "/api/v1/templates/categories" in paths
    assert "/health" in paths