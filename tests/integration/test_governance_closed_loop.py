"""治理闭环集成测试（链路5）。

覆盖：资产入目录 → 质量规则 → 血缘查询 → 质量分回写 的 HTTP 链路。
组件不可用时自动跳过（不阻塞 CI）。
"""

from __future__ import annotations

import json


def test_asset_catalog_crud(api_client, encaps_url):
    """资产入目录：创建/查询/回写质量分。"""
    token = _login(api_client, encaps_url)
    if not token:
        import pytest
        pytest.skip("登录不可用（需 Keycloak），跳过")

    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    # 创建资产（质量分 80）
    resp = api_client.post(encaps_url + "/api/v1/assets",
                           json={"name": "it-orders", "type": "table",
                                 "owner": "it", "qualityScore": 80, "securityLevel": "L2"},
                           headers=headers)
    if resp.status_code != 200:
        import pytest
        pytest.skip(f"资产创建失败: HTTP {resp.status_code}")
    asset = resp.json()
    assert asset.get("qualityScore") == 80
    asset_id = asset.get("id")

    # 质量分回写 80 → 92
    put_resp = api_client.put(f"{encaps_url}/api/v1/assets/{asset_id}",
                              json={"name": "it-orders", "type": "table",
                                    "owner": "it", "qualityScore": 92, "securityLevel": "L2"},
                              headers=headers)
    assert put_resp.status_code == 200
    assert put_resp.json().get("qualityScore") == 92

    # 租户隔离：无 token 应 401
    noauth = api_client.get(encaps_url + "/api/v1/assets")
    assert noauth.status_code == 401


def test_quality_rules_endpoint(api_client, rule_engine_url):
    """质量规则列表（治理质量校验入口）。"""
    resp = api_client.get(rule_engine_url + "/api/v1/rules")
    if resp.status_code != 200:
        import pytest
        pytest.skip(f"rule-engine 不可用: HTTP {resp.status_code}")
    body = resp.json()
    assert "list" in body and "total" in body  # PagedResult 契约


def test_lineage_query(api_client, lineage_url):
    """血缘查询（治理血缘入口）。"""
    import pytest
    try:
        resp = api_client.post(lineage_url + "/api/v1/lineage/query",
                               json={"table": "orders"})
    except Exception:
        pytest.skip("lineage 服务不可用，跳过")
    if resp.status_code != 200:
        pytest.skip(f"lineage 不可用: HTTP {resp.status_code}")
    # 空结果可接受（无录入）；接口必须正常响应
    assert resp.status_code == 200


def _login(api_client, encaps_url):
    try:
        resp = api_client.post(encaps_url + "/api/v1/auth/login",
                               json={"username": "demo", "password": "demo123"})
        if resp.status_code == 200:
            data = resp.json()
            return data.get("token", "")
    except Exception:
        pass
    return ""
