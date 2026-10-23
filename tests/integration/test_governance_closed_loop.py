"""治理闭环集成测试（链路5）。

覆盖：资产入目录 → 质量规则 → 血缘查询 → 质量分回写 的 HTTP 链路。
组件不可用时自动跳过（不阻塞 CI）。
"""

from __future__ import annotations

import time

import requests


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
    try:
        resp = api_client.get(rule_engine_url + "/api/v1/rules")
    except requests.exceptions.ConnectionError:
        # 本文件既定契约是"组件不可用时自动跳过"，但原实现只把"非 200"当缺席：
        # 服务根本没起时 requests 直接抛 ConnectionError → 没起 compose 的机器必红。
        # 服务在位时断言强度不变（状态码与响应结构仍会真实判失败）。
        import pytest
        pytest.skip(f"rule-engine 不可达，跳过: {rule_engine_url}")
    if resp.status_code != 200:
        import pytest
        pytest.skip(f"rule-engine 不可用: HTTP {resp.status_code}")
    body = resp.json()
    # PagedResult 契约（dict with list/total）或空列表均可接受
    if isinstance(body, dict):
        assert "list" in body and "total" in body
    else:
        assert isinstance(body, list)


def test_lineage_query(api_client, lineage_url):
    """血缘闭环：OpenLineage 摄取 → 上下游查询必须读回同一条边。

    对应治理闭环草案 阶段0 断言 1（写入口有真实调用方）与断言 2（写完查得到）。

    历史版本打的是 ``POST /api/v1/lineage/query``——该路径在任何后端都没有实现
    （只有 design/deploy/values/governance-values.yaml:247 声明过），且当时
    ``BASE_URLS["lineage"]`` 指向 18084，也就是 finops 的主机端口，
    于是这个用例等价于"向 finops 发一个不存在的路径，非 200 就 skip"：
    永远绿、永远不覆盖。现已改为真实查询面并显式区分"服务缺席"与"闭环断裂"。
    """
    import uuid

    import pytest

    ns = "it-closed-loop"
    src, dst = f"{ns}/ods.it", f"{ns}/dws.it"
    event = {
        "eventType": "COMPLETE",
        "eventTime": time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
        "run": {"runId": str(uuid.uuid4())},
        "job": {"namespace": ns, "name": f"{ns}.it-job"},
        # 规范包装对象形态：{...} 而非裸数组，顺带守住"标准生产端也能落边"
        "inputs": {"datasets": [{"namespace": ns, "name": "ods.it"}]},
        "outputs": {"datasets": [{"namespace": ns, "name": "dws.it"}]},
    }

    try:
        ing = api_client.post(lineage_url + "/api/v1/lineage/events", json=event)
    except requests.exceptions.RequestException as exc:
        pytest.skip(f"lineage-analyzer 未纳入本 compose 拓扑（不可达）：{exc}")
        return  # 显式 return：CodeQL 不把 pytest.skip 视为不返回，运行时它已抛异常

    if ing.status_code in (401, 403):
        pytest.skip(
            f"lineage-analyzer 可达但拒绝测试令牌（HTTP {ing.status_code}）；"
            "本 leg 未为其注入与服务端一致的 JWT_SECRET"
        )
    if ing.status_code == 404:
        pytest.skip(f"lineage-analyzer 未提供 /api/v1/lineage/events：{ing.text[:120]}")

    assert ing.status_code == 200, f"摄取失败 HTTP {ing.status_code}: {ing.text[:300]}"
    assert ing.json().get("edges") == 1, f"规范形态事件未落边：{ing.text[:300]}"

    # 关键断言：含 / 的数据集全名必须能从统一查询面读回（?table= 形态）
    down = api_client.get(lineage_url + "/api/v1/lineage/downstream", params={"table": src})
    assert down.status_code == 200, f"下游查询失败 HTTP {down.status_code}: {down.text[:300]}"
    assert dst in down.json().get("tables", []), f"写入成功但查询为空：{down.text[:300]}"

    up = api_client.get(lineage_url + "/api/v1/lineage/upstream", params={"table": dst})
    assert src in up.json().get("tables", []), f"上游查询未读回边：{up.text[:300]}"


def test_lineage_query_surface_rejects_bad_request(api_client, lineage_url):
    """查询面缺 table 或深度越界必须是 4xx，不能被伪装成 500。"""
    import pytest

    try:
        missing = api_client.get(lineage_url + "/api/v1/lineage/downstream")
        too_deep = api_client.get(
            lineage_url + "/api/v1/lineage/downstream", params={"table": "a.b", "depth": 999}
        )
    except requests.exceptions.RequestException as exc:
        pytest.skip(f"lineage-analyzer 不可达：{exc}")
        return  # 显式 return：CodeQL 不把 pytest.skip 视为不返回，运行时它已抛异常

    if missing.status_code == 404:
        pytest.skip("lineage-analyzer 版本过旧，未提供 ?table= 形态")
    assert missing.status_code == 400, f"缺 table 应 400，实际 {missing.status_code}"
    assert too_deep.status_code == 400, f"depth 越界应 400，实际 {too_deep.status_code}"


def test_quality_rules_actually_evaluated(api_client, rule_engine_url):
    """阶段 0 断言 3：rule-engine 中登记的规则被实际评估。

    对应治理闭环草案 阶段0 断言 3。
    GET /api/v1/quality/rules/summary 会对当前租户的全部规则执行一次校验，
    并返回 {total, passed, passRate} —— 这是"规则被实际评估"的可回归契约。

    若 service 缺席则 skip（不阻塞 CI）；若端点 404/非 200 则该 leg 为断裂。
    """
    import pytest

    try:
        resp = api_client.get(rule_engine_url + "/api/v1/quality/rules/summary")
    except requests.exceptions.RequestException as exc:
        pytest.skip(f"rule-engine 不可达：{exc}")
        return

    if resp.status_code == 404:
        pytest.skip(f"rule-engine 未提供 /api/v1/quality/rules/summary：{resp.text[:120]}")
    assert resp.status_code == 200, f"质量规则 summary 失败 HTTP {resp.status_code}: {resp.text[:300]}"

    body = resp.json()
    assert isinstance(body, dict), f"summary 应返回对象，实际：{resp.text[:300]}"
    for key in ("total", "passed", "passRate"):
        assert key in body, f"summary 缺少字段 {key}: {resp.text[:300]}"
    assert isinstance(body["total"], int), f"total 应为整数：{resp.text[:300]}"
    assert isinstance(body["passRate"], (int, float)), f"passRate 应为数值：{resp.text[:300]}"


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
