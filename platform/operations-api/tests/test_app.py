"""operations-api 冒烟测试 · 验证应用启动与核心路由可访问.

P-04 合同交付实体 — 基础冒烟测试，确保：
  1. FastAPI 应用实例可正常创建
  2. 健康检查端点 /healthz 返回 200
  3. 就绪检查端点 /readyz 返回 200
  4. 合同管理路由 /api/v1/contracts 已注册（GET 返回非 404）
  5. 鉴权依赖已注入（AUTH_MODE=none 时匿名 admin 放行）

运行方式：
    cd platform/operations-api
    pip install -e ".[dev]"
    pytest tests/test_app.py -v
"""

from __future__ import annotations

import os

# 测试环境：强制 AUTH_MODE=none，避免 K8s fail-fast
os.environ.setdefault("AUTH_MODE", "none")

from fastapi.testclient import TestClient  # noqa: E402  # noqa: E402

from operations_api.app import app  # noqa: E402  # noqa: E402


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

    AUTH_MODE=none 时匿名 admin 放行，但缺省 tenantId 来自 JWT（匿名 tenantId="")，
    会返回 400（无法确定租户 ID），证明路由已注册且鉴权依赖已生效。
    """
    client = TestClient(app)
    response = client.get("/api/v1/contracts")
    # 400 表示路由已注册且鉴权依赖生效（匿名无 tenantId），不是 404（路由不存在）
    assert response.status_code != 404, "合同管理路由 /api/v1/contracts 未注册"


def test_contracts_list_with_tenant_param():
    """AUTH_MODE=none 匿名 admin 可通过 tenantId query param 查询合同列表."""
    client = TestClient(app)
    response = client.get("/api/v1/contracts", params={"tenantId": "t-1"})
    # 匿名 admin + tenantId query param → effectiveTenant 返回 "t-1"，进入 service 层
    # service 层调用 repo（骨架实现），可能返回 200 或 500，但不应是 401/403/404
    assert response.status_code != 404, "路由未注册"
    assert response.status_code != 401, "匿名模式不应返回 401"
    assert response.status_code != 403, "匿名 admin 不应返回 403"


def test_openapi_docs_available():
    """OpenAPI 文档端点 /openapi.json 应返回 200."""
    client = TestClient(app)
    response = client.get("/openapi.json")
    assert response.status_code == 200
    schema = response.json()
    assert "paths" in schema


def test_jwt_auth_module_importable():
    """jwt_auth 模块应可导入且导出关键依赖符号."""
    from operations_api.jwt_auth import (
        AuthContext,
        effectiveTenant,
        getAuthContext,
        requireAdmin,
    )

    # effectiveTenant 行为：admin + 指定 tenantId → 返回指定值
    ctx = AuthContext(userId="u", tenantId="t-jwt", role="admin")
    assert effectiveTenant(ctx, "t-query") == "t-query"
    # 普通用户 → 强制取 JWT
    ctx_user = AuthContext(userId="u", tenantId="t-jwt", role="user")
    assert effectiveTenant(ctx_user, "t-query") == "t-jwt"
    # admin 未指定 → 取 JWT
    assert effectiveTenant(ctx, None) == "t-jwt"
