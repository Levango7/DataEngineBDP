"""链路6: 治理闭环——OpenLineage 摄取 → 统一查询面读回 端到端集成测试.

对应 docs/治理闭环修复草案.md 阶段0 的两条断言：
    断言 1  血缘只有一个写入者，且 ingest 端点有真实调用方；
    断言 2  写完必须查得到（GET /upstream|downstream|impact 能读回同一条边）。

被测服务（K3s ClusterIP）：
    - lineage-analyzer (port 8086)

测试步骤：
    1. lineage-analyzer 健康检查（匿名可读，k8s/k3s 就绪探针走这条路径）
    2. 规范形态事件（inputs={"datasets":[...]}）摄取 → 必须真的落边
    3. 含 `/` 的 OpenLineage 数据集全名，用 ?table= 形态查下游/上游/影响分析
    4. 旧的路径变量形态（点限定表名）不回归
    5. 客户端错误必须留在 4xx（缺 table、depth 越界不得变成 500）

为什么单独成一条链路：2026-10-05 之前的症状是"POST 返回 200 且 edges=1，
而所有查询返回 tables=[]"，本机单元层无法覆盖跨进程这一段。
"""

from __future__ import annotations

import time
import uuid

from conftest import record_test_result

CHAIN_NAME = "链路6: 治理闭环→血缘摄取与统一查询面"


def _event(run_tag: str, ns: str, src: str, dst: str, spec_shape: bool = True) -> dict:
    """构造一个 OpenLineage RunEvent.

    Args:
        run_tag: 本次运行的唯一标记（避免与集群里历史数据串味）。
        ns: 数据集 namespace。
        src: 上游数据集名。
        dst: 下游数据集名。
        spec_shape: True 用规范包装对象 {"datasets": [...]}，False 用仓内裸数组。

    Returns:
        RunEvent 字典。
    """
    datasets_in = [{"namespace": ns, "name": src}]
    datasets_out = [{"namespace": ns, "name": dst}]
    return {
        "eventType": "COMPLETE",
        "eventTime": time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
        "schemaVersion": "1-0-0",
        "producer": f"https://k3s-chain6/{run_tag}",
        "run": {"runId": f"{run_tag}-run"},
        "job": {"namespace": ns, "name": f"{ns}.chain6-job"},
        "inputs": {"datasets": datasets_in} if spec_shape else datasets_in,
        "outputs": {"datasets": datasets_out} if spec_shape else datasets_out,
    }


class TestChain6HealthCheck:
    """链路6 健康检查：验证 lineage-analyzer 可用."""

    def test_lineage_analyzer_health(self, k3s_client, lineage_analyzer_url):
        """匿名 GET /api/v1/health 必须 200（与平台其它服务同口径）."""
        start = time.time()
        try:
            resp = k3s_client.get(lineage_analyzer_url + "/api/v1/health")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:  # noqa: BLE001
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "lineage-analyzer健康检查", passed, detail, duration_ms)
        assert passed, detail


class TestChain6IngestThenQuery:
    """链路6 闭环断言：写完必须查得到."""

    def test_spec_shape_event_persists_edge(self, k3s_client, lineage_analyzer_url):
        """规范形态（inputs={"datasets":[...]}）摄取必须产生边，而不是 200 + edges=0."""
        tag = uuid.uuid4().hex[:8]
        body = _event(tag, f"chain6.{tag}", "ods.orders", "dws.order_daily")
        start = time.time()
        try:
            resp = k3s_client.post(lineage_analyzer_url + "/api/v1/lineage/events", json=body)
            edges = resp.json().get("edges") if resp.status_code == 200 else None
            passed = resp.status_code == 200 and edges == 1
            detail = f"status={resp.status_code}, edges={edges}, body={resp.text[:200]}"
        except Exception as e:  # noqa: BLE001
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "规范形态事件落边", passed, detail, duration_ms)
        assert passed, detail

    def test_downstream_reads_back_edge(self, k3s_client, lineage_analyzer_url):
        """含 / 的全名用 ?table= 查询必须读回下游边（断言 2）."""
        tag = uuid.uuid4().hex[:8]
        src, dst = f"chain6.{tag}/ods.orders", f"chain6.{tag}/dws.order_daily"
        ingest = k3s_client.post(
            lineage_analyzer_url + "/api/v1/lineage/events",
            json=_event(tag, f"chain6.{tag}", "ods.orders", "dws.order_daily"),
        )
        start = time.time()
        try:
            assert ingest.status_code == 200, f"摄取失败: {ingest.text[:200]}"
            resp = k3s_client.get(
                lineage_analyzer_url + "/api/v1/lineage/downstream", params={"table": src}
            )
            tables = resp.json().get("tables", []) if resp.status_code == 200 else []
            passed = resp.status_code == 200 and dst in tables
            detail = f"status={resp.status_code}, tables={tables}"
        except AssertionError as e:
            passed = False
            detail = str(e)
        except Exception as e:  # noqa: BLE001
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "写完查得到下游", passed, detail, duration_ms)
        assert passed, detail

    def test_upstream_and_impact_read_back_edge(self, k3s_client, lineage_analyzer_url):
        """上游与影响分析走同一存储，必须同样读回."""
        tag = uuid.uuid4().hex[:8]
        src, dst = f"chain6.{tag}/ods.orders", f"chain6.{tag}/dws.order_daily"
        ingest = k3s_client.post(
            lineage_analyzer_url + "/api/v1/lineage/events",
            json=_event(tag, f"chain6.{tag}", "ods.orders", "dws.order_daily"),
        )
        start = time.time()
        try:
            assert ingest.status_code == 200, f"摄取失败: {ingest.text[:200]}"
            up = k3s_client.get(
                lineage_analyzer_url + "/api/v1/lineage/upstream", params={"table": dst}
            )
            imp = k3s_client.get(
                lineage_analyzer_url + "/api/v1/lineage/impact", params={"table": src}
            )
            upTables = up.json().get("tables", []) if up.status_code == 200 else []
            impTables = imp.json().get("tables", []) if imp.status_code == 200 else []
            passed = src in upTables and dst in impTables
            detail = f"upstream={upTables} impact={impTables}"
        except AssertionError as e:
            passed = False
            detail = str(e)
        except Exception as e:  # noqa: BLE001
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "上游与影响分析读回", passed, detail, duration_ms)
        assert passed, detail

    def test_bare_array_shape_still_supported(self, k3s_client, lineage_analyzer_url):
        """仓内生产端（batch_pipeline）历史上发裸数组形态，必须继续可用."""
        tag = uuid.uuid4().hex[:8]
        body = _event(tag, f"chain6.{tag}", "ods.raw", "dws.clean", spec_shape=False)
        start = time.time()
        try:
            resp = k3s_client.post(lineage_analyzer_url + "/api/v1/lineage/events", json=body)
            edges = resp.json().get("edges") if resp.status_code == 200 else None
            passed = resp.status_code == 200 and edges == 1
            detail = f"status={resp.status_code}, edges={edges}"
        except Exception as e:  # noqa: BLE001
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "裸数组形态兼容", passed, detail, duration_ms)
        assert passed, detail


class TestChain6ErrorSemantics:
    """链路6 错误语义：客户端错误不得伪装成服务端错误."""

    def test_missing_table_is_400(self, k3s_client, lineage_analyzer_url):
        """既不传路径变量也不传 ?table= → 400."""
        start = time.time()
        resp = k3s_client.get(lineage_analyzer_url + "/api/v1/lineage/downstream")
        passed = resp.status_code == 400
        detail = f"status={resp.status_code}, body={resp.text[:160]}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "缺 table 返回 400", passed, detail, duration_ms)
        assert passed, detail

    def test_depth_out_of_range_is_400_not_500(self, k3s_client, lineage_analyzer_url):
        """@Min/@Max 越界必须是 4xx（修复前实测被兜底分支包成 500）."""
        start = time.time()
        resp = k3s_client.get(
            lineage_analyzer_url + "/api/v1/lineage/downstream",
            params={"table": "chain6.probe/t", "depth": 999},
        )
        passed = resp.status_code == 400
        detail = f"status={resp.status_code}, body={resp.text[:160]}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "depth 越界返回 400", passed, detail, duration_ms)
        assert passed, detail

    def test_no_credential_is_401(self, k3s_client, lineage_analyzer_url):
        """无凭据打业务端点必须 401（健康豁免不得扩大到整链）."""
        import requests

        start = time.time()
        resp = requests.get(
            lineage_analyzer_url + "/api/v1/lineage/downstream",
            params={"table": "chain6.probe/t"},
            timeout=5,
        )
        passed = resp.status_code == 401
        detail = f"status={resp.status_code}, body={resp.text[:160]}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "无凭据返回 401", passed, detail, duration_ms)
        assert passed, detail
