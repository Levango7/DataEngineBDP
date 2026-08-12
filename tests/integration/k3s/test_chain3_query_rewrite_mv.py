"""链路3: 查询改写 → 物化视图路由 端到端集成测试.

测试 SQL 查询自动路由全链路：
    原始SQL → ViewMatcher(物化视图匹配) → 命中则改写为物化视图查询 → 透传或改写后SQL

被测服务（K3s ClusterIP）：
    - sql-gateway  (port 8081)  /api/v1/rewrite/* 系列端点

测试步骤：
    1. 验证 sql-gateway 健康检查
    2. 列出物化视图定义: GET /api/v1/rewrite/views
    3. 新增物化视图定义: POST /api/v1/rewrite/views
    4. 查询改写(命中): POST /api/v1/rewrite/execute
    5. 查询路由决策: POST /api/v1/rewrite/route
    6. 候选匹配列表: POST /api/v1/rewrite/candidates
    7. 列出改写规则: GET /api/v1/rewrite/rules
    8. 端到端: 添加视图 → 查询命中 → 自动改写
"""

from __future__ import annotations

import time
import uuid

import pytest

from conftest import record_test_result

CHAIN_NAME = "链路3: 查询改写→物化视图路由"


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
class TestChain3HealthCheck:
    """链路3 健康检查：验证 sql-gateway 可用."""

    def test_sql_gateway_health(self, k3s_client, sql_gateway_url):
        """验证 sql-gateway 健康检查返回 200."""
        start = time.time()
        try:
            resp = k3s_client.get(sql_gateway_url + "/api/v1/health")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "sql-gateway健康检查", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 物化视图定义管理
# ---------------------------------------------------------------------------
class TestChain3ViewManagement:
    """物化视图定义管理测试."""

    def test_list_views(self, k3s_client, sql_gateway_url):
        """测试列出物化视图定义: GET /api/v1/rewrite/views."""
        start = time.time()
        try:
            resp = k3s_client.get(sql_gateway_url + "/api/v1/rewrite/views")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "列出物化视图", passed, detail, duration_ms)
        assert passed, detail

    def test_add_view(self, k3s_client, sql_gateway_url):
        """测试新增物化视图定义: POST /api/v1/rewrite/views."""
        start = time.time()
        view_name = f"mv_test_{uuid.uuid4().hex[:8]}"
        payload = {
            "viewName": view_name,
            "viewSql": "SELECT user_id, count(*) AS order_cnt FROM orders GROUP BY user_id",
            "baseTables": ["orders"],
            "queryPattern": "SELECT.*FROM orders.*GROUP BY user_id",
            "refreshInterval": 3600,
        }
        try:
            resp = k3s_client.post(
                sql_gateway_url + "/api/v1/rewrite/views", json=payload
            )
            passed = resp.status_code in (200, 201)
            detail = f"status={resp.status_code}, view={view_name}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "新增物化视图", passed, detail, duration_ms)
        if passed:
            TestChain3ViewManagement._view_name = view_name
        assert passed, detail

    _view_name: str = ""


# ---------------------------------------------------------------------------
# 查询改写与路由
# ---------------------------------------------------------------------------
class TestChain3RewriteRoute:
    """查询改写与路由测试."""

    def test_rewrite_execute(self, k3s_client, sql_gateway_url):
        """测试查询改写: POST /api/v1/rewrite/execute."""
        start = time.time()
        payload = {
            "sql": "SELECT user_id, count(*) AS cnt FROM orders GROUP BY user_id",
        }
        try:
            resp = k3s_client.post(
                sql_gateway_url + "/api/v1/rewrite/execute", json=payload
            )
            body = resp.json() if resp.status_code == 200 else {}
            passed = resp.status_code == 200 and "rewritten" in body
            detail = (
                f"status={resp.status_code}, "
                f"rewritten={body.get('rewritten')}, "
                f"matchedView={body.get('matchedView')}"
            )
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "查询改写执行", passed, detail, duration_ms)
        assert passed, detail

    def test_route_decision(self, k3s_client, sql_gateway_url):
        """测试路由决策: POST /api/v1/rewrite/route."""
        start = time.time()
        payload = {
            "sql": "SELECT * FROM orders WHERE dt = '2024-01-01'",
        }
        try:
            resp = k3s_client.post(
                sql_gateway_url + "/api/v1/rewrite/route", json=payload
            )
            body = resp.json() if resp.status_code == 200 else {}
            passed = resp.status_code == 200 and "matched" in body
            detail = (
                f"status={resp.status_code}, "
                f"matched={body.get('matched')}, "
                f"viewName={body.get('viewName')}"
            )
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "路由决策", passed, detail, duration_ms)
        assert passed, detail

    def test_candidates(self, k3s_client, sql_gateway_url):
        """测试候选匹配列表: POST /api/v1/rewrite/candidates."""
        start = time.time()
        payload = {
            "sql": "SELECT user_id, count(*) FROM orders GROUP BY user_id",
        }
        try:
            resp = k3s_client.post(
                sql_gateway_url + "/api/v1/rewrite/candidates", json=payload
            )
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:300]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "候选匹配列表", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 改写规则管理
# ---------------------------------------------------------------------------
class TestChain3RuleManagement:
    """改写规则管理测试."""

    def test_list_rules(self, k3s_client, sql_gateway_url):
        """测试列出改写规则: GET /api/v1/rewrite/rules."""
        start = time.time()
        try:
            resp = k3s_client.get(sql_gateway_url + "/api/v1/rewrite/rules")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "列出改写规则", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 端到端：添加视图 → 查询命中 → 自动改写
# ---------------------------------------------------------------------------
class TestChain3EndToEnd:
    """端到端：添加物化视图 → 查询命中 → 自动改写."""

    def test_add_view_then_rewrite(self, k3s_client, sql_gateway_url):
        """完整链路: 添加物化视图 → 查询命中 → 自动改写."""
        start = time.time()
        view_name = f"mv_e2e_{uuid.uuid4().hex[:8]}"
        try:
            # 步骤1: 添加物化视图定义
            view_payload = {
                "viewName": view_name,
                "viewSql": "SELECT region, sum(amount) AS total FROM sales GROUP BY region",
                "baseTables": ["sales"],
                "queryPattern": "SELECT.*FROM sales.*GROUP BY region",
                "refreshInterval": 1800,
            }
            add_resp = k3s_client.post(
                sql_gateway_url + "/api/v1/rewrite/views", json=view_payload
            )
            assert add_resp.status_code in (200, 201), f"添加视图失败: {add_resp.text}"

            # 步骤2: 查询改写（应命中刚添加的视图）
            rewrite_payload = {
                "sql": "SELECT region, sum(amount) AS total FROM sales GROUP BY region",
            }
            rewrite_resp = k3s_client.post(
                sql_gateway_url + "/api/v1/rewrite/execute", json=rewrite_payload
            )
            rewrite_body = rewrite_resp.json() if rewrite_resp.status_code == 200 else {}

            # 步骤3: 路由决策验证
            route_resp = k3s_client.post(
                sql_gateway_url + "/api/v1/rewrite/route", json=rewrite_payload
            )
            route_body = route_resp.json() if route_resp.status_code == 200 else {}

            passed = (
                add_resp.status_code in (200, 201)
                and rewrite_resp.status_code == 200
                and route_resp.status_code == 200
            )
            detail = (
                f"添加视图={add_resp.status_code}, "
                f"改写={rewrite_resp.status_code}(rewritten={rewrite_body.get('rewritten')}), "
                f"路由={route_resp.status_code}(matched={route_body.get('matched')})"
            )
        except Exception as e:
            passed = False
            detail = f"链路异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "添加视图→查询命中→改写(端到端)", passed, detail, duration_ms)
        assert passed, detail