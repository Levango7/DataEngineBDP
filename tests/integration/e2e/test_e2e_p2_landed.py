"""P2-26/27/28 真实 API 落地测试（Sprint 3.2.2 取消 skip，替换骨架）。

背景：Sprint 1.x 写骨架标记 @pytest.mark.skip，注释"Phase 3 实现后取消"。
Sprint 3.2 阶段三种 P2 需求现状：
- P2-26 数据虚拟化：sql-gateway VirtualTableController 已实现完整 CRUD
  （/api/v1/virtual-tables 12 端点：CRUD + query/schema/test/refresh/cache/types），
  契约可路由，本测试做真实 API 验证。
- P2-27 能源行业模板：industry-templates 当前 7 个模板（金融/零售/制造/医疗/交通/教育/农牧），
  暂无 energy 专属模板。本测试验证行业模板域整体可路由（列表/分类），并断言 ≥7 个
  模板；能源专属模板实现是 Phase 4+ 业务工作，本测试兜底域级契约。
- P2-28 政务行业模板：同上，模板详情端点可路由；政务专属模板实现是 Phase 4+ 业务工作。

设计对齐：与 test_e2e_all_requirements.py 既有 P0/P1 测试同模式——
用 e2e_api_client（自动 JWT）+ *_url + *_available fixture（服务不可用时 skip）。
"""

from __future__ import annotations

import pytest


def _skip_unless(available: bool, reason: str) -> None:
    if not available:
        pytest.skip(reason)


def _unwrap_response(body):
    """从可能被 ApiResponse 包装的响应中提取业务数据。"""
    if isinstance(body, dict) and "code" in body and "data" in body:
        return body["data"]
    return body


# ===========================================================================
# P2-26 数据虚拟化（sql-gateway VirtualTableController）
# ===========================================================================

@pytest.mark.p2
@pytest.mark.requirement("P2-26 数据虚拟化")
def test_req_data_virtualization(
    e2e_api_client,
    sql_gateway_url,
    sql_gateway_available,
):
    """P2-26 数据虚拟化（Sprint 3.2 落地）。

    sql-gateway VirtualTableController 已实现完整 CRUD。本测试验证：
    1. GET /api/v1/virtual-tables 列出虚拟表（200 + 列表）；
    2. GET /api/v1/virtual-tables/types 列出支持的数据源类型（200 + ≥3 种）。
    """
    _skip_unless(sql_gateway_available, "SQL 网关服务不可用")

    # 1. 列出虚拟表
    resp = e2e_api_client.get(sql_gateway_url + "/api/v1/virtual-tables")
    assert resp.status_code == 200, f"虚拟表列表失败: {resp.text}"
    body = _unwrap_response(resp.json())
    assert isinstance(body, list), f"期望列表，实际 {type(body).__name__}"

    # 2. 列出虚拟表支持的数据源类型
    types_resp = e2e_api_client.get(sql_gateway_url + "/api/v1/virtual-tables/types")
    assert types_resp.status_code == 200, f"虚拟表类型查询失败: {types_resp.text}"
    types_body = _unwrap_response(types_resp.json())
    assert isinstance(types_body, list), f"期望列表，实际 {type(types_body).__name__}"
    # 至少支持 3 种数据源类型（mysql/postgresql/... 契约）
    assert len(types_body) >= 3, f"虚拟表类型数量 {len(types_body)} 偏低（期望 ≥3）"


# ===========================================================================
# P2-27 能源行业模板（industry-templates）
# ===========================================================================

@pytest.mark.p2
@pytest.mark.requirement("P2-27 能源行业模板")
def test_req_energy_template(
    e2e_api_client,
    industry_templates_url,
    industry_templates_available,
):
    """P2-27 能源行业模板（Sprint 4.1 真实模板落地）。

    Sprint 4.1 新增 energy-iot-monitor 模板（IoT 物联采集/能耗分析/负荷预测/告警）。
    本测试验证：
    1. 分类清单含 energy 行业；
    2. 模板列表含 energy-iot-monitor 且行业归属为 energy。
    """
    _skip_unless(industry_templates_available, "行业模板服务不可用")

    # 1. 分类清单
    cat_resp = e2e_api_client.get(industry_templates_url + "/api/v1/templates/categories")
    assert cat_resp.status_code == 200, f"模板分类查询失败: {cat_resp.text}"
    cat_body = _unwrap_response(cat_resp.json())
    assert isinstance(cat_body, list), f"期望列表，实际 {type(cat_body).__name__}"
    cat_ids = {c["industry"] for c in cat_body}
    assert "energy" in cat_ids, f"分类清单缺 energy 行业: {cat_ids}"

    # 2. 模板列表含能源专属
    list_resp = e2e_api_client.get(industry_templates_url + "/api/v1/templates")
    assert list_resp.status_code == 200, f"模板列表查询失败: {list_resp.text}"
    list_body = _unwrap_response(list_resp.json())
    assert isinstance(list_body, list), f"期望列表，实际 {type(list_body).__name__}"
    energy_templates = [t for t in list_body if t.get("id") == "energy-iot-monitor"]
    assert energy_templates, "模板列表缺少能源专属 energy-iot-monitor"
    assert energy_templates[0].get("industry") == "energy", "能源模板行业归属错误"


# ===========================================================================
# P2-28 政务行业模板（industry-templates）
# ===========================================================================

@pytest.mark.p2
@pytest.mark.requirement("P2-28 政务行业模板")
def test_req_government_template(
    e2e_api_client,
    industry_templates_url,
    industry_templates_available,
):
    """P2-28 政务行业模板（Sprint 4.1 真实模板落地）。

    Sprint 4.1 新增 gov-public-services 模板（数据共享/一网通办/民生诉求/效能看板）。
    本测试验证：
    1. 模板列表含 gov-public-services 且行业归属为 government；
    2. 模板详情端点可路由。
    """
    _skip_unless(industry_templates_available, "行业模板服务不可用")

    # 1. 列表含政务专属模板
    list_resp = e2e_api_client.get(industry_templates_url + "/api/v1/templates")
    assert list_resp.status_code == 200, f"模板列表查询失败: {list_resp.text}"
    list_body = _unwrap_response(list_resp.json())
    assert isinstance(list_body, list), f"期望列表，实际 {type(list_body).__name__}"
    gov_templates = [t for t in list_body if t.get("id") == "gov-public-services"]
    assert gov_templates, "模板列表缺少政务专属 gov-public-services"
    assert gov_templates[0].get("industry") == "government", "政务模板行业归属错误"

    # 2. 模板详情端点
    # 注意：详情端点返回完整 Template dump（id/industry 在 meta 下，顶层无），
    # 列表端点返回模板视图（顶层 id/industry）。断言兼容两种形态。
    detail_resp = e2e_api_client.get(
        industry_templates_url + "/api/v1/templates/gov-public-services"
    )
    assert detail_resp.status_code == 200, f"政务模板详情查询失败: {detail_resp.text}"
    detail_body = _unwrap_response(detail_resp.json())
    detail_id = detail_body.get("id") or detail_body.get("meta", {}).get("id")
    detail_industry = detail_body.get("industry") or detail_body.get("meta", {}).get("industry")
    assert detail_id == "gov-public-services", f"详情 ID 不匹配: {detail_id}"
    assert detail_industry == "government", f"详情行业不匹配: {detail_industry}"
