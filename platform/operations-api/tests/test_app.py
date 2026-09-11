"""operations-api 冒烟测试 · 验证应用启动与核心路由可访问.

P-04 合同交付实体 — 基础冒烟测试，确保：
  1. FastAPI 应用实例可正常创建
  2. 健康检查端点 /healthz 返回 200
  3. 就绪检查端点 /readyz 返回 200
  4. 合同管理路由 /api/v1/contracts 已注册（GET 返回非 404）

运行方式：
    cd platform/operations-api
    pip install -e ".[dev]"
    pytest tests/test_app.py -v
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from operations_api.app import app


def test_app_instance_created():
    """应用实例应成功创建且标题正确."""
    assert app is not None
    assert app.title == "数擎运营后台 · 合同管理服务"


def test_healthz_endpoint():
    """健康检查端点 /healthz 应返回 200 且 status=ok."""
    client = TestClient(app)
    response = client.get("/healthz")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "operations-api"


def test_readyz_endpoint():
    """就绪检查端点 /readyz 应返回 200 且 status=ok."""
    client = TestClient(app)
    response = client.get("/readyz")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"


def test_contracts_route_registered():
    """合同管理路由 /api/v1/contracts 应已注册（GET 不返回 404）.

    路由需要 tenantId 查询参数，未提供时返回 422（参数校验失败），
    但 422 证明路由已注册，仅缺少必需参数。
    """
    client = TestClient(app)
    response = client.get("/api/v1/contracts")
    # 422 表示路由已注册但缺少必需的 tenantId 参数，不是 404（路由不存在）
    assert response.status_code != 404, "合同管理路由 /api/v1/contracts 未注册"


def test_openapi_docs_available():
    """OpenAPI 文档端点 /openapi.json 应返回 200."""
    client = TestClient(app)
    response = client.get("/openapi.json")
    assert response.status_code == 200
    schema = response.json()
    assert "paths" in schema