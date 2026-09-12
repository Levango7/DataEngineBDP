"""租户隔离校验测试.

验证：
1. MockSqlGenerator 在传入 tenantId 时，生成的 SQL 包含 tenant_id 过滤条件。
2. 不同租户生成不同 SQL（tenant_id 值不同）。
3. tenantId 为 None 时不注入租户过滤（兼容无鉴权的本地调试场景）。
4. /nl2sql/generate 端点在 JWT 鉴权下，生成的 SQL 包含 token 声明的 tenant_id。
5. /nl2sql/convert 端点同样注入租户过滤。
6. /nl2sql/execute 端点生成的 SQL 包含租户过滤，且透传给网关。
7. admin 可通过请求体指定他人租户，生成的 SQL 包含指定租户的过滤条件。

来源：2026-09-10-fastapi-auth-fix-test-design-matrix（每个受保护端点至少有
1 个无 token → 401 用例 + 1 个 user token → 2xx 用例）。
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from models import (
    ColumnSchema,
    Intent,
    IntentType,
    SchemaContext,
    TableSchema,
)


def _mockCtx() -> SchemaContext:
    """构造含 orders 表的 mock schema 上下文."""
    return SchemaContext(
        database="default",
        tables=[
            TableSchema(
                databaseName="default",
                tableName="orders",
                columns=[
                    ColumnSchema(name="order_id", type="bigint"),
                    ColumnSchema(name="user_id", type="bigint"),
                    ColumnSchema(name="amount", type="decimal"),
                    ColumnSchema(name="dt", type="date"),
                ],
                partitionKeys=["dt"],
            ),
        ],
    )


# ============================================================
# MockSqlGenerator 租户过滤单测
# ============================================================
class TestMockGeneratorTenantFilter:
    """MockSqlGenerator 在传入 tenantId 时应注入 tenant_id 过滤条件."""

    async def test_tenant_id_in_where_clause(self, mockGenerator) -> None:
        """生成的 SQL 应包含 tenant_id = ? 占位符，参数值在 params 中."""
        ctx = _mockCtx()
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        result = await mockGenerator.generate("查询 orders", ctx, intent, tenantId="tenant-a")
        assert "tenant_id" in result.sql
        assert "tenant_id = ?" in result.sql
        assert "tenant-a" in result.params
        assert "WHERE" in result.sql.upper()

    async def test_different_tenants_produce_different_sql(self, mockGenerator) -> None:
        """不同租户生成的 SQL 占位符相同，但 params 值不同."""
        ctx = _mockCtx()
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        resultA = await mockGenerator.generate("查询 orders", ctx, intent, tenantId="tenant-a")
        resultB = await mockGenerator.generate("查询 orders", ctx, intent, tenantId="tenant-b")
        assert "tenant-a" in resultA.params
        assert "tenant-b" in resultB.params
        assert resultA.params != resultB.params

    async def test_no_tenant_id_when_none(self, mockGenerator) -> None:
        """tenantId 为 None 时不注入租户过滤（兼容无鉴权场景）."""
        ctx = _mockCtx()
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        result = await mockGenerator.generate("查询 orders", ctx, intent, tenantId=None)
        assert "tenant_id" not in result.sql

    async def test_tenant_filter_with_aggregation(self, mockGenerator) -> None:
        """聚合查询也应包含租户过滤条件."""
        from models import AggFunc

        ctx = _mockCtx()
        intent = Intent(
            primaryType=IntentType.AGGREGATION,
            aggFunc=AggFunc.COUNT,
        )
        result = await mockGenerator.generate("统计订单数", ctx, intent, tenantId="tenant-x")
        assert "COUNT" in result.sql.upper()
        assert "tenant_id" in result.sql
        assert "tenant-x" in result.params

    async def test_tenant_filter_combines_with_time_range(self, mockGenerator) -> None:
        """租户过滤应与时间范围条件用 AND 连接."""
        from models import Slot, SlotFrame, SlotStatus

        ctx = _mockCtx()
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = SlotFrame(
            slots=[
                Slot(name="timeRange", required=False, status=SlotStatus.FILLED, value="yesterday"),
            ],
            intent=intent,
        )
        result = await mockGenerator.generate("查询昨天的数据", ctx, intent, frame, tenantId="tenant-a")
        assert "tenant_id" in result.sql
        assert "tenant-a" in result.params
        assert "AND" in result.sql.upper()
        assert "dt" in result.sql


# ============================================================
# API 端点租户隔离集成测试
# ============================================================
class TestGenerateEndpointTenantIsolation:
    """/nl2sql/generate 端点在 JWT 鉴权下的租户隔离."""

    def _jwtHeaders(self, tenant: str, role: str = "user") -> dict:
        from nl2sql.tests.test_jwt_auth import SECRET, makeToken

        return {"Authorization": f"Bearer {makeToken(secret=SECRET, tenant=tenant, role=role)}"}

    def test_generate_with_jwt_includes_tenant_filter(self, app, monkeypatch) -> None:
        """user token 生成的 SQL 应包含 token 声明的 tenant_id."""
        from nl2sql.tests.test_jwt_auth import SECRET

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", SECRET)
        c = TestClient(app)
        resp = c.post(
            "/api/v1/nl2sql/generate",
            json={"query": "查询 orders 表", "useMockSchema": True},
            headers=self._jwtHeaders("tenant-a"),
        )
        assert resp.status_code == 200
        data = resp.json()
        sql = data["sql"]
        assert "tenant_id" in sql
        assert "tenant-a" in data.get("params", [])

    def test_generate_different_tenants_different_sql(self, app, monkeypatch) -> None:
        """不同租户的 token 生成包含不同 tenant_id 的 SQL."""
        from nl2sql.tests.test_jwt_auth import SECRET

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", SECRET)
        c = TestClient(app)
        respA = c.post(
            "/api/v1/nl2sql/generate",
            json={"query": "查询 orders 表", "useMockSchema": True},
            headers=self._jwtHeaders("tenant-a"),
        )
        respB = c.post(
            "/api/v1/nl2sql/generate",
            json={"query": "查询 orders 表", "useMockSchema": True},
            headers=self._jwtHeaders("tenant-b"),
        )
        assert respA.status_code == 200
        assert respB.status_code == 200
        dataA = respA.json()
        dataB = respB.json()
        assert "tenant-a" in dataA.get("params", [])
        assert "tenant-b" in dataB.get("params", [])
        assert dataA.get("params", []) != dataB.get("params", [])

    def test_generate_admin_can_specify_other_tenant(self, app, monkeypatch) -> None:
        """admin 可通过请求体 tenantId 指定他人租户，SQL 包含指定租户."""
        from nl2sql.tests.test_jwt_auth import SECRET

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", SECRET)
        c = TestClient(app)
        resp = c.post(
            "/api/v1/nl2sql/generate",
            json={"query": "查询 orders 表", "useMockSchema": True, "tenantId": "tenant-other"},
            headers=self._jwtHeaders("tenant-admin", role="admin"),
        )
        assert resp.status_code == 200
        data = resp.json()
        sql = data["sql"]
        assert "tenant-other" in data.get("params", [])

    def test_generate_user_cannot_override_tenant(self, app, monkeypatch) -> None:
        """普通 user 请求体指定他人租户应被忽略，使用 token 声明的租户."""
        from nl2sql.tests.test_jwt_auth import SECRET

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", SECRET)
        c = TestClient(app)
        resp = c.post(
            "/api/v1/nl2sql/generate",
            json={"query": "查询 orders 表", "useMockSchema": True, "tenantId": "tenant-other"},
            headers=self._jwtHeaders("tenant-a", role="user"),
        )
        assert resp.status_code == 200
        data = resp.json()
        sql = data["sql"]
        # 普通用户强制使用 token 声明的 tenant-a，而非请求体的 tenant-other
        assert "tenant-a" in data.get("params", [])
        assert "tenant-other" not in data.get("params", [])


class TestConvertEndpointTenantIsolation:
    """/nl2sql/convert 端点租户隔离（ai-assistant Go 代理调用入口）."""

    def _jwtHeaders(self, tenant: str, role: str = "user") -> dict:
        from nl2sql.tests.test_jwt_auth import SECRET, makeToken

        return {"Authorization": f"Bearer {makeToken(secret=SECRET, tenant=tenant, role=role)}"}

    def test_convert_with_jwt_includes_tenant_filter(self, app, monkeypatch) -> None:
        """convert 端点生成的 SQL 应包含 tenant_id 过滤条件."""
        from nl2sql.tests.test_jwt_auth import SECRET

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", SECRET)
        c = TestClient(app)
        resp = c.post(
            "/api/v1/nl2sql/convert",
            json={"query": "查询 orders 表"},
            headers=self._jwtHeaders("tenant-a"),
        )
        assert resp.status_code == 200
        data = resp.json()
        sql = data["sql"]
        assert "tenant_id" in sql
        assert "tenant-a" in data.get("params", [])

    def test_convert_different_tenants_different_sql(self, app, monkeypatch) -> None:
        """不同租户调用 convert 生成不同 SQL."""
        from nl2sql.tests.test_jwt_auth import SECRET

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", SECRET)
        c = TestClient(app)
        respA = c.post(
            "/api/v1/nl2sql/convert",
            json={"query": "查询 orders 表"},
            headers=self._jwtHeaders("tenant-a"),
        )
        respB = c.post(
            "/api/v1/nl2sql/convert",
            json={"query": "查询 orders 表"},
            headers=self._jwtHeaders("tenant-b"),
        )
        assert respA.status_code == 200
        assert respB.status_code == 200
        assert respA.json().get("params", []) != respB.json().get("params", [])


class TestExecuteEndpointTenantIsolation:
    """/nl2sql/execute 端点租户隔离."""

    def _jwtHeaders(self, tenant: str, role: str = "user") -> dict:
        from nl2sql.tests.test_jwt_auth import SECRET, makeToken

        return {"Authorization": f"Bearer {makeToken(secret=SECRET, tenant=tenant, role=role)}"}

    def test_execute_includes_tenant_filter_in_sql(self, app, monkeypatch) -> None:
        """execute 端点生成的 SQL 应包含 tenant_id 过滤条件."""
        from nl2sql.tests.test_jwt_auth import SECRET

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", SECRET)
        c = TestClient(app)
        resp = c.post(
            "/api/v1/nl2sql/execute",
            json={"query": "查询 orders 表", "useMockSchema": True},
            headers=self._jwtHeaders("tenant-a"),
        )
        assert resp.status_code == 200
        data = resp.json()
        sql = data["sql"]
        assert "tenant_id" in sql
        assert "tenant-a" in data.get("params", [])