"""规则引擎（rule-engine）集成测试。

被测对象：``platform/rule-engine``，Java/Spring Boot，默认端口 8083。

覆盖端点：
- GET    /api/v1/health
- GET    /api/v1/rules/types
- POST   /api/v1/rules
- GET    /api/v1/rules
- GET    /api/v1/rules/{id}
- PUT    /api/v1/rules/{id}
- DELETE /api/v1/rules/{id}
- POST   /api/v1/rules/execute

设计要点：
- 规则类型固定为 ``["DQ", "MASK", "ALERT"]``；
- 使用 ``sample_rule`` fixture 创建测试规则并自动清理；
- 执行规则返回 SIMULATED 模拟结果，校验 status 与 ruleId。
"""

from __future__ import annotations


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(api_client, rule_engine_url):
    """验证规则引擎健康检查端点返回 200 且 status=UP。"""
    resp = api_client.get(rule_engine_url + "/api/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"


# ---------------------------------------------------------------------------
# 规则类型
# ---------------------------------------------------------------------------
def test_list_rule_types(api_client, rule_engine_url):
    """验证 GET /api/v1/rules/types 返回 ["DQ", "MASK", "ALERT"]。"""
    resp = api_client.get(rule_engine_url + "/api/v1/rules/types")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)
    assert set(body) == {"DQ", "MASK", "ALERT"}


# ---------------------------------------------------------------------------
# 规则 CRUD
# ---------------------------------------------------------------------------
def test_create_rule(api_client, rule_engine_url):
    """验证 POST /api/v1/rules 创建规则返回 201/200 且含 id 与请求字段。"""
    payload = {
        "name": "it-create-dq",
        "description": "创建规则测试",
        "type": "DQ",
        "expression": "user_id IS NOT NULL",
        "severity": "ERROR",
        "enabled": True,
    }
    resp = api_client.post(rule_engine_url + "/api/v1/rules", json=payload)
    assert resp.status_code in (200, 201)
    body = resp.json()
    assert "id" in body
    assert body.get("name") == payload["name"]
    assert body.get("type") == "DQ"

    # 清理
    try:
        api_client.delete(rule_engine_url + f"/api/v1/rules/{body['id']}")
    except Exception:
        pass


def test_list_rules(api_client, rule_engine_url, sample_rule):
    """验证 GET /api/v1/rules 返回 200 且为列表，含已创建的规则。"""
    resp = api_client.get(rule_engine_url + "/api/v1/rules")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)
    ids = [r.get("id") for r in body]
    assert sample_rule["id"] in ids


def test_get_rule(api_client, rule_engine_url, sample_rule):
    """验证 GET /api/v1/rules/{id} 返回 200 且字段一致。"""
    rule_id = sample_rule["id"]
    resp = api_client.get(rule_engine_url + f"/api/v1/rules/{rule_id}")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == rule_id
    assert body.get("name") == sample_rule["name"]


def test_get_rule_not_found(api_client, rule_engine_url):
    """验证 GET /api/v1/rules/999999 对不存在的 id 返回 404。"""
    resp = api_client.get(rule_engine_url + "/api/v1/rules/999999")
    assert resp.status_code == 404


def test_update_rule(api_client, rule_engine_url, sample_rule):
    """验证 PUT /api/v1/rules/{id} 更新规则返回 200 且字段已更新。"""
    rule_id = sample_rule["id"]
    update_payload = {
        "name": "it-test-dq-updated",
        "description": "已更新",
        "type": "DQ",
        "expression": "user_id IS NOT NULL AND age > 0",
        "severity": "WARN",
        "enabled": False,
    }
    resp = api_client.put(
        rule_engine_url + f"/api/v1/rules/{rule_id}", json=update_payload
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == rule_id
    assert body.get("name") == "it-test-dq-updated"
    assert body.get("severity") == "WARN"


def test_delete_rule(api_client, rule_engine_url):
    """验证 DELETE /api/v1/rules/{id} 删除规则返回 204，再次删除返回 404。"""
    # 先创建一个待删除规则。
    create_resp = api_client.post(
        rule_engine_url + "/api/v1/rules",
        json={
            "name": "it-delete-rule",
            "description": "删除规则测试",
            "type": "MASK",
            "expression": "mask(phone)",
            "severity": "INFO",
            "enabled": True,
        },
    )
    assert create_resp.status_code in (200, 201)
    rule_id = create_resp.json()["id"]

    del_resp = api_client.delete(rule_engine_url + f"/api/v1/rules/{rule_id}")
    assert del_resp.status_code == 204

    again_resp = api_client.delete(rule_engine_url + f"/api/v1/rules/{rule_id}")
    assert again_resp.status_code == 404


# ---------------------------------------------------------------------------
# 规则执行
# ---------------------------------------------------------------------------
def test_execute_rule(api_client, rule_engine_url, sample_rule):
    """验证 POST /api/v1/rules/execute 执行规则返回 200 且含 ruleId/status。"""
    payload = {
        "ruleId": sample_rule["id"],
        "context": {"table": "t_user"},
        "tenantId": "default",
    }
    resp = api_client.post(
        rule_engine_url + "/api/v1/rules/execute", json=payload
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("ruleId") == sample_rule["id"]
    assert "status" in body
    # MVP 阶段为 SIMULATED/PASS，未来可能为 SUCCESS/FAIL。
    assert body.get("status") in ("PASS", "FAIL", "SIMULATED", "SUCCESS", "ERROR")


def test_execute_rule_not_found(api_client, rule_engine_url):
    """验证执行不存在的 ruleId 返回 404。"""
    payload = {"ruleId": 999999, "context": {}, "tenantId": "default"}
    resp = api_client.post(
        rule_engine_url + "/api/v1/rules/execute", json=payload
    )
    assert resp.status_code == 404


# ---------------------------------------------------------------------------
# 完整 CRUD + 执行流程
# ---------------------------------------------------------------------------
def test_rule_crud_and_execute_flow(api_client, rule_engine_url):
    """端到端流程：创建 → 获取 → 更新 → 执行 → 删除 → 再获取（404）。

    本测试自管理数据，验证规则完整生命周期与执行能力。
    """
    # 1. 创建
    create_resp = api_client.post(
        rule_engine_url + "/api/v1/rules",
        json={
            "name": "it-flow-alert",
            "description": "流程测试告警规则",
            "type": "ALERT",
            "expression": "cpu_usage > 0.9",
            "severity": "CRITICAL",
            "enabled": True,
        },
    )
    assert create_resp.status_code in (200, 201)
    rule = create_resp.json()
    rule_id = rule["id"]

    try:
        # 2. 获取
        get_resp = api_client.get(rule_engine_url + f"/api/v1/rules/{rule_id}")
        assert get_resp.status_code == 200
        assert get_resp.json()["name"] == "it-flow-alert"

        # 3. 更新
        upd_resp = api_client.put(
            rule_engine_url + f"/api/v1/rules/{rule_id}",
            json={
                "name": "it-flow-alert-v2",
                "description": "已更新",
                "type": "ALERT",
                "expression": "cpu_usage > 0.95",
                "severity": "CRITICAL",
                "enabled": True,
            },
        )
        assert upd_resp.status_code == 200
        assert upd_resp.json()["name"] == "it-flow-alert-v2"

        # 4. 执行
        exec_resp = api_client.post(
            rule_engine_url + "/api/v1/rules/execute",
            json={
                "ruleId": rule_id,
                "context": {"host": "node-1"},
                "tenantId": "default",
            },
        )
        assert exec_resp.status_code == 200
        exec_body = exec_resp.json()
        assert exec_body.get("ruleId") == rule_id
        assert "status" in exec_body

        # 5. 列表中应包含该规则
        list_resp = api_client.get(rule_engine_url + "/api/v1/rules")
        assert list_resp.status_code == 200
        assert rule_id in [r["id"] for r in list_resp.json()]
    finally:
        # 6. 删除
        del_resp = api_client.delete(rule_engine_url + f"/api/v1/rules/{rule_id}")
        assert del_resp.status_code == 204

    # 7. 删除后获取应 404
    not_found_resp = api_client.get(rule_engine_url + f"/api/v1/rules/{rule_id}")
    assert not_found_resp.status_code == 404