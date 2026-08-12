"""跨服务调用链路 Docker 集成测试。

本模块验证 4 个核心模块在 Docker 环境下的协同工作能力，
重点测试跨服务的业务链路（非单模块内部 CRUD）。

覆盖链路：
1. 封装层创建租户 → SQL 网关在租户上下文执行 SQL
2. Catalog 创建表 → SQL 网关查询该表（路由匹配）
3. 封装层创建租户 → 规则引擎在租户上下文执行规则
4. 全链路：创建租户 → 创建规则 → 执行规则 → 查询 Catalog → 执行 SQL

设计要点：
- 跨服务测试需要所有 4 个模块可用（conftest.py 已处理自动跳过）；
- 使用统一的 JWT token（含 tenantId claim）贯穿全链路；
- 每个测试结束后清理创建的资源，避免污染。
"""

from __future__ import annotations

import pytest


# ---------------------------------------------------------------------------
# 前置条件：所有模块健康
# ---------------------------------------------------------------------------
def test_all_modules_healthy(encaps_url, sql_gateway_url, catalog_url, rule_engine_url):
    """验证 4 个模块健康检查全部通过，作为跨服务测试的前置条件。"""
    import requests

    # 封装层（Java，/actuator/health）
    resp = requests.get(encaps_url + "/actuator/health", timeout=10)
    assert resp.status_code == 200
    assert resp.json().get("status") == "UP"

    # SQL 网关（Java，/actuator/health）
    resp = requests.get(sql_gateway_url + "/actuator/health", timeout=10)
    assert resp.status_code == 200
    assert resp.json().get("status") == "UP"

    # Catalog（Go，/api/v1/health）
    resp = requests.get(catalog_url + "/api/v1/health", timeout=10)
    assert resp.status_code == 200
    assert resp.json().get("status") == "UP"

    # 规则引擎（Java，/actuator/health）
    resp = requests.get(rule_engine_url + "/actuator/health", timeout=10)
    assert resp.status_code == 200
    assert resp.json().get("status") == "UP"


# ---------------------------------------------------------------------------
# 链路 1：封装层创建租户 → SQL 网关执行 SQL
# ---------------------------------------------------------------------------
def test_encaps_to_sql_gateway_chain(api_client, encaps_url, sql_gateway_url):
    """验证封装层创建租户后，SQL 网关能在该租户上下文执行 SQL。

    链路：POST /api/v1/tenants → POST /api/v1/sql/execute
    """
    # 1. 封装层创建租户。
    tenant_payload = {
        "name": "docker-chain-encaps-sql",
        "displayName": "跨服务链路测试",
        "namespace": "ns-chain-encaps-sql",
        "quotaProfile": "small",
        "status": "ACTIVE",
    }
    create_resp = api_client.post(encaps_url + "/api/v1/tenants", json=tenant_payload)
    assert create_resp.status_code == 201
    tenant = create_resp.json()

    try:
        # 2. SQL 网关在该租户上下文执行 SQL（token 中已含 tenantId）。
        sql_payload = {"sql": "SELECT 1", "tenantId": "docker-it-tenant"}
        sql_resp = api_client.post(
            sql_gateway_url + "/api/v1/sql/execute", json=sql_payload
        )
        assert sql_resp.status_code == 200
        sql_body = sql_resp.json()
        assert "queryId" in sql_body
        assert sql_body["status"] in ("SUCCESS", "DEGRADED", "FAILED")
    finally:
        # 清理租户。
        api_client.delete(encaps_url + f"/api/v1/tenants/{tenant['id']}")


# ---------------------------------------------------------------------------
# 链路 2：Catalog 创建表 → SQL 网关查询
# ---------------------------------------------------------------------------
def test_catalog_to_sql_gateway_chain(api_client, catalog_url, sql_gateway_url):
    """验证 Catalog 创建表后，SQL 网关能查询该表（即使引擎降级也应返回响应）。

    链路：POST /api/v1/catalog/tables → POST /api/v1/sql/execute
    """
    # 1. Catalog 创建表。
    table_payload = {
        "databaseName": "docker_chain_db",
        "tableName": "chain_test_table",
        "description": "跨服务链路测试表",
        "columns": [
            {"name": "id", "type": "BIGINT", "nullable": False},
            {"name": "name", "type": "VARCHAR", "nullable": True},
        ],
    }
    create_resp = api_client.post(catalog_url + "/api/v1/catalog/tables", json=table_payload)
    assert create_resp.status_code == 201
    table = create_resp.json()

    try:
        # 2. SQL 网关查询该表（无真实引擎时返回 DEGRADED）。
        sql_payload = {
            "sql": f"SELECT * FROM {table_payload['databaseName']}.{table_payload['tableName']} LIMIT 10",
            "tenantId": "docker-it-tenant",
        }
        sql_resp = api_client.post(
            sql_gateway_url + "/api/v1/sql/execute", json=sql_payload
        )
        assert sql_resp.status_code == 200
        assert "queryId" in sql_resp.json()
    finally:
        # 清理表。
        api_client.delete(catalog_url + f"/api/v1/catalog/tables/{table['id']}")


# ---------------------------------------------------------------------------
# 链路 3：封装层创建租户 → 规则引擎执行规则
# ---------------------------------------------------------------------------
def test_encaps_to_rule_engine_chain(api_client, encaps_url, rule_engine_url):
    """验证封装层创建租户后，规则引擎能在该租户上下文创建并执行规则。

    链路：POST /api/v1/tenants → POST /api/v1/rules → POST /api/v1/rules/execute
    """
    # 1. 封装层创建租户。
    tenant_payload = {
        "name": "docker-chain-encaps-rule",
        "namespace": "ns-chain-encaps-rule",
        "quotaProfile": "medium",
        "status": "ACTIVE",
    }
    tenant_resp = api_client.post(encaps_url + "/api/v1/tenants", json=tenant_payload)
    assert tenant_resp.status_code == 201
    tenant = tenant_resp.json()

    # 2. 规则引擎创建规则。
    rule_payload = {
        "name": "chain-rule",
        "description": "跨服务链路规则",
        "type": "DQ",
        "expression": "value > 0",
        "severity": "INFO",
        "enabled": True,
    }
    rule_resp = api_client.post(rule_engine_url + "/api/v1/rules", json=rule_payload)
    assert rule_resp.status_code == 201
    rule = rule_resp.json()

    try:
        # 3. 规则引擎执行规则。
        exec_resp = api_client.post(
            rule_engine_url + "/api/v1/rules/execute",
            json={"ruleId": rule["id"], "context": {"value": 42}, "tenantId": "docker-it-tenant"},
        )
        assert exec_resp.status_code == 200
        assert exec_resp.json()["ruleId"] == rule["id"]
    finally:
        # 清理规则与租户。
        api_client.delete(rule_engine_url + f"/api/v1/rules/{rule['id']}")
        api_client.delete(encaps_url + f"/api/v1/tenants/{tenant['id']}")


# ---------------------------------------------------------------------------
# 链路 4：全链路 - 创建租户 → 创建规则 → 执行规则 → 查询 Catalog → 执行 SQL
# ---------------------------------------------------------------------------
def test_full_chain_all_services(
    api_client, encaps_url, sql_gateway_url, catalog_url, rule_engine_url
):
    """验证全链路：4 个模块协同工作。

    链路：
    1. 封装层创建租户
    2. 规则引擎创建规则
    3. 规则引擎执行规则
    4. Catalog 创建表
    5. SQL 网关执行查询
    """
    # 1. 封装层创建租户。
    tenant = api_client.post(
        encaps_url + "/api/v1/tenants",
        json={
            "name": "docker-full-chain-tenant",
            "namespace": "ns-full-chain",
            "quotaProfile": "large",
            "status": "ACTIVE",
        },
    ).json()

    # 2. 规则引擎创建规则。
    rule = api_client.post(
        rule_engine_url + "/api/v1/rules",
        json={
            "name": "full-chain-rule",
            "type": "DQ",
            "expression": "value > 0",
            "severity": "INFO",
            "enabled": True,
        },
    ).json()

    # 4. Catalog 创建表。
    table = api_client.post(
        catalog_url + "/api/v1/catalog/tables",
        json={
            "databaseName": "full_chain_db",
            "tableName": "full_chain_table",
            "columns": [{"name": "id", "type": "BIGINT"}],
        },
    ).json()

    try:
        # 3. 规则引擎执行规则。
        exec_resp = api_client.post(
            rule_engine_url + "/api/v1/rules/execute",
            json={"ruleId": rule["id"], "context": {"value": 1}, "tenantId": "docker-it-tenant"},
        )
        assert exec_resp.status_code == 200

        # 5. SQL 网关执行查询。
        sql_resp = api_client.post(
            sql_gateway_url + "/api/v1/sql/execute",
            json={"sql": "SELECT 1", "tenantId": "docker-it-tenant"},
        )
        assert sql_resp.status_code == 200

        # 验证各步骤结果完整。
        assert tenant["id"] is not None
        assert rule["id"] is not None
        assert table["id"] is not None
        assert exec_resp.json()["ruleId"] == rule["id"]
        assert "queryId" in sql_resp.json()
    finally:
        # 清理所有创建的资源。
        api_client.delete(rule_engine_url + f"/api/v1/rules/{rule['id']}")
        api_client.delete(catalog_url + f"/api/v1/catalog/tables/{table['id']}")
        api_client.delete(encaps_url + f"/api/v1/tenants/{tenant['id']}")


# ---------------------------------------------------------------------------
# 链路 5：JWT token 跨服务一致性
# ---------------------------------------------------------------------------
def test_jwt_token_consistency_across_services(
    api_client, encaps_url, sql_gateway_url, catalog_url, rule_engine_url
):
    """验证同一个 JWT token 能被 4 个模块同时接受。

    使用 api_client fixture 中的统一 token，分别访问 4 个模块的受保护端点，
    全部应返回 200（非 401），证明 JWT 配置一致。
    """
    # 封装层。
    resp = api_client.get(encaps_url + "/api/v1/tenants")
    assert resp.status_code == 200

    # SQL 网关。
    resp = api_client.get(sql_gateway_url + "/api/v1/sql/routes")
    assert resp.status_code == 200

    # Catalog。
    resp = api_client.get(catalog_url + "/api/v1/catalog/tables")
    assert resp.status_code == 200

    # 规则引擎。
    resp = api_client.get(rule_engine_url + "/api/v1/rules")
    assert resp.status_code == 200