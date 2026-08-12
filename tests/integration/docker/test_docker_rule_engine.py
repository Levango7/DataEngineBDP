"""规则引擎（rule-engine）Docker 集成测试。

被测对象：Docker 容器 ``it-rule-engine``（镜像 ``sq/rule-engine:0.1.0``），
Java/Spring Boot，主机端口 18083 → 容器 8083。

覆盖端点：
- GET  /actuator/health        （健康检查，无需认证）
- GET  /api/v1/rules           （列出规则，需认证）
- POST /api/v1/rules           （创建规则，需认证）
- GET  /api/v1/rules/{id}      （获取单个规则，需认证）
- PUT  /api/v1/rules/{id}      （更新规则，需认证）
- DELETE /api/v1/rules/{id}    （删除规则，需认证）
- POST /api/v1/rules/execute   （执行规则，需认证）
- GET  /api/v1/rules/types     （列出规则类型，需认证）

设计要点：
- RuleExecutionRequest.ruleId 是 Long 类型（非 String）；
- 规则执行返回 status=PASS/FAIL/ERROR，message=SIMULATED 表示模拟执行；
- 验证无认证请求返回 401（认证机制正常）。
"""

from __future__ import annotations

import pytest


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(rule_engine_url):
    """验证规则引擎健康检查端点返回 200 且 status=UP。"""
    import requests

    resp = requests.get(rule_engine_url + "/actuator/health", timeout=10)
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"


# ---------------------------------------------------------------------------
# 认证机制验证
# ---------------------------------------------------------------------------
def test_unauthorized_without_token(rule_engine_url):
    """验证无 Bearer token 访问受保护端点返回 401。"""
    import requests

    resp = requests.get(rule_engine_url + "/api/v1/rules", timeout=10)
    assert resp.status_code == 401


# ---------------------------------------------------------------------------
# 规则类型
# ---------------------------------------------------------------------------
def test_list_rule_types(api_client, rule_engine_url):
    """验证 GET /api/v1/rules/types 返回 200 且包含 DQ、MASK、ALERT 三种类型。"""
    resp = api_client.get(rule_engine_url + "/api/v1/rules/types")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)
    assert "DQ" in body
    assert "MASK" in body
    assert "ALERT" in body


# ---------------------------------------------------------------------------
# 规则 CRUD
# ---------------------------------------------------------------------------
def test_list_rules(api_client, rule_engine_url):
    """验证 GET /api/v1/rules 返回 200 且为列表。"""
    resp = api_client.get(rule_engine_url + "/api/v1/rules")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)


def test_create_rule(api_client, rule_engine_url):
    """验证 POST /api/v1/rules 创建规则返回 201。"""
    payload = {
        "name": "docker-it-create-rule",
        "description": "Docker集成测试规则",
        "type": "DQ",
        "expression": "value > 0",
        "severity": "INFO",
        "enabled": True,
    }
    resp = api_client.post(rule_engine_url + "/api/v1/rules", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert "id" in body
    assert body.get("name") == payload["name"]
    assert body.get("type") == payload["type"]

    # 清理。
    try:
        api_client.delete(rule_engine_url + f"/api/v1/rules/{body['id']}")
    except Exception:
        pass


def test_get_rule(api_client, rule_engine_url, sample_rule):
    """验证 GET /api/v1/rules/{id} 返回 200 且字段一致。"""
    rule_id = sample_rule["id"]
    resp = api_client.get(rule_engine_url + f"/api/v1/rules/{rule_id}")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == rule_id
    assert body.get("name") == sample_rule["name"]


def test_get_rule_not_found(api_client, rule_engine_url):
    """验证 GET /api/v1/rules/{id} 对不存在的 id 返回 404。"""
    resp = api_client.get(rule_engine_url + "/api/v1/rules/999999")
    assert resp.status_code == 404


def test_update_rule(api_client, rule_engine_url, sample_rule):
    """验证 PUT /api/v1/rules/{id} 更新规则返回 200 且字段已更新。"""
    rule_id = sample_rule["id"]
    update_payload = {
        "name": sample_rule["name"],
        "description": "更新后的描述",
        "type": "MASK",
        "expression": "mask(value)",
        "severity": "WARN",
        "enabled": False,
    }
    resp = api_client.put(
        rule_engine_url + f"/api/v1/rules/{rule_id}", json=update_payload
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == rule_id
    assert body.get("type") == "MASK"
    assert body.get("severity") == "WARN"


def test_delete_rule(api_client, rule_engine_url):
    """验证 DELETE /api/v1/rules/{id} 删除规则返回 204。"""
    # 先创建一个待删除的规则。
    payload = {
        "name": "docker-it-delete-rule",
        "type": "ALERT",
        "expression": "value > 100",
        "severity": "ERROR",
        "enabled": True,
    }
    create_resp = api_client.post(rule_engine_url + "/api/v1/rules", json=payload)
    assert create_resp.status_code == 201
    rule_id = create_resp.json()["id"]

    # 删除。
    resp = api_client.delete(rule_engine_url + f"/api/v1/rules/{rule_id}")
    assert resp.status_code == 204

    # 验证已删除。
    verify_resp = api_client.get(rule_engine_url + f"/api/v1/rules/{rule_id}")
    assert verify_resp.status_code == 404


# ---------------------------------------------------------------------------
# 规则执行
# ---------------------------------------------------------------------------
def test_execute_rule(api_client, rule_engine_url, sample_rule):
    """验证 POST /api/v1/rules/execute 执行已存在的规则返回 200。"""
    payload = {
        "ruleId": sample_rule["id"],
        "context": {"value": 42},
        "tenantId": "docker-it-tenant",
    }
    resp = api_client.post(rule_engine_url + "/api/v1/rules/execute", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("ruleId") == sample_rule["id"]
    assert "status" in body
    # PASS/FAIL/SIMULATED 均为合法执行结果。
    assert body["status"] in ("PASS", "FAIL", "ERROR", "SIMULATED")


def test_execute_rule_not_found(api_client, rule_engine_url):
    """验证 POST /api/v1/rules/execute 对不存在的规则返回 404。"""
    payload = {"ruleId": 999999, "context": {"value": 1}, "tenantId": "docker-it-tenant"}
    resp = api_client.post(rule_engine_url + "/api/v1/rules/execute", json=payload)
    assert resp.status_code == 404


# ---------------------------------------------------------------------------
# 端到端 CRUD + 执行流程
# ---------------------------------------------------------------------------
def test_rule_crud_and_execute_flow(api_client, rule_engine_url):
    """端到端验证规则 CRUD + 执行完整流程：创建 → 查询 → 执行 → 更新 → 删除。"""
    # 1. 创建
    create_payload = {
        "name": "docker-it-crud-flow-rule",
        "description": "CRUD+执行流程测试",
        "type": "DQ",
        "expression": "value > 0",
        "severity": "INFO",
        "enabled": True,
    }
    create_resp = api_client.post(rule_engine_url + "/api/v1/rules", json=create_payload)
    assert create_resp.status_code == 201
    rule = create_resp.json()
    rule_id = rule["id"]

    try:
        # 2. 查询
        get_resp = api_client.get(rule_engine_url + f"/api/v1/rules/{rule_id}")
        assert get_resp.status_code == 200
        assert get_resp.json()["name"] == create_payload["name"]

        # 3. 执行
        exec_resp = api_client.post(
            rule_engine_url + "/api/v1/rules/execute",
            json={"ruleId": rule_id, "context": {"value": 1}, "tenantId": "docker-it-tenant"},
        )
        assert exec_resp.status_code == 200
        assert exec_resp.json()["ruleId"] == rule_id

        # 4. 更新
        update_payload = {**create_payload, "severity": "WARN"}
        update_resp = api_client.put(
            rule_engine_url + f"/api/v1/rules/{rule_id}", json=update_payload
        )
        assert update_resp.status_code == 200
        assert update_resp.json()["severity"] == "WARN"
    finally:
        # 5. 清理
        api_client.delete(rule_engine_url + f"/api/v1/rules/{rule_id}")


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture
def sample_rule(api_client, rule_engine_url):
    """创建一个示例规则，测试结束后自动删除。

    Yields:
        创建后的规则字典（含 id 等字段）。
    """
    payload = {
        "name": "docker-it-sample-rule",
        "description": "示例规则",
        "type": "DQ",
        "expression": "value > 0",
        "severity": "INFO",
        "enabled": True,
    }
    resp = api_client.post(rule_engine_url + "/api/v1/rules", json=payload)
    assert resp.status_code == 201
    rule = resp.json()

    yield rule

    # 清理。
    try:
        api_client.delete(rule_engine_url + f"/api/v1/rules/{rule['id']}")
    except Exception:
        pass