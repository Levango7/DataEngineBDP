"""Locust 性能压测脚本 — 舒清大数据平台核心服务。

覆盖三个 P0 核心服务:
  - encaps-layer (8080): /actuator/health, /api/v1/health
  - sql-gateway  (8081): /api/v1/sql/execute, /api/v1/sql/parse, /api/v1/sql/validate
  - rule-engine  (8083): /api/v1/rules, /api/v1/rules/execute, /api/v1/rules/types

用法:
  # 通过环境变量配置服务地址(默认指向 K3s port-forward 本地端口)
  $ENCAPS_HOST=127.0.0.1 $ENCAPS_PORT=18080 \
  $SQLGW_HOST=127.0.0.1  $SQLGW_PORT=18081 \
  $RULE_HOST=127.0.0.1   $RULE_PORT=18083 \
  locust -f locustfile.py --headless -u 1 -r 1 -t 10s --only-summary

  # 或直接指定 Pod IP
  $ENCAPS_HOST=10.42.0.120 $ENCAPS_PORT=8080 ...
"""

from __future__ import annotations

import json
import os
import uuid

from locust import HttpUser, between, task


# ---------------------------------------------------------------------------
# 服务地址配置(环境变量覆盖默认值)
# ---------------------------------------------------------------------------
ENCAPS_HOST = os.getenv("ENCAPS_HOST", "127.0.0.1")
ENCAPS_PORT = int(os.getenv("ENCAPS_PORT", "18080"))

SQLGW_HOST = os.getenv("SQLGW_HOST", "127.0.0.1")
SQLGW_PORT = int(os.getenv("SQLGW_PORT", "18081"))

RULE_HOST = os.getenv("RULE_HOST", "127.0.0.1")
RULE_PORT = int(os.getenv("RULE_PORT", "18083"))

# 租户 ID(多租户隔离与审计)
TENANT_ID = os.getenv("TENANT_ID", "perf-benchmark")


# ---------------------------------------------------------------------------
# encaps-layer 压测用户
# ---------------------------------------------------------------------------
class EncapsLayerUser(HttpUser):
    """encaps-layer 封装层压测。

    测试端点:
      - GET /actuator/health  (Spring Boot Actuator 健康检查)
      - GET /api/v1/health    (自定义健康端点)
    """

    host = f"http://{ENCAPS_HOST}:{ENCAPS_PORT}"
    wait_time = between(0.1, 0.3)

    @task(7)
    def actuator_health(self) -> None:
        """Actuator 健康端点(就绪/存活探针同路径)。"""
        with self.client.get(
            "/actuator/health",
            name="encaps-layer /actuator/health",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(3)
    def api_health(self) -> None:
        """自定义健康端点 /api/v1/health。"""
        with self.client.get(
            "/api/v1/health",
            name="encaps-layer /api/v1/health",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")


# ---------------------------------------------------------------------------
# sql-gateway 压测用户
# ---------------------------------------------------------------------------
class SqlGatewayUser(HttpUser):
    """sql-gateway SQL 网关压测。

    测试端点:
      - GET  /actuator/health
      - POST /api/v1/sql/execute   (SQL 执行,核心)
      - POST /api/v1/sql/parse     (SQL 解析)
      - POST /api/v1/sql/validate  (SQL 校验)
      - GET  /api/v1/sql/engines   (引擎列表)
    """

    host = f"http://{SQLGW_HOST}:{SQLGW_PORT}"
    wait_time = between(0.1, 0.3)

    # 压测用 SQL 集合(覆盖 SELECT/聚合/JOIN 等典型模式)
    SQL_SAMPLES = [
        "SELECT 1",
        "SELECT * FROM orders LIMIT 100",
        "SELECT count(*) FROM orders WHERE dt = '2026-08-07'",
        "SELECT region, sum(amount) FROM sales GROUP BY region",
        "SELECT a.id, b.name FROM orders a JOIN users b ON a.uid = b.id LIMIT 200",
    ]

    @task(2)
    def actuator_health(self) -> None:
        with self.client.get(
            "/actuator/health",
            name="sql-gateway /actuator/health",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(5)
    def sql_execute(self) -> None:
        """SQL 执行 — 核心压测端点。

        后端 Trino/Doris 未部署时,SqlRoutingService 走降级路径快速返回 DEGRADED。
        """
        sql = self.SQL_SAMPLES[uuid.uuid4().int % len(self.SQL_SAMPLES)]
        payload = {
            "sql": sql,
            "engine": "trino",
            "tenantId": TENANT_ID,
            "limit": 100,
        }
        with self.client.post(
            "/api/v1/sql/execute",
            json=payload,
            name="sql-gateway /api/v1/sql/execute",
            catch_response=True,
        ) as resp:
            # 200 即视为成功(含 DEGRADED 降级响应)
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(2)
    def sql_parse(self) -> None:
        """SQL 解析 — 纯内存 AST 构建,无后端依赖。"""
        sql = self.SQL_SAMPLES[uuid.uuid4().int % len(self.SQL_SAMPLES)]
        payload = {"sql": sql, "dialect": "TRINO"}
        with self.client.post(
            "/api/v1/sql/parse",
            json=payload,
            name="sql-gateway /api/v1/sql/parse",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(1)
    def sql_validate(self) -> None:
        """SQL 校验 — 纯内存语法检查。"""
        payload = {"sql": "SELECT * FROM orders LIMIT 10", "dialect": "TRINO"}
        with self.client.post(
            "/api/v1/sql/validate",
            json=payload,
            name="sql-gateway /api/v1/sql/validate",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(1)
    def sql_engines(self) -> None:
        """引擎列表 — 轻量级 GET。"""
        with self.client.get(
            "/api/v1/sql/engines",
            name="sql-gateway /api/v1/sql/engines",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")


# ---------------------------------------------------------------------------
# rule-engine 压测用户
# ---------------------------------------------------------------------------
class RuleEngineUser(HttpUser):
    """rule-engine 规则引擎压测。

    测试端点:
      - GET  /actuator/health
      - GET  /api/v1/rules         (规则列表)
      - GET  /api/v1/rules/types   (规则类型)
      - POST /api/v1/rules/execute (规则执行,核心)
    """

    host = f"http://{RULE_HOST}:{RULE_PORT}"
    wait_time = between(0.1, 0.3)

    @task(2)
    def actuator_health(self) -> None:
        with self.client.get(
            "/actuator/health",
            name="rule-engine /actuator/health",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(3)
    def rules_list(self) -> None:
        """规则列表 — H2 查询,毫秒级。"""
        with self.client.get(
            "/api/v1/rules",
            name="rule-engine /api/v1/rules",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(2)
    def rules_types(self) -> None:
        """规则类型枚举 — 纯静态返回。"""
        with self.client.get(
            "/api/v1/rules/types",
            name="rule-engine /api/v1/rules/types",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(4)
    def rules_execute(self) -> None:
        """规则执行 — 核心压测端点。

        ruleId=1 为常见默认规则;不存在时返回 404/ERROR,仍计入延迟统计。
        """
        payload = {
            "ruleId": 1,
            "context": {"value": 42, "threshold": 100},
            "tenantId": TENANT_ID,
        }
        with self.client.post(
            "/api/v1/rules/execute",
            json=payload,
            name="rule-engine /api/v1/rules/execute",
            catch_response=True,
        ) as resp:
            # 200 与 404 均视为请求完成(404 表示规则不存在,但服务正常响应)
            if resp.status_code in (200, 404):
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")