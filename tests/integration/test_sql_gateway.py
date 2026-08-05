"""统一 SQL 网关（sql-gateway）集成测试。

被测对象：``platform/sql-gateway``，Java/Spring Boot，默认端口 8081。

覆盖端点：
- GET  /api/v1/health
- GET  /api/v1/sql/engines
- GET  /api/v1/sql/routes
- POST /api/v1/sql/routes
- POST /api/v1/sql/execute

设计要点：
- 引擎列表应固定为 ``["trino", "doris"]``；
- 路由规则在 MVP 阶段为内存存储，添加后会出现在列表中；
- SQL 执行返回 SIMULATED 模拟结果，校验关键字段。
"""

from __future__ import annotations


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(api_client, sql_gateway_url):
    """验证 SQL 网关健康检查端点返回 200 且 status=UP。"""
    resp = api_client.get(sql_gateway_url + "/api/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"


# ---------------------------------------------------------------------------
# 引擎与路由
# ---------------------------------------------------------------------------
def test_list_engines(api_client, sql_gateway_url):
    """验证 GET /api/v1/sql/engines 返回 ["trino", "doris"]。"""
    resp = api_client.get(sql_gateway_url + "/api/v1/sql/engines")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)
    # 顺序不强制，但两个引擎都必须存在。
    assert set(body) == {"trino", "doris"}


def test_list_routes(api_client, sql_gateway_url):
    """验证 GET /api/v1/sql/routes 返回 200 且为列表。"""
    resp = api_client.get(sql_gateway_url + "/api/v1/sql/routes")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)


def test_add_route(api_client, sql_gateway_url):
    """验证 POST /api/v1/sql/routes 添加路由规则返回 201/200，且可在列表中查到。"""
    payload = {
        "pattern": "INSERT INTO",
        "engine": "doris",
        "priority": 10,
        "enabled": True,
    }
    resp = api_client.post(sql_gateway_url + "/api/v1/sql/routes", json=payload)
    # 兼容 201 与 200 两种成功状态码。
    assert resp.status_code in (200, 201)
    body = resp.json()
    assert body.get("engine") == "doris"
    assert body.get("pattern") == "INSERT INTO"

    # 验证列表中包含刚添加的路由。
    list_resp = api_client.get(sql_gateway_url + "/api/v1/sql/routes")
    assert list_resp.status_code == 200
    routes = list_resp.json()
    assert any(r.get("pattern") == "INSERT INTO" for r in routes)


# ---------------------------------------------------------------------------
# SQL 执行
# ---------------------------------------------------------------------------
def test_execute_sql(api_client, sql_gateway_url):
    """验证 POST /api/v1/sql/execute 执行 SQL 返回 200 且包含 queryId/status/engine。"""
    payload = {
        "sql": "SELECT count(*) FROM orders WHERE dt = '2024-01-01'",
        "engine": "trino",
        "tenantId": "it-test",
        "limit": 100,
    }
    resp = api_client.post(sql_gateway_url + "/api/v1/sql/execute", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert "queryId" in body
    assert "status" in body
    assert body.get("engine") == "trino"
    # MVP 阶段为 SIMULATED，但允许未来真实执行返回 SUCCESS；后端不可用时返回 DEGRADED。
    assert body.get("status") in ("SIMULATED", "SUCCESS", "OK", "DEGRADED")


def test_execute_sql_with_doris(api_client, sql_gateway_url):
    """验证显式指定 engine=doris 时，响应中 engine 字段为 doris。"""
    payload = {
        "sql": "SELECT * FROM dashboard_metrics LIMIT 10",
        "engine": "doris",
        "tenantId": "it-test",
    }
    resp = api_client.post(sql_gateway_url + "/api/v1/sql/execute", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("engine") == "doris"


def test_sql_execution_flow(api_client, sql_gateway_url):
    """端到端 SQL 执行流程：添加路由 → 执行命中路由的 SQL → 校验引擎路由结果。

    场景：添加一条 ``pattern=INSERT INTO, engine=doris`` 的路由，
    然后执行一条 INSERT 语句（不显式指定 engine），期望被路由到 doris。
    """
    # 1. 添加路由规则（若已存在则忽略）。
    route_payload = {
        "pattern": "INSERT INTO it_flow",
        "engine": "doris",
        "priority": 5,
        "enabled": True,
    }
    add_resp = api_client.post(
        sql_gateway_url + "/api/v1/sql/routes", json=route_payload
    )
    assert add_resp.status_code in (200, 201)

    # 2. 执行命中路由的 SQL（不显式指定 engine，由路由规则决定）。
    exec_payload = {
        "sql": "INSERT INTO it_flow VALUES (1, 'a')",
        "tenantId": "it-flow",
    }
    exec_resp = api_client.post(
        sql_gateway_url + "/api/v1/sql/execute", json=exec_payload
    )
    assert exec_resp.status_code == 200
    body = exec_resp.json()
    assert "queryId" in body
    # 路由命中后应使用 doris；若实现未严格按路由，至少应有 engine 字段。
    assert "engine" in body
    # 期望路由到 doris（MVP 实现可能返回 SIMULATED，但 engine 应为 doris）。
    assert body.get("engine") == "doris"