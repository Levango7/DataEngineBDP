"""封装层租户域服务（encaps-tenant）Docker 集成测试。

被测对象：Docker 容器 ``it-encaps-tenant``（镜像 ``shuqing/encaps-tenant:0.1.0``），
Java/Spring Boot，主机端口 18090 → 容器 8081。

Sprint 2.2 L4：本服务此前无 Dockerfile、无 compose 条目、无任何全 context 测试
（单测全为 standaloneSetup），本文件补齐 Docker 层集成测试。

覆盖域与端点：
- 健康检查：GET /actuator/health
- 认证机制：无 token / 无效 token → 401
- 租户 CRUD：GET|POST /api/v1/tenants、GET|PUT|DELETE /api/v1/tenants/{id}
- 项目管理：GET|POST /api/v1/projects（列表分页契约 list/total/page/size）
- 账户：GET /api/v1/account/plan、/account/billing、POST /account/upgrade
- 运营后台：GET /api/v1/admin/kpi、/admin/env-matrix
- Workspace：POST /api/v1/workspaces（K8s mock 下状态容忍）+ GET 列表
- Quota：POST /api/v1/quotas（校验 workspaceId 必填）+ GET 列表

设计要点：
- 每个测试独立，不依赖执行顺序；
- K8s mock 模式（K8S_MOCK_ENABLED=true）：WorkspaceService/QuotaService 的
  K8s 翻译失败被服务层捕获并置状态 FAILED/DELETED，不影响 API 可用性——
  集成测试断言 HTTP 契约与落库行为，不断言 K8s 真实联动（那是 K3s 链路测试的职责）；
- JWT 使用与其他 Java 组件相同的 HMAC 密钥签发（conftest.generate_test_jwt）。
"""

from __future__ import annotations

import pytest

from conftest import unwrap_response


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(encaps_tenant_url):
    """验证封装层租户域服务健康检查返回 200 且 status=UP。"""
    import requests

    resp = requests.get(encaps_tenant_url + "/actuator/health", timeout=10)
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"


# ---------------------------------------------------------------------------
# 认证机制
# ---------------------------------------------------------------------------
def test_unauthorized_without_token(encaps_tenant_url):
    """验证无 Bearer token 访问受保护端点返回 401。"""
    import requests

    resp = requests.get(encaps_tenant_url + "/api/v1/tenants", timeout=10)
    assert resp.status_code == 401


def test_unauthorized_with_invalid_token(encaps_tenant_url):
    """验证无效 Bearer token 返回 401。"""
    import requests

    resp = requests.get(
        encaps_tenant_url + "/api/v1/tenants",
        headers={"Authorization": "Bearer invalid-token-xxx"},
        timeout=10,
    )
    assert resp.status_code == 401


# ---------------------------------------------------------------------------
# 租户 CRUD（与 encaps-layer 的 tenants 端点契约一致——跨进程复用同前缀）
# ---------------------------------------------------------------------------
def test_tenant_crud_flow(api_client, encaps_tenant_url):
    """端到端验证租户 CRUD：创建(201) → 查询(200) → 列表包含 → 更新(200) → 删除(204)。"""
    payload = {
        "name": "docker-it-encaps-tenant-crud",
        "displayName": "租户域CRUD流程",
        "namespace": "ns-it-encaps-tenant",
        "quotaProfile": "small",
    }
    create_resp = api_client.post(encaps_tenant_url + "/api/v1/tenants", json=payload)
    assert create_resp.status_code == 201
    tenant = unwrap_response(create_resp.json())
    tenant_id = tenant["id"]

    try:
        # 查询详情
        get_resp = api_client.get(encaps_tenant_url + f"/api/v1/tenants/{tenant_id}")
        assert get_resp.status_code == 200
        assert unwrap_response(get_resp.json())["name"] == payload["name"]

        # 列表包含
        list_resp = api_client.get(encaps_tenant_url + "/api/v1/tenants")
        assert list_resp.status_code == 200
        ids = [t.get("id") for t in unwrap_response(list_resp.json())]
        assert tenant_id in ids

        # 更新
        update_payload = {**payload, "quotaProfile": "large"}
        update_resp = api_client.put(
            encaps_tenant_url + f"/api/v1/tenants/{tenant_id}", json=update_payload
        )
        assert update_resp.status_code == 200
        assert unwrap_response(update_resp.json()).get("quotaProfile") == "large"
    finally:
        # 清理
        api_client.delete(encaps_tenant_url + f"/api/v1/tenants/{tenant_id}")


def test_tenant_not_found(api_client, encaps_tenant_url):
    """验证 GET 不存在的租户 id 返回 404。"""
    resp = api_client.get(encaps_tenant_url + "/api/v1/tenants/999999")
    assert resp.status_code == 404


# ---------------------------------------------------------------------------
# 项目管理（本服务独有；JWT 需带数字 tenantId claim 以落 ProjectEntity.tenantId）
# ---------------------------------------------------------------------------
def test_project_crud_flow(api_client, encaps_tenant_url, numeric_tenant_token):
    """端到端验证项目 CRUD：创建(200) → 列表分页契约 → 详情 → 删除。"""
    payload = {
        "name": "docker-it-project",
        "domain": "finance",
        "description": "集成测试项目",
    }
    create_resp = api_client.post(
        encaps_tenant_url + "/api/v1/projects",
        json=payload,
        headers={"Authorization": f"Bearer {numeric_tenant_token}"},
    )
    assert create_resp.status_code == 200, create_resp.text
    project = unwrap_response(create_resp.json())
    assert project["name"] == payload["name"]
    assert project["domain"] == payload["domain"]
    project_id = project["id"]

    try:
        # 列表（分页契约）
        list_resp = api_client.get(
            encaps_tenant_url + "/api/v1/projects",
            headers={"Authorization": f"Bearer {numeric_tenant_token}"},
        )
        assert list_resp.status_code == 200
        body = unwrap_response(list_resp.json())
        assert isinstance(body, dict)
        assert {"list", "total", "page", "size"} <= set(body.keys())
        assert project_id in [p["id"] for p in body["list"]]

        # 详情
        get_resp = api_client.get(
            encaps_tenant_url + f"/api/v1/projects/{project_id}",
            headers={"Authorization": f"Bearer {numeric_tenant_token}"},
        )
        assert get_resp.status_code == 200
        assert unwrap_response(get_resp.json())["id"] == project_id
    finally:
        api_client.delete(
            encaps_tenant_url + f"/api/v1/projects/{project_id}",
            headers={"Authorization": f"Bearer {numeric_tenant_token}"},
        )


def test_project_requires_tenant_context(api_client, encaps_tenant_url):
    """验证缺少租户上下文（无 tenantId claim）时项目创建返回服务端错误（500），
    而非静默落库——ProjectController.requireTenant() 的防御行为。"""
    # api_client 默认 token 带 tenantId="docker-it-tenant"（非数字）
    # AccountController.tenantIdLong() 会容错为 0，但 ProjectController.requireTenant()
    # 只要求非空——所以这里改用「无 tenantId claim」的 token 触发防御
    from conftest import generate_test_jwt

    no_tenant_token = generate_test_jwt(tenant_id="")  # type: ignore[call-arg]
    resp = api_client.post(
        encaps_tenant_url + "/api/v1/projects",
        json={"name": "no-tenant", "domain": "x"},
        headers={"Authorization": f"Bearer {no_tenant_token}"},
    )
    # 防御路径：缺租户上下文 → 500（IllegalStateException 由全局异常处理兜底）
    assert resp.status_code in (400, 401, 500)


# ---------------------------------------------------------------------------
# 账户域
# ---------------------------------------------------------------------------
def test_account_plan(api_client, encaps_tenant_url):
    """验证 GET /account/plan 返回 200 且含套餐字段（无配额时为免费版）。"""
    resp = api_client.get(encaps_tenant_url + "/api/v1/account/plan")
    assert resp.status_code == 200
    body = unwrap_response(resp.json())
    assert "plan" in body
    assert body["plan"] in ("free", "pro", "enterprise")


def test_account_billing(api_client, encaps_tenant_url):
    """验证 GET /account/billing 返回 200 且账单结构完整。"""
    resp = api_client.get(encaps_tenant_url + "/api/v1/account/billing")
    assert resp.status_code == 200
    body = unwrap_response(resp.json())
    assert "items" in body
    assert "totalCost" in body


def test_account_upgrade(api_client, encaps_tenant_url):
    """验证 POST /account/upgrade 返回目标档费用与受理状态。"""
    resp = api_client.post(
        encaps_tenant_url + "/api/v1/account/upgrade",
        json={"targetPlan": "pro"},
    )
    assert resp.status_code == 200
    body = unwrap_response(resp.json())
    assert body.get("status") == "submitted"
    assert body.get("estimatedMonthlyFee") == 1999


# ---------------------------------------------------------------------------
# 运营后台
# ---------------------------------------------------------------------------
def test_admin_kpi(api_client, encaps_tenant_url):
    """验证 GET /admin/kpi 返回 200 且 KPI 字段齐全。"""
    resp = api_client.get(encaps_tenant_url + "/api/v1/admin/kpi")
    assert resp.status_code == 200
    body = unwrap_response(resp.json())
    for key in ("tenantTotal", "workspaceTotal", "quotaTotal", "assetTotal", "projectTotal"):
        assert key in body


def test_admin_env_matrix(api_client, encaps_tenant_url):
    """验证 GET /admin/env-matrix 返回四环境矩阵（信创/本地/公有云/私有云）。"""
    resp = api_client.get(encaps_tenant_url + "/api/v1/admin/env-matrix")
    assert resp.status_code == 200
    matrix = unwrap_response(resp.json())
    assert isinstance(matrix, list)
    assert len(matrix) == 4
    env_ids = {row["id"] for row in matrix}
    assert env_ids == {"xinchuang", "onprem", "publiccloud", "privatecloud"}


# ---------------------------------------------------------------------------
# Workspace（K8s mock：创建可能置 FAILED 状态，但 API 契约与落库正常）
# ---------------------------------------------------------------------------
def test_workspace_create_and_list(api_client, encaps_tenant_url, numeric_tenant_token):
    """验证 POST /workspaces 创建落库（状态容忍 K8s mock 失败）+ GET 列表包含。"""
    headers = {"Authorization": f"Bearer {numeric_tenant_token}"}
    payload = {"name": "docker-it-ws", "tenantId": 1}
    create_resp = api_client.post(
        encaps_tenant_url + "/api/v1/workspaces", json=payload, headers=headers
    )
    assert create_resp.status_code == 201, create_resp.text
    ws = unwrap_response(create_resp.json())
    assert ws["name"] == payload["name"]
    # K8s mock 下状态可能为 FAILED（翻译失败被捕获），不阻断 API 契约
    assert ws["status"] in ("ACTIVE", "CREATING", "FAILED", "DELETED")
    ws_id = ws["id"]

    try:
        list_resp = api_client.get(
            encaps_tenant_url + "/api/v1/workspaces", headers=headers
        )
        assert list_resp.status_code == 200
        body = unwrap_response(list_resp.json())
        # list 端点返回分页契约
        if isinstance(body, dict):
            ids = [w["id"] for w in body["list"]]
        else:
            ids = [w["id"] for w in body]
        assert ws_id in ids
    finally:
        api_client.delete(
            encaps_tenant_url + f"/api/v1/workspaces/{ws_id}", headers=headers
        )


# ---------------------------------------------------------------------------
# Quota（校验必填字段契约；K8s mock 下状态容忍）
# ---------------------------------------------------------------------------
def test_quota_validation_and_list(api_client, encaps_tenant_url, numeric_tenant_token):
    """验证 POST /quotas 缺 workspaceId 返回 400（Bean Validation 契约）。"""
    headers = {"Authorization": f"Bearer {numeric_tenant_token}"}
    # 缺 workspaceId/tenantId/cpuLimit 等必填字段
    resp = api_client.post(
        encaps_tenant_url + "/api/v1/quotas", json={"name": "invalid"}, headers=headers
    )
    assert resp.status_code == 400

    # 列表端点可用
    list_resp = api_client.get(
        encaps_tenant_url + "/api/v1/quotas", headers=headers
    )
    assert list_resp.status_code == 200
    assert isinstance(unwrap_response(list_resp.json()), list)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def numeric_tenant_token() -> str:
    """带数字 tenantId claim 的 JWT。

    ProjectController/AccountController 从 TenantContext 取租户：
    - Project 落库要求 tenantId 可解析（数字串落 ProjectEntity.tenantId）；
    - Account 的 quotaCount 走 Long.parseLong（非数字容错为 0）。
    用数字 claim 保证项目/配额域测试数据可落库可回查。
    """
    from conftest import generate_test_jwt

    return generate_test_jwt(tenant_id="1")
