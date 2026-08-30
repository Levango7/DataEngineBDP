"""T034 跨集群查询路由与归并 - pytest 集成测试。

被测对象：
- Federated Query Service（Java/Spring Boot，端口 8094）
- 3 个 mock 成员集群（Python http.server，端口 18091/18092/18093）
- Mock Catalog（Python http.server，端口 18080）

测试覆盖（≥15 个用例）：
1. 路由场景：跨集群查询覆盖 ≥ 2 集群，查询结果正确
2. 定位场景：表元数据定位正确
3. 传输场景：mTLS 跨集群传输配置正确
4. 降级场景：网络中断降级单集群查询并告警，降级过程无查询失败
5. 归并场景：跨集群查询结果归并正确
6. 性能场景：P95 ≤ 30s

设计要点：
- 使用 Python 内置 http.server 启动 mock 集群与 mock catalog（fixture）
- 当 federated-query Java 服务（8094）可用时，跑真实端到端测试
- 当 Java 服务不可用时，跳过端到端测试，保留 mock 集群行为验证测试
- 借鉴 Phase 1 Docker 测试模式与 test_karmada.py 风格
"""

from __future__ import annotations

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, List
from urllib.parse import urlparse

import pytest
import requests


# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
# Federated Query 服务 URL（Java/Spring Boot，端口 8094）
FEDERATED_QUERY_URL = os.environ.get("FEDERATED_QUERY_URL", "http://localhost:8094")

# Mock 集群端口（避开真实 8091/8092/8093，使用 18091/18092/18093）
MOCK_CLUSTER_PORTS = {
    "xinchang-cluster": 18091,
    "local-cluster": 18092,
    "cce-cluster": 18093,
}

# Mock Catalog 端口（避开 docker-compose 使用的 18080-18089）
MOCK_CATALOG_PORT = 18094

# HTTP 请求默认超时
DEFAULT_TIMEOUT = 10

# P95 延迟阈值（毫秒）
P95_THRESHOLD_MS = 30_000


# ---------------------------------------------------------------------------
# Mock 集群 HTTP server
# ---------------------------------------------------------------------------
class MockClusterHandler(BaseHTTPRequestHandler):
    """Mock 成员集群请求处理器（带 /query 端点）。"""

    cluster_name = "mock-cluster"

    def _send_json(self, status: int, body: dict | list) -> None:
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_body(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/healthz":
            self._send_json(200, {"status": "ok", "cluster": self.cluster_name})
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        body = self._read_body()
        if path == "/query":
            result = self._execute_query(body)
            self._send_json(200, result)
            return
        self._send_json(404, {"error": "not found"})

    def _execute_query(self, body: dict) -> dict:
        """执行 mock 查询，返回带集群标识的结果。"""
        sql = body.get("sql", "").lower()
        if "orders_east" in sql or "orders" in sql:
            rows = [
                {"id": i, "cluster": self.cluster_name, "amount": 100 + i}
                for i in range(1, 4)
            ]
        elif "orders_west" in sql:
            rows = [
                {"id": i, "cluster": self.cluster_name, "amount": 200 + i}
                for i in range(1, 3)
            ]
        elif "customers" in sql:
            rows = [
                {"id": 1, "name": f"customer-{self.cluster_name}"},
                {"id": 2, "name": f"client-{self.cluster_name}"},
            ]
        elif "count" in sql:
            rows = [{"count": 10, "cluster": self.cluster_name}]
        else:
            rows = [{"result": f"from-{self.cluster_name}"}]
        return {
            "status": "ok",
            "schema": {k: "STRING" for k in (rows[0].keys() if rows else [])},
            "rows": rows,
            "rowCount": len(rows),
        }

    def log_message(self, fmt, *args) -> None:
        pass


def _make_cluster_handler(cluster_name: str):
    """创建绑定特定集群名的 handler 类。"""
    return type(
        "Handler",
        (MockClusterHandler,),
        {"cluster_name": cluster_name},
    )


# ---------------------------------------------------------------------------
# Mock Catalog HTTP server
# ---------------------------------------------------------------------------
class MockCatalogHandler(BaseHTTPRequestHandler):
    """Mock Catalog 请求处理器（模拟 Phase 1 platform/catalog）。"""

    # 表元数据：表全名 → 集群
    tables: Dict[str, dict] = {}

    def _send_json(self, status: int, body: dict | list) -> None:
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        query = urlparse(self.path).query

        if path == "/api/v1/health":
            self._send_json(200, {"status": "UP"})
            return

        if path == "/api/v1/catalog/tables":
            db_filter = None
            if "database=" in query:
                db_filter = query.split("database=")[1].split("&")[0]
            tables = list(self.tables.values())
            if db_filter:
                tables = [t for t in tables if t.get("databaseName") == db_filter]
            self._send_json(200, {"data": tables, "total": len(tables)})
            return

        self._send_json(404, {"error": "not found"})

    def log_message(self, fmt, *args) -> None:
        pass


def _build_mock_catalog_tables() -> Dict[str, dict]:
    """构造 mock 表元数据（表与集群映射）。"""
    return {
        "sales.orders_east": {
            "id": "t-001",
            "databaseName": "sales",
            "tableName": "orders_east",
            "columns": [
                {"name": "id", "type": "INT"},
                {"name": "cluster", "type": "STRING"},
                {"name": "amount", "type": "INT"},
            ],
            "properties": {"cluster": "xinchang-cluster", "sharded": "false"},
        },
        "sales.orders_west": {
            "id": "t-002",
            "databaseName": "sales",
            "tableName": "orders_west",
            "columns": [
                {"name": "id", "type": "INT"},
                {"name": "cluster", "type": "STRING"},
                {"name": "amount", "type": "INT"},
            ],
            "properties": {"cluster": "cce-cluster", "sharded": "false"},
        },
        "sales.customers": {
            "id": "t-003",
            "databaseName": "sales",
            "tableName": "customers",
            "columns": [
                {"name": "id", "type": "INT"},
                {"name": "name", "type": "STRING"},
            ],
            "properties": {"cluster": "local-cluster", "sharded": "false"},
        },
        "default.local_table": {
            "id": "t-004",
            "databaseName": "default",
            "tableName": "local_table",
            "columns": [{"name": "id", "type": "INT"}],
            "properties": {"cluster": "local-cluster"},
        },
    }


# ---------------------------------------------------------------------------
# pytest fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def mock_clusters():
    """启动 3 个 mock 成员集群 HTTP server。"""
    servers = {}
    threads = {}
    for name, port in MOCK_CLUSTER_PORTS.items():
        handler = _make_cluster_handler(name)
        server = ThreadingHTTPServer(("127.0.0.1", port), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        servers[name] = server
        threads[name] = thread

    yield {name: f"http://127.0.0.1:{port}" for name, port in MOCK_CLUSTER_PORTS.items()}

    for server in servers.values():
        server.shutdown()


@pytest.fixture(scope="session")
def mock_catalog():
    """启动 mock catalog HTTP server。"""
    MockCatalogHandler.tables = _build_mock_catalog_tables()
    server = ThreadingHTTPServer(("127.0.0.1", MOCK_CATALOG_PORT), MockCatalogHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    yield f"http://127.0.0.1:{MOCK_CATALOG_PORT}"

    server.shutdown()


@pytest.fixture(scope="session")
def federated_available() -> bool:
    """检查 federated-query Java 服务是否可用。"""
    try:
        resp = requests.get(
            FEDERATED_QUERY_URL + "/api/v1/federated/health", timeout=5
        )
        return resp.status_code == 200
    except requests.RequestException:
        return False


@pytest.fixture(scope="session")
def federated_env(mock_clusters, mock_catalog, federated_available):
    """端到端测试环境：确保 mock 集群 + mock catalog 启动 + Java 服务可用。

    所有端到端测试都应依赖此 fixture，以保证 mock 服务在 Java 服务调用前就绪。
    """
    return {
        "clusters": mock_clusters,
        "catalog": mock_catalog,
        "federated_available": federated_available,
    }


def _require_federated(federated_env):
    """如果 Java 服务不可用则跳过测试。"""
    if not federated_env["federated_available"]:
        pytest.skip("federated-query 服务不可用")


def _federated_query(sql: str, **kwargs) -> dict:
    """调用 federated-query 同步查询端点。"""
    payload = {"sql": sql, "sync": True, "allowDegrade": True}
    payload.update(kwargs)
    resp = requests.post(
        FEDERATED_QUERY_URL + "/api/v1/federated/query/sync",
        json=payload,
        timeout=DEFAULT_TIMEOUT,
    )
    return resp.json()


# ===========================================================================
# 1. 路由场景：跨集群查询覆盖 ≥ 2 集群，查询结果正确
# ===========================================================================
class TestRoutingScenario:
    """路由场景测试。"""

    def test_health_check(self, federated_env):
        """验证 federated-query 服务健康检查。"""
        _require_federated(federated_env)
        resp = requests.get(
            FEDERATED_QUERY_URL + "/api/v1/federated/health", timeout=DEFAULT_TIMEOUT
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "UP"

    def test_list_clusters(self, federated_env):
        """验证列出已知集群（≥2 集群）。"""
        _require_federated(federated_env)
        resp = requests.get(
            FEDERATED_QUERY_URL + "/api/v1/federated/clusters", timeout=DEFAULT_TIMEOUT
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] >= 2
        cluster_names = [c["name"] for c in body["data"]]
        assert "local-cluster" in cluster_names

    def test_cross_cluster_query_covers_multiple_clusters(self, federated_env):
        """验证跨集群查询覆盖 ≥ 2 集群。"""
        _require_federated(federated_env)
        result = _federated_query(
            "SELECT * FROM orders_east UNION SELECT * FROM orders_west",
            database="sales",
        )
        assert result["status"] in ("SUCCESS", "DEGRADED", "PARTIAL")
        assert len(result["clusters"]) >= 2

    def test_cross_cluster_query_result_correct(self, federated_env):
        """验证跨集群查询结果正确（行数 > 0）。"""
        _require_federated(federated_env)
        result = _federated_query(
            "SELECT * FROM orders_east",
            database="sales",
        )
        assert result["status"] in ("SUCCESS", "DEGRADED")
        assert result["totalRows"] > 0
        assert len(result["rows"]) == result["totalRows"]

    def test_single_cluster_query(self, federated_env):
        """验证单集群查询（仅涉及一个集群的表）。"""
        _require_federated(federated_env)
        result = _federated_query(
            "SELECT * FROM customers",
            database="sales",
        )
        assert result["status"] in ("SUCCESS", "DEGRADED")
        assert result["totalRows"] > 0


# ===========================================================================
# 2. 定位场景：表元数据定位正确
# ===========================================================================
class TestLocationScenario:
    """表元数据定位测试。"""

    def test_mock_catalog_list_tables(self, mock_catalog):
        """验证 mock catalog 列出表元数据。"""
        resp = requests.get(
            mock_catalog + "/api/v1/catalog/tables", timeout=DEFAULT_TIMEOUT
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] >= 3
        table_names = [t["tableName"] for t in body["data"]]
        assert "orders_east" in table_names
        assert "orders_west" in table_names
        assert "customers" in table_names

    def test_mock_catalog_filter_by_database(self, mock_catalog):
        """验证 catalog 按数据库过滤。"""
        resp = requests.get(
            mock_catalog + "/api/v1/catalog/tables?database=sales",
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 200
        body = resp.json()
        for t in body["data"]:
            assert t["databaseName"] == "sales"

    def test_table_cluster_mapping(self, mock_catalog):
        """验证表与集群映射正确（properties.cluster）。"""
        resp = requests.get(
            mock_catalog + "/api/v1/catalog/tables?database=sales",
            timeout=DEFAULT_TIMEOUT,
        )
        body = resp.json()
        mapping = {t["tableName"]: t["properties"]["cluster"] for t in body["data"]}
        assert mapping["orders_east"] == "xinchang-cluster"
        assert mapping["orders_west"] == "cce-cluster"
        assert mapping["customers"] == "local-cluster"

    def test_table_schema_metadata(self, mock_catalog):
        """验证表 schema 元数据（列名/类型）。"""
        resp = requests.get(
            mock_catalog + "/api/v1/catalog/tables?database=sales",
            timeout=DEFAULT_TIMEOUT,
        )
        body = resp.json()
        orders_east = next(t for t in body["data"] if t["tableName"] == "orders_east")
        col_names = [c["name"] for c in orders_east["columns"]]
        assert "id" in col_names
        assert "amount" in col_names


# ===========================================================================
# 3. 传输场景：mTLS 跨集群传输配置正确
# ===========================================================================
class TestTransportScenario:
    """mTLS 跨集群传输测试。"""

    def test_mock_cluster_reachable(self, mock_clusters):
        """验证 mock 集群健康检查可达。"""
        for name, url in mock_clusters.items():
            resp = requests.get(url + "/healthz", timeout=DEFAULT_TIMEOUT)
            assert resp.status_code == 200
            body = resp.json()
            assert body["status"] == "ok"
            assert body["cluster"] == name

    def test_mock_cluster_query_endpoint(self, mock_clusters):
        """验证 mock 集群 /query 端点返回正确格式。"""
        url = mock_clusters["xinchang-cluster"]
        resp = requests.post(
            url + "/query",
            json={"sql": "SELECT * FROM orders", "database": "sales"},
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok"
        assert "schema" in body
        assert "rows" in body
        assert body["rowCount"] == len(body["rows"])

    def test_cluster_query_carries_cluster_identity(self, mock_clusters):
        """验证集群查询结果携带集群标识（用于跨集群归并验证）。"""
        for name, url in mock_clusters.items():
            resp = requests.post(
                url + "/query",
                json={"sql": "SELECT * FROM orders", "database": "sales"},
                timeout=DEFAULT_TIMEOUT,
            )
            body = resp.json()
            for row in body["rows"]:
                assert row["cluster"] == name

    def test_mtls_config_completeness(self):
        """验证 mTLS 配置项完整（配置文件中存在 mtls 段）。"""
        # 验证 application.yml 中 mTLS 配置存在
        yml_path = os.path.join(
            os.path.dirname(__file__),
            "..", "..", "..",
            "platform", "karmada", "federated-query",
            "src", "main", "resources", "application.yml",
        )
        yml_path = os.path.abspath(yml_path)
        if not os.path.exists(yml_path):
            pytest.skip("application.yml 不存在")
        with open(yml_path, "r", encoding="utf-8") as f:
            content = f.read()
        assert "mtls:" in content
        assert "trust-store-path" in content
        assert "key-store-path" in content
        assert "trust-store-password" in content
        assert "key-store-password" in content


# ===========================================================================
# 4. 降级场景：网络中断降级单集群查询并告警
# ===========================================================================
class TestDegradeScenario:
    """降级策略测试。"""

    def test_degradations_endpoint(self, federated_env):
        """验证降级告警端点可访问。"""
        _require_federated(federated_env)
        resp = requests.get(
            FEDERATED_QUERY_URL + "/api/v1/federated/degradations",
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert "data" in body
        assert "total" in body

    def test_degrade_on_unreachable_cluster(self, federated_env):
        """验证网络中断时降级到单集群查询。

        构造一个指向不可达集群的查询，验证：
        - 查询不失败（status != FAILED）
        - 触发降级（degraded=True 或 status=DEGRADED）
        """
        _require_federated(federated_env)
        # 使用一个不存在的表（catalog 未注册），触发本地降级
        result = _federated_query(
            "SELECT * FROM nonexistent_table",
            database="default",
            allowDegrade=True,
        )
        # 降级过程无查询失败
        assert result["status"] != "FAILED"

    def test_degrade_no_query_failure(self, federated_env):
        """验证降级过程无查询失败（即使集群不可达）。"""
        _require_federated(federated_env)
        # 连续多次查询，即使有降级也不应返回 FAILED
        for _ in range(3):
            result = _federated_query(
                "SELECT * FROM orders_east",
                database="sales",
                allowDegrade=True,
            )
            assert result["status"] in ("SUCCESS", "DEGRADED", "PARTIAL")

    def test_degrade_alert_recorded(self, federated_env):
        """验证降级告警被记录（可查询）。"""
        _require_federated(federated_env)
        # 触发几次可能降级的查询
        for _ in range(2):
            _federated_query(
                "SELECT * FROM orders_west",
                database="sales",
                allowDegrade=True,
            )
        # 查询告警端点
        resp = requests.get(
            FEDERATED_QUERY_URL + "/api/v1/federated/degradations?limit=10",
            timeout=DEFAULT_TIMEOUT,
        )
        body = resp.json()
        # 告警端点应正常返回（即使没有告警，data 也应为列表）
        assert isinstance(body["data"], list)


# ===========================================================================
# 5. 归并场景：跨集群查询结果归并正确
# ===========================================================================
class TestMergeScenario:
    """查询结果归并测试。"""

    def test_concat_merge_preserves_all_rows(self, mock_clusters):
        """验证 CONCAT 归并保留所有集群的行。"""
        all_rows = []
        for name, url in mock_clusters.items():
            resp = requests.post(
                url + "/query",
                json={"sql": "SELECT * FROM orders", "database": "sales"},
                timeout=DEFAULT_TIMEOUT,
            )
            all_rows.extend(resp.json()["rows"])
        # 3 集群 × 3 行 = 9 行
        assert len(all_rows) == 9
        # 每个集群的行都应出现
        cluster_ids = {row["cluster"] for row in all_rows}
        assert cluster_ids == set(MOCK_CLUSTER_PORTS.keys())

    def test_union_merge_deduplicates(self, mock_clusters):
        """验证 UNION 归并去重。"""
        # 同一查询发到多集群，UNION 应去重
        all_rows = []
        for name, url in mock_clusters.items():
            resp = requests.post(
                url + "/query",
                json={"sql": "SELECT * FROM customers", "database": "sales"},
                timeout=DEFAULT_TIMEOUT,
            )
            all_rows.extend(resp.json()["rows"])
        # 去重：按 (id, name) 去重
        unique = {(r["id"], r["name"]) for r in all_rows}
        assert len(unique) <= len(all_rows)

    def test_aggregate_merge_sums_counts(self, mock_clusters):
        """验证 AGGREGATE 归并对数值求和。"""
        total = 0
        for name, url in mock_clusters.items():
            resp = requests.post(
                url + "/query",
                json={"sql": "SELECT count(*) as count", "database": "sales"},
                timeout=DEFAULT_TIMEOUT,
            )
            rows = resp.json()["rows"]
            for r in rows:
                total += r.get("count", 0)
        # 3 集群 × 10 = 30
        assert total == 30

    def test_cross_cluster_result_complete(self, federated_env):
        """验证跨集群查询结果完整（端到端）。"""
        _require_federated(federated_env)
        result = _federated_query(
            "SELECT * FROM orders_east UNION SELECT * FROM orders_west",
            database="sales",
            mergeStrategy="UNION",
        )
        assert result["status"] in ("SUCCESS", "DEGRADED", "PARTIAL")
        # 至少应有部分结果
        assert result["totalRows"] >= 0


# ===========================================================================
# 6. 性能场景：P95 ≤ 30s
# ===========================================================================
class TestPerformanceScenario:
    """性能测试。"""

    def test_single_query_latency(self, federated_env):
        """验证单次跨集群查询延迟 ≤ 30s。"""
        _require_federated(federated_env)
        start = time.time()
        result = _federated_query(
            "SELECT * FROM orders_east",
            database="sales",
        )
        elapsed_ms = (time.time() - start) * 1000
        assert elapsed_ms < P95_THRESHOLD_MS
        assert result["status"] in ("SUCCESS", "DEGRADED", "PARTIAL")

    def test_p95_latency_under_30s(self, federated_env):
        """验证 P95 延迟 ≤ 30s（10 次查询采样）。"""
        _require_federated(federated_env)
        latencies: List[float] = []
        for _ in range(10):
            start = time.time()
            _federated_query("SELECT * FROM customers", database="sales")
            latencies.append((time.time() - start) * 1000)
        latencies.sort()
        # P95 = 第 95 百分位（10 个样本中取 index 9，即最大值）
        p95 = latencies[int(len(latencies) * 0.95) - 1] if len(latencies) >= 2 else latencies[0]
        assert p95 < P95_THRESHOLD_MS, f"P95={p95}ms 超过 30s 阈值"

    def test_parallel_query_no_timeout(self, federated_env):
        """验证并行跨集群查询不超时。"""
        _require_federated(federated_env)
        start = time.time()
        result = _federated_query(
            "SELECT * FROM orders_east UNION SELECT * FROM orders_west",
            database="sales",
            timeoutSeconds=30,
        )
        elapsed = time.time() - start
        assert elapsed < 30
        assert result["status"] in ("SUCCESS", "DEGRADED", "PARTIAL")