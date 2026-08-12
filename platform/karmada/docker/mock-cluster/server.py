"""Mock 成员集群服务。

模拟 Karmada 成员集群的 API，用于集成测试。
每个容器运行一个 mock 集群，通过环境变量配置集群元数据。

端点：
  GET /healthz                          健康检查
  GET /apis/cluster                     集群信息（名称、标签、状态）
  GET /apis/deployments                 部署列表（模拟副本分配结果）
  POST /apis/deployments                接收部署（模拟 Karmada 推送工作负载）
  GET /apis/propagation-policies        传播策略列表（模拟已同步的策略）
  POST /apis/propagation-policies       接收传播策略
  POST /query                           执行 SQL 查询（T034 跨集群查询路由目标）
"""

from __future__ import annotations

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


# ---------------------------------------------------------------------------
# 集群元数据（从环境变量读取）
# ---------------------------------------------------------------------------
CLUSTER_NAME = os.environ.get("CLUSTER_NAME", "unknown-cluster")
CLUSTER_TYPE = os.environ.get("CLUSTER_TYPE", "unknown")
CLUSTER_VENDOR = os.environ.get("CLUSTER_VENDOR", "unknown")
CLUSTER_ARCH = os.environ.get("CLUSTER_ARCH", "amd64")
CLUSTER_REGION = os.environ.get("CLUSTER_REGION", "unknown")
CLUSTER_ENV = os.environ.get("CLUSTER_ENV", "staging")
CLUSTER_MAX_REPLICAS = int(os.environ.get("CLUSTER_MAX_REPLICAS", "100"))

# 集群标签（与 karmadactl-join-config.yaml 对齐）
CLUSTER_LABELS = {
    "cluster.karmada.io/type": CLUSTER_TYPE,
    "cluster.karmada.io/vendor": CLUSTER_VENDOR,
    "cluster.karmada.io/arch": CLUSTER_ARCH,
    "cluster.karmada.io/region": CLUSTER_REGION,
    "cluster.karmada.io/env": CLUSTER_ENV,
}

# ---------------------------------------------------------------------------
# 内存存储（线程安全）
# ---------------------------------------------------------------------------
_lock = threading.Lock()
_deployments: dict[str, dict] = {}
_policies: dict[str, dict] = {}


class ClusterHandler(BaseHTTPRequestHandler):
    """Mock 集群 HTTP 请求处理器。"""

    def _send_json(self, status: int, body: dict | list) -> None:
        """发送 JSON 响应。"""
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_body(self) -> dict:
        """读取请求体 JSON。"""
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}

    # ------------------------------------------------------------------
    # GET 路由
    # ------------------------------------------------------------------
    def do_GET(self) -> None:
        path = urlparse(self.path).path

        if path == "/healthz":
            self._send_json(200, {"status": "ok", "cluster": CLUSTER_NAME})
            return

        if path == "/apis/cluster":
            self._send_json(200, {
                "name": CLUSTER_NAME,
                "labels": CLUSTER_LABELS,
                "maxReplicas": CLUSTER_MAX_REPLICAS,
                "conditions": [
                    {"type": "Ready", "status": "True"},
                    {"type": "Syncable", "status": "True"},
                ],
                "arch": CLUSTER_ARCH,
                "vendor": CLUSTER_VENDOR,
            })
            return

        if path == "/apis/deployments":
            with _lock:
                items = list(_deployments.values())
            self._send_json(200, {"items": items, "total": len(items)})
            return

        if path == "/apis/propagation-policies":
            with _lock:
                items = list(_policies.values())
            self._send_json(200, {"items": items, "total": len(items)})
            return

        self._send_json(404, {"error": "not found"})

    # ------------------------------------------------------------------
    # POST 路由
    # ------------------------------------------------------------------
    def do_POST(self) -> None:
        path = urlparse(self.path).path
        body = self._read_body()

        if path == "/apis/deployments":
            name = body.get("name", "unnamed")
            with _lock:
                _deployments[name] = {
                    "name": name,
                    "cluster": CLUSTER_NAME,
                    "replicas": body.get("replicas", 1),
                    "spec": body.get("spec", {}),
                }
            self._send_json(201, _deployments[name])
            return

        if path == "/apis/propagation-policies":
            name = body.get("name", "unnamed")
            with _lock:
                _policies[name] = {
                    "name": name,
                    "cluster": CLUSTER_NAME,
                    "spec": body.get("spec", {}),
                }
            self._send_json(201, _policies[name])
            return

        if path == "/query":
            # T034 跨集群查询路由目标端点
            # 接收 {"sql": "...", "database": "..."}，返回 mock 查询结果
            sql = body.get("sql", "")
            database = body.get("database", "default")
            result = execute_mock_query(sql, database)
            self._send_json(200, result)
            return

        self._send_json(404, {"error": "not found"})

    # ------------------------------------------------------------------
    # DELETE 路由
    # ------------------------------------------------------------------
    def do_DELETE(self) -> None:
        path = urlparse(self.path).path

        # /apis/deployments/{name}
        if path.startswith("/apis/deployments/"):
            name = path[len("/apis/deployments/"):]
            with _lock:
                if name in _deployments:
                    del _deployments[name]
                    self._send_json(204, {})
                    return
            self._send_json(404, {"error": "not found"})
            return

        # /apis/propagation-policies/{name}
        if path.startswith("/apis/propagation-policies/"):
            name = path[len("/apis/propagation-policies/"):]
            with _lock:
                if name in _policies:
                    del _policies[name]
                    self._send_json(204, {})
                    return
            self._send_json(404, {"error": "not found"})
            return

        self._send_json(404, {"error": "not found"})

    def log_message(self, format, *args) -> None:
        """静默日志（避免测试输出噪声）。"""
        pass


def main() -> None:
    """启动 mock 集群 HTTP 服务。"""
    port = int(os.environ.get("CLUSTER_PORT", "8090"))
    server = ThreadingHTTPServer(("0.0.0.0", port), ClusterHandler)
    print(f"[mock-cluster] {CLUSTER_NAME} (type={CLUSTER_TYPE}, arch={CLUSTER_ARCH}) "
          f"listening on :{port}")
    server.serve_forever()


# ---------------------------------------------------------------------------
# Mock 查询执行（T034 跨集群查询路由目标）
# ---------------------------------------------------------------------------
def execute_mock_query(sql: str, database: str) -> dict:
    """执行 mock SQL 查询，返回模拟结果。

    根据 SQL 中的表名返回预置的 mock 数据，用于 T034 跨集群查询集成测试。
    每个集群返回带集群标识的数据，便于验证跨集群归并。

    Args:
        sql: SQL 查询语句
        database: 默认数据库

    Returns:
        {"status": "ok", "schema": {...}, "rows": [...], "rowCount": N}
    """
    sql_lower = sql.lower()

    # 根据 SQL 中的表名返回对应 mock 数据
    if "orders_east" in sql_lower or "orders" in sql_lower:
        # 订单表：每个集群返回带集群标识的订单
        rows = [
            {"id": i, "cluster": CLUSTER_NAME, "amount": 100 + i}
            for i in range(1, 4)
        ]
        return {
            "status": "ok",
            "schema": {"id": "INT", "cluster": "STRING", "amount": "INT"},
            "rows": rows,
            "rowCount": len(rows),
        }

    if "orders_west" in sql_lower:
        rows = [
            {"id": i, "cluster": CLUSTER_NAME, "amount": 200 + i}
            for i in range(1, 3)
        ]
        return {
            "status": "ok",
            "schema": {"id": "INT", "cluster": "STRING", "amount": "INT"},
            "rows": rows,
            "rowCount": len(rows),
        }

    if "customers" in sql_lower:
        rows = [
            {"id": 1, "name": f"customer-{CLUSTER_NAME}", "region": CLUSTER_REGION},
            {"id": 2, "name": f"client-{CLUSTER_NAME}", "region": CLUSTER_REGION},
        ]
        return {
            "status": "ok",
            "schema": {"id": "INT", "name": "STRING", "region": "STRING"},
            "rows": rows,
            "rowCount": len(rows),
        }

    if "count" in sql_lower or "aggregate" in sql_lower:
        # 聚合查询：返回单行计数
        return {
            "status": "ok",
            "schema": {"count": "INT", "cluster": "STRING"},
            "rows": [{"count": 10, "cluster": CLUSTER_NAME}],
            "rowCount": 1,
        }

    # 默认：返回简单结果
    return {
        "status": "ok",
        "schema": {"result": "STRING"},
        "rows": [{"result": f"from-{CLUSTER_NAME}"}],
        "rowCount": 1,
    }


if __name__ == "__main__":
    main()