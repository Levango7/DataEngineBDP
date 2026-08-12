"""链路1: NL2SQL → SQL网关 → 查询 端到端集成测试.

测试自然语言转 SQL 全链路：
    用户自然语言 → NL2SQL 引擎(意图识别+Schema上下文+SQL生成+校验)
                 → SQL 网关(路由+执行) → 返回查询结果

被测服务（K3s ClusterIP）：
    - nl2sql       (port 8093)  POST /api/v1/nl2sql/generate, /api/v1/nl2sql/execute
    - sql-gateway  (port 8081)  POST /api/v1/sql/execute, GET /api/v1/sql/engines

测试步骤：
    1. 验证 nl2sql 健康检查
    2. 验证 sql-gateway 健康检查
    3. NL → SQL 生成（不执行）：POST /api/v1/nl2sql/generate
    4. NL → SQL → 网关执行：POST /api/v1/nl2sql/execute
    5. 直接 SQL 网关执行：POST /api/v1/sql/execute
    6. 端到端链路验证：NL2SQL 生成的 SQL 可被网关执行
"""

from __future__ import annotations

import time

import pytest

from conftest import record_test_result

CHAIN_NAME = "链路1: NL2SQL→SQL网关→查询"


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
class TestChain1HealthCheck:
    """链路1 健康检查：验证两个被测服务均可用."""

    def test_nl2sql_health(self, k3s_client, nl2sql_url):
        """验证 nl2sql 健康检查返回 200."""
        start = time.time()
        try:
            resp = k3s_client.get(nl2sql_url + "/api/v1/health")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "nl2sql健康检查", passed, detail, duration_ms)
        assert passed, detail

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
# NL → SQL 生成（不执行）
# ---------------------------------------------------------------------------
class TestChain1Nl2SqlGenerate:
    """NL → SQL 生成测试：验证自然语言可转换为合法 SQL."""

    def test_generate_simple_query(self, k3s_client, nl2sql_url):
        """测试简单查询: '查询订单总数' → SELECT count(*) FROM orders."""
        start = time.time()
        payload = {
            "query": "查询订单总数",
            "database": "test_db",
            "useMockSchema": True,
            "tenantId": "it-test-tenant",
        }
        try:
            resp = k3s_client.post(
                nl2sql_url + "/api/v1/nl2sql/generate", json=payload
            )
            passed = resp.status_code == 200 and "sql" in resp.text
            detail = f"status={resp.status_code}, body={resp.text[:300]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "NL→SQL生成(简单查询)", passed, detail, duration_ms)
        assert passed, detail

    def test_generate_with_table_hints(self, k3s_client, nl2sql_url):
        """测试带表名提示的查询."""
        start = time.time()
        payload = {
            "query": "统计每个用户的订单金额",
            "database": "test_db",
            "tableHints": ["orders", "users"],
            "useMockSchema": True,
            "tenantId": "it-test-tenant",
        }
        try:
            resp = k3s_client.post(
                nl2sql_url + "/api/v1/nl2sql/generate", json=payload
            )
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:300]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "NL→SQL生成(带表名提示)", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# NL → SQL → 网关执行（端到端）
# ---------------------------------------------------------------------------
class TestChain1Nl2SqlExecute:
    """NL → SQL → 网关执行端到端测试."""

    def test_execute_via_gateway(self, k3s_client, nl2sql_url):
        """测试 NL → SQL → 网关执行全链路: POST /api/v1/nl2sql/execute."""
        start = time.time()
        payload = {
            "query": "查询前10条订单记录",
            "database": "test_db",
            "useMockSchema": True,
            "engine": "trino",
            "tenantId": "it-test-tenant",
            "limit": 10,
        }
        try:
            resp = k3s_client.post(
                nl2sql_url + "/api/v1/nl2sql/execute", json=payload
            )
            body = resp.json() if resp.status_code == 200 else {}
            # 验证响应包含 sql 字段（生成成功）
            passed = resp.status_code == 200 and "sql" in body
            detail = f"status={resp.status_code}, sql={body.get('sql', 'N/A')[:100]}, gateway={body.get('gateway')}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "NL→SQL→网关执行(端到端)", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# SQL 网关直接执行
# ---------------------------------------------------------------------------
class TestChain1SqlGatewayExecute:
    """SQL 网关直接执行测试：验证网关可执行 NL2SQL 生成的 SQL."""

    def test_gateway_execute_trino(self, k3s_client, sql_gateway_url):
        """测试网关执行 Trino SQL."""
        start = time.time()
        payload = {
            "sql": "SELECT count(*) AS cnt FROM orders WHERE dt = '2024-01-01'",
            "engine": "trino",
            "tenantId": "it-test-tenant",
            "limit": 100,
        }
        try:
            resp = k3s_client.post(
                sql_gateway_url + "/api/v1/sql/execute", json=payload
            )
            body = resp.json() if resp.status_code == 200 else {}
            passed = resp.status_code == 200 and "queryId" in body
            detail = f"status={resp.status_code}, queryId={body.get('queryId')}, status={body.get('status')}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "SQL网关执行(trino)", passed, detail, duration_ms)
        assert passed, detail

    def test_gateway_execute_doris(self, k3s_client, sql_gateway_url):
        """测试网关执行 Doris SQL."""
        start = time.time()
        payload = {
            "sql": "SELECT * FROM dashboard_metrics LIMIT 10",
            "engine": "doris",
            "tenantId": "it-test-tenant",
        }
        try:
            resp = k3s_client.post(
                sql_gateway_url + "/api/v1/sql/execute", json=payload
            )
            body = resp.json() if resp.status_code == 200 else {}
            passed = resp.status_code == 200 and body.get("engine") == "doris"
            detail = f"status={resp.status_code}, engine={body.get('engine')}, queryId={body.get('queryId')}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "SQL网关执行(doris)", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 端到端链路验证
# ---------------------------------------------------------------------------
class TestChain1EndToEnd:
    """端到端链路验证：NL2SQL 生成 SQL → SQL 网关执行."""

    def test_full_chain_nl_to_result(self, k3s_client, nl2sql_url, sql_gateway_url):
        """完整链路: 自然语言 → NL2SQL生成SQL → SQL网关执行 → 结果."""
        start = time.time()
        try:
            # 步骤1: NL → SQL 生成
            gen_payload = {
                "query": "查询订单总数",
                "database": "test_db",
                "useMockSchema": True,
                "tenantId": "it-test-tenant",
            }
            gen_resp = k3s_client.post(
                nl2sql_url + "/api/v1/nl2sql/generate", json=gen_payload
            )
            assert gen_resp.status_code == 200, f"NL2SQL生成失败: {gen_resp.text}"
            gen_body = gen_resp.json()
            generated_sql = gen_body.get("sql", "")

            # 步骤2: SQL 网关执行生成的 SQL
            exec_payload = {
                "sql": generated_sql,
                "engine": "trino",
                "tenantId": "it-test-tenant",
                "limit": 100,
            }
            exec_resp = k3s_client.post(
                sql_gateway_url + "/api/v1/sql/execute", json=exec_payload
            )
            exec_body = exec_resp.json() if exec_resp.status_code == 200 else {}

            passed = (
                gen_resp.status_code == 200
                and bool(generated_sql)
                and exec_resp.status_code == 200
                and "queryId" in exec_body
            )
            detail = (
                f"生成SQL={generated_sql[:80]}, "
                f"网关执行status={exec_resp.status_code}, "
                f"queryId={exec_body.get('queryId')}"
            )
        except Exception as e:
            passed = False
            detail = f"链路异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "完整链路(NL→SQL→结果)", passed, detail, duration_ms)
        assert passed, detail