"""Industry Templates（行业应用模板平台, L5.3）集成测试.

被测组件：platform/industry-templates（FastAPI, 默认端口 8091）
启动方式：cd platform/industry-templates && INDUSTRY_TEMPLATES_PORT=8091 python main.py

健康检查：GET /api/v1/health → {"status": "UP", "version": "0.1.0", "deployMode": "mock|helm", "templateCount": N}
主要端点：
    GET    /api/v1/templates                列出所有模板（支持过滤）
    GET    /api/v1/templates/{id}           模板详情
    POST   /api/v1/templates/{id}/deploy    部署模板
    GET    /api/v1/templates/{id}/preview   预览模板架构
    GET    /api/v1/categories               列出行业分类
"""
from __future__ import annotations

import httpx
import pytest

HEALTH_PATH = "/api/v1/health"
TEMPLATES_PATH = "/api/v1/templates"
# categories 路由 prefix="/templates" + "/categories" → /api/v1/templates/categories
CATEGORIES_PATH = "/api/v1/templates/categories"
DEFAULT_TIMEOUT = 10.0


# ---------------------------------------------------------------------------
# 健康检查 & 基础冒烟
# ---------------------------------------------------------------------------
def test_health_check(industry_templates_url):
    """健康检查返回 200 且 status=UP，含 deployMode 与 templateCount.

    端点：GET /api/v1/health
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(industry_templates_url + HEALTH_PATH)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "UP"
    assert "deployMode" in body
    assert "templateCount" in body


def test_openapi_schema(industry_templates_url):
    """OpenAPI schema 可访问."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(industry_templates_url + "/openapi.json")
    assert resp.status_code == 200
    schema = resp.json()
    assert schema["info"]["title"] == "Industry Templates Platform"


def test_list_templates(industry_templates_url):
    """列出所有模板返回 200 且为列表.

    端点：GET /api/v1/templates
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(industry_templates_url + TEMPLATES_PATH)
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_list_templates_with_filter(industry_templates_url):
    """按行业过滤模板返回 200 且为列表.

    端点：GET /api/v1/templates?industry=finance
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(
            industry_templates_url + TEMPLATES_PATH,
            params={"industry": "finance"},
        )
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_list_categories(industry_templates_url):
    """列出行业分类返回 200.

    端点：GET /api/v1/categories
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(industry_templates_url + CATEGORIES_PATH)
    assert resp.status_code == 200


# ---------------------------------------------------------------------------
# 模板详情 & 部署（依赖预置模板数据）
# ---------------------------------------------------------------------------
def test_get_template_not_found(industry_templates_url):
    """不存在的模板 id 返回 404.

    端点：GET /api/v1/templates/{id}
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(
            industry_templates_url + f"{TEMPLATES_PATH}/non-existent-template-id"
        )
    assert resp.status_code == 404


def test_template_detail_and_deploy_flow(industry_templates_url):
    """端到端流程：列出模板 → 取首个 → 详情 → 部署（mock 模式）.

    若模板列表为空则跳过（环境无预置模板）。
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        listed = client.get(industry_templates_url + TEMPLATES_PATH)
        assert listed.status_code == 200
        templates = listed.json()
        if not templates:
            pytest.skip("无预置模板，跳过模板详情/部署流程测试")

        # 取第一个模板的 id（兼容 id / templateId 字段）
        first = templates[0]
        template_id = first.get("id") or first.get("templateId")
        assert template_id, f"模板缺少 id 字段: {first}"

        # 详情
        detail = client.get(
            industry_templates_url + f"{TEMPLATES_PATH}/{template_id}"
        )
        assert detail.status_code == 200

        # 部署（mock 模式应返回 201）
        deploy = client.post(
            industry_templates_url + f"{TEMPLATES_PATH}/{template_id}/deploy",
            json={
                "tenantId": "it-test-tenant",
                "releaseName": "it-test-release",
                "values": {
                    "datasource.order_db": "jdbc:mysql://order:3306/order",
                    "datasource.user_db": "jdbc:mysql://user:3306/user",
                },
            },
        )
        assert deploy.status_code in (200, 201), deploy.text