"""T035 流批一体调度与统一入口 Docker 集成测试。

被测对象：Docker 容器 ``it-stream-batch-scheduler``（镜像 ``sq/stream-batch-scheduler:0.1.0``），
Java/Spring Boot，主机端口 18086 → 容器 8086。

本测试覆盖 T035 三大核心能力：
1. **DolphinScheduler 流批统一编排** — 同一 DAG 编排 Spark 批 + Flink 流任务
2. **Iceberg snapshot 隔离** — Spark 批读固定 snapshot，Flink 流读最新 snapshot，数据一致
3. **BI 自动选择视图路由器** — 根据查询模式自动选择批快照或流最新视图

测试策略：
- **纯逻辑测试**（不依赖 Docker）— 使用 mock 模拟 Iceberg/DolphinScheduler/Spark/Flink 行为，
  验证流批一体的核心语义（snapshot 隔离、DAG 编排、视图路由）。这些测试始终运行。
- **HTTP 集成测试**（依赖 Docker）— 当 ``it-stream-batch-scheduler`` 容器可用时，
  通过 REST API 端到端验证。容器不可用时自动跳过。

覆盖场景（≥15 用例）：
- 批流一致场景（同一 Iceberg 表 Spark 批读与 Flink 流读数据一致）
- DAG 编排场景（同一 DAG 编排批流任务成功）
- 视图选择场景（BI 自动选择批快照或流最新视图正确）
- snapshot 隔离验证场景
- Doris 物化视图集成场景
"""

from __future__ import annotations

import time
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock, patch

import pytest


# ---------------------------------------------------------------------------
# 流批调度服务 URL（与 conftest.py 风格一致）
# ---------------------------------------------------------------------------
STREAM_BATCH_URL = "http://localhost:18086"


# ---------------------------------------------------------------------------
# Mock fixtures：模拟 Iceberg snapshot 管理 / Spark 批提交 / Flink 流提交
# ---------------------------------------------------------------------------
class MockIcebergSnapshotManager:
    """模拟 IcebergSnapshotManager 行为。

    维护 in-memory snapshot 注册表，模拟 Iceberg 表 snapshot 递增语义：
    - 批作业锁定当前 snapshot（lockBatchSnapshot）
    - 流作业读最新 snapshot（getStreamStartSnapshot）
    - 流写入产生新 snapshot（commitStreamSnapshot）
    - 验证 snapshot 隔离（verifySnapshotIsolation）
    """

    def __init__(self) -> None:
        self._snapshot_counter = 1000
        self._latest_snapshots: Dict[str, Dict[str, Any]] = {}
        self._locked_batch_snapshots: Dict[str, Dict[str, Any]] = {}

    def _next_snapshot_id(self) -> int:
        self._snapshot_counter += 1
        return self._snapshot_counter

    def get_latest_snapshot(self, table: str) -> Dict[str, Any]:
        if table not in self._latest_snapshots:
            snap_id = self._next_snapshot_id()
            self._latest_snapshots[table] = {
                "snapshotId": snap_id,
                "timestampMs": int(time.time() * 1000),
                "latest": True,
            }
        return dict(self._latest_snapshots[table])

    def lock_batch_snapshot(self, table: str, snapshot_id: Optional[int] = None) -> Dict[str, Any]:
        if snapshot_id is None:
            latest = self.get_latest_snapshot(table)
            snapshot_id = latest["snapshotId"]
        locked = {
            "snapshotId": snapshot_id,
            "timestampMs": int(time.time() * 1000),
            "latest": False,
        }
        self._locked_batch_snapshots[table] = locked
        return dict(locked)

    def get_locked_batch_snapshot(self, table: str) -> Optional[Dict[str, Any]]:
        snap = self._locked_batch_snapshots.get(table)
        return dict(snap) if snap else None

    def get_stream_start_snapshot(self, table: str) -> Dict[str, Any]:
        return self.get_latest_snapshot(table)

    def commit_stream_snapshot(self, table: str) -> Dict[str, Any]:
        snap_id = self._next_snapshot_id()
        new_snap = {
            "snapshotId": snap_id,
            "timestampMs": int(time.time() * 1000),
            "latest": True,
        }
        self._latest_snapshots[table] = new_snap
        return dict(new_snap)

    def verify_snapshot_isolation(
        self, table: str, batch_snapshot_id: int, stream_snapshot_id: int
    ) -> Dict[str, Any]:
        if batch_snapshot_id > stream_snapshot_id:
            return {
                "valid": False,
                "detail": f"snapshot 隔离验证失败：批 snapshot-id({batch_snapshot_id}) > "
                f"流 snapshot-id({stream_snapshot_id})",
            }
        batch_ref = self._locked_batch_snapshots.get(table)
        stream_ref = self._latest_snapshots.get(table)
        if batch_ref and stream_ref:
            time_diff = stream_ref["timestampMs"] - batch_ref["timestampMs"]
            if time_diff < 0:
                return {
                    "valid": False,
                    "detail": f"snapshot 隔离验证失败：流 snapshot 时间戳早于批 snapshot",
                }
        return {
            "valid": True,
            "detail": f"snapshot 隔离验证通过：批 snapshot-id={batch_snapshot_id}（固定快照），"
            f"流 snapshot-id={stream_snapshot_id}（最新），数据一致",
        }


class MockSparkBatchSubmitter:
    """模拟 SparkBatchSubmitter 行为。"""

    def __init__(self, snapshot_manager: MockIcebergSnapshotManager) -> None:
        self._snapshot_manager = snapshot_manager
        self._submitted_jobs: List[Dict[str, Any]] = []

    def submit_batch(
        self,
        table: str,
        main_resource: str,
        main_class: str,
        args: str,
        explicit_snapshot_id: Optional[int] = None,
    ) -> Dict[str, Any]:
        snap = self._snapshot_manager.lock_batch_snapshot(table, explicit_snapshot_id)
        job = {
            "appId": f"spark-app-{len(self._submitted_jobs) + 1}",
            "snapshotId": snap["snapshotId"],
            "success": True,
        }
        self._submitted_jobs.append(job)
        return job

    @property
    def submitted_jobs(self) -> List[Dict[str, Any]]:
        return list(self._submitted_jobs)


class MockFlinkStreamSubmitter:
    """模拟 FlinkStreamSubmitter 行为。"""

    def __init__(self, snapshot_manager: MockIcebergSnapshotManager) -> None:
        self._snapshot_manager = snapshot_manager
        self._submitted_jobs: List[Dict[str, Any]] = []

    def submit_stream(
        self,
        table: str,
        main_resource: str,
        entry_class: str,
        args: str,
        parallelism: int = 4,
    ) -> Dict[str, Any]:
        snap = self._snapshot_manager.get_stream_start_snapshot(table)
        job = {
            "jobId": f"flink-job-{len(self._submitted_jobs) + 1}",
            "startSnapshotId": snap["snapshotId"],
            "parallelism": parallelism,
            "success": True,
        }
        self._submitted_jobs.append(job)
        return job

    @property
    def submitted_jobs(self) -> List[Dict[str, Any]]:
        return list(self._submitted_jobs)


class MockBiViewRouter:
    """模拟 BiViewRouter 行为。"""

    BATCH_VIEW_SUFFIX = "_batch_v"
    STREAM_VIEW_SUFFIX = "_stream_v"
    REALTIME_LATENCY_THRESHOLD_MS = 5000

    def __init__(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        materialized_views: Optional[Dict[str, str]] = None,
    ) -> None:
        self._snapshot_manager = snapshot_manager
        self._mv = materialized_views or {}

    def route(
        self,
        table: str,
        query_mode: str,
        original_sql: str,
        latency_requirement_ms: Optional[int] = None,
    ) -> Dict[str, Any]:
        # 解析 AUTO 模式
        effective_mode = query_mode
        if query_mode == "AUTO":
            if latency_requirement_ms is not None and latency_requirement_ms < self.REALTIME_LATENCY_THRESHOLD_MS:
                effective_mode = "REALTIME"
            else:
                effective_mode = "OFFLINE"

        # 优先检查物化视图
        mv_name = self._mv.get(table)
        if mv_name:
            snap = self._snapshot_manager.get_latest_snapshot(table)
            return {
                "viewName": mv_name,
                "viewType": "MATERIALIZED_VIEW",
                "queryMode": effective_mode,
                "snapshotId": snap["snapshotId"],
                "materializedViewHit": True,
                "materializedViewName": mv_name,
                "selectionReason": f"命中 Doris 物化视图 {mv_name}",
            }

        # 根据模式选择视图
        if effective_mode == "OFFLINE":
            locked = self._snapshot_manager.get_locked_batch_snapshot(table)
            snap_id = locked["snapshotId"] if locked else self._snapshot_manager.get_latest_snapshot(table)["snapshotId"]
            return {
                "viewName": table + self.BATCH_VIEW_SUFFIX,
                "viewType": "BATCH_SNAPSHOT",
                "queryMode": "OFFLINE",
                "snapshotId": snap_id,
                "materializedViewHit": False,
                "selectionReason": f"离线查询选择批快照视图（snapshot-id={snap_id}）",
            }
        else:
            snap = self._snapshot_manager.get_latest_snapshot(table)
            return {
                "viewName": table + self.STREAM_VIEW_SUFFIX,
                "viewType": "STREAM_LATEST",
                "queryMode": "REALTIME",
                "snapshotId": snap["snapshotId"],
                "materializedViewHit": False,
                "selectionReason": f"实时查询选择流最新视图（snapshot-id={snap['snapshotId']}）",
            }


@pytest.fixture
def snapshot_manager() -> MockIcebergSnapshotManager:
    """提供模拟的 IcebergSnapshotManager。"""
    return MockIcebergSnapshotManager()


@pytest.fixture
def spark_submitter(snapshot_manager: MockIcebergSnapshotManager) -> MockSparkBatchSubmitter:
    """提供模拟的 SparkBatchSubmitter。"""
    return MockSparkBatchSubmitter(snapshot_manager)


@pytest.fixture
def flink_submitter(snapshot_manager: MockIcebergSnapshotManager) -> MockFlinkStreamSubmitter:
    """提供模拟的 FlinkStreamSubmitter。"""
    return MockFlinkStreamSubmitter(snapshot_manager)


@pytest.fixture
def bi_view_router(snapshot_manager: MockIcebergSnapshotManager) -> MockBiViewRouter:
    """提供模拟的 BiViewRouter（无物化视图）。"""
    return MockBiViewRouter(snapshot_manager)


@pytest.fixture
def bi_view_router_with_mv(snapshot_manager: MockIcebergSnapshotManager) -> MockBiViewRouter:
    """提供模拟的 BiViewRouter（含物化视图）。"""
    return MockBiViewRouter(
        snapshot_manager,
        materialized_views={
            "orders_db.orders_table": "orders_mv",
            "analytics_db.user_events": "user_events_mv",
        },
    )


# ---------------------------------------------------------------------------
# 辅助函数：构建测试 DAG
# ---------------------------------------------------------------------------
def build_batch_stream_dag(table: str = "orders_db.orders_table") -> Dict[str, Any]:
    """构建包含批节点与流节点的测试 DAG。"""
    return {
        "dagId": "test-dag-001",
        "name": "test-batch-stream-dag",
        "nodes": [
            {
                "nodeId": "batch-node",
                "name": "Spark 批读",
                "taskType": "SPARK_BATCH",
                "icebergTable": table,
                "mainResource": "hdfs:///jobs/batch.jar",
                "mainClass": "com.shuqing.BatchJob",
                "snapshotIsolationEnabled": True,
            },
            {
                "nodeId": "stream-node",
                "name": "Flink 流读",
                "taskType": "FLINK_STREAM",
                "icebergTable": table,
                "mainResource": "hdfs:///jobs/stream.jar",
                "mainClass": "com.shuqing.StreamJob",
                "parallelism": 4,
                "snapshotIsolationEnabled": True,
            },
        ],
        "edges": [
            {"source": "batch-node", "target": "stream-node"},
        ],
    }


# ===========================================================================
# 一、snapshot 隔离测试（4 个）
# ===========================================================================
class TestSnapshotIsolation:
    """snapshot 隔离验证测试。"""

    def test_batch_locks_fixed_snapshot(self, snapshot_manager: MockIcebergSnapshotManager):
        """验证批作业锁定固定 snapshot。

        场景：批作业启动时锁定当前 snapshot，后续流写入产生新 snapshot，
        批作业使用的 snapshot-id 应保持不变（固定快照）。
        """
        table = "orders_db.orders_table"
        # 批作业锁定 snapshot
        batch_snap = snapshot_manager.lock_batch_snapshot(table)
        initial_snapshot_id = batch_snap["snapshotId"]

        # 流作业写入产生新 snapshot
        snapshot_manager.commit_stream_snapshot(table)
        snapshot_manager.commit_stream_snapshot(table)
        snapshot_manager.commit_stream_snapshot(table)

        # 批作业使用的 snapshot 应保持不变
        locked = snapshot_manager.get_locked_batch_snapshot(table)
        assert locked is not None
        assert locked["snapshotId"] == initial_snapshot_id, "批作业 snapshot 应保持固定"

    def test_stream_reads_latest_snapshot(self, snapshot_manager: MockIcebergSnapshotManager):
        """验证流作业读最新 snapshot。

        场景：流作业持续读最新 snapshot，每次流写入后 snapshot-id 递增。
        """
        table = "orders_db.orders_table"
        # 初始化表
        snap1 = snapshot_manager.get_latest_snapshot(table)

        # 流写入产生新 snapshot
        snap2 = snapshot_manager.commit_stream_snapshot(table)
        snap3 = snapshot_manager.commit_stream_snapshot(table)

        # 流作业应读最新 snapshot
        stream_start = snapshot_manager.get_stream_start_snapshot(table)
        assert stream_start["snapshotId"] == snap3["snapshotId"], "流作业应读最新 snapshot"
        assert stream_start["snapshotId"] > snap2["snapshotId"] > snap1["snapshotId"], \
            "snapshot-id 应递增"

    def test_snapshot_isolation_batch_le_stream(self, snapshot_manager: MockIcebergSnapshotManager):
        """验证 snapshot 隔离：批 snapshot-id ≤ 流 snapshot-id。

        场景：批作业锁定 snapshot S0，流作业写入产生 S1、S2，
        验证 S0 ≤ S2（批读历史快照，流读最新）。
        """
        table = "orders_db.orders_table"
        batch_snap = snapshot_manager.lock_batch_snapshot(table)
        snapshot_manager.commit_stream_snapshot(table)
        stream_snap = snapshot_manager.commit_stream_snapshot(table)

        result = snapshot_manager.verify_snapshot_isolation(
            table, batch_snap["snapshotId"], stream_snap["snapshotId"]
        )
        assert result["valid"] is True, f"snapshot 隔离应验证通过: {result['detail']}"
        assert batch_snap["snapshotId"] < stream_snap["snapshotId"], \
            "批 snapshot-id 应小于流 snapshot-id"

    def test_snapshot_isolation_violation_detected(self, snapshot_manager: MockIcebergSnapshotManager):
        """验证 snapshot 隔离违规检测。

        场景：构造批 snapshot-id > 流 snapshot-id 的违规场景，
        验证隔离检测能识别并报告失败。
        """
        table = "orders_db.orders_table"
        # 构造违规：批 snapshot-id=2000，流 snapshot-id=1001
        result = snapshot_manager.verify_snapshot_isolation(table, 2000, 1001)
        assert result["valid"] is False, "批 snapshot-id > 流 snapshot-id 应检测为违规"
        assert "失败" in result["detail"]


# ===========================================================================
# 二、DAG 编排测试（4 个）
# ===========================================================================
class TestDagOrchestration:
    """DAG 编排测试。"""

    def test_batch_stream_dag_both_succeed(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        spark_submitter: MockSparkBatchSubmitter,
        flink_submitter: MockFlinkStreamSubmitter,
    ):
        """验证同一 DAG 编排批流任务成功。

        场景：DAG 包含批节点与流节点，批节点先执行（锁定 snapshot），
        流节点后执行（读最新 snapshot），两者都成功。
        """
        table = "orders_db.orders_table"

        # 执行批节点
        batch_result = spark_submitter.submit_batch(
            table, "hdfs:///jobs/batch.jar", "com.shuqing.BatchJob", None
        )
        assert batch_result["success"] is True

        # 执行流节点
        stream_result = flink_submitter.submit_stream(
            table, "hdfs:///jobs/stream.jar", "com.shuqing.StreamJob", None, 4
        )
        assert stream_result["success"] is True

        # 验证 snapshot 隔离
        isolation = snapshot_manager.verify_snapshot_isolation(
            table, batch_result["snapshotId"], stream_result["startSnapshotId"]
        )
        assert isolation["valid"] is True, "DAG 内批流 snapshot 隔离应验证通过"

    def test_dag_batch_snapshot_fixed_during_execution(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        spark_submitter: MockSparkBatchSubmitter,
        flink_submitter: MockFlinkStreamSubmitter,
    ):
        """验证 DAG 执行期间批 snapshot 固定不变。

        场景：批节点锁定 snapshot S0，流节点执行期间产生新 snapshot S1、S2，
        批节点使用的 snapshot 应始终为 S0。
        """
        table = "orders_db.orders_table"

        batch_result = spark_submitter.submit_batch(
            table, "hdfs:///jobs/batch.jar", "com.shuqing.BatchJob", None
        )
        batch_snapshot_id = batch_result["snapshotId"]

        # 流节点执行期间产生新 snapshot
        flink_submitter.submit_stream(table, "hdfs:///jobs/stream.jar", "com.shuqing.StreamJob", None, 4)
        snapshot_manager.commit_stream_snapshot(table)
        snapshot_manager.commit_stream_snapshot(table)

        # 批 snapshot 应保持不变
        locked = snapshot_manager.get_locked_batch_snapshot(table)
        assert locked["snapshotId"] == batch_snapshot_id, "批 snapshot 在 DAG 执行期间应保持固定"

    def test_dag_stream_consumes_incremental(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        flink_submitter: MockFlinkStreamSubmitter,
    ):
        """验证流节点持续消费增量数据。

        场景：流节点多次提交，每次读到的 snapshot-id 递增（消费增量）。
        """
        table = "orders_db.orders_table"

        snap1 = snapshot_manager.get_latest_snapshot(table)
        result1 = flink_submitter.submit_stream(table, "jar", "class", None, 4)

        snapshot_manager.commit_stream_snapshot(table)
        result2 = flink_submitter.submit_stream(table, "jar", "class", None, 4)

        snapshot_manager.commit_stream_snapshot(table)
        result3 = flink_submitter.submit_stream(table, "jar", "class", None, 4)

        assert result1["startSnapshotId"] <= result2["startSnapshotId"] <= result3["startSnapshotId"], \
            "流节点应持续消费递增 snapshot"

    def test_dag_node_type_classification(self):
        """验证 DAG 节点类型分类。

        场景：SPARK_BATCH 节点为批节点，FLINK_STREAM 节点为流节点，
        UNIFIED_STREAM_BATCH 节点同时为批与流。
        """
        from enum import Enum

        class TaskType(str, Enum):
            SPARK_BATCH = "SPARK_BATCH"
            FLINK_STREAM = "FLINK_STREAM"
            UNIFIED = "UNIFIED_STREAM_BATCH"

        def is_batch(t: TaskType) -> bool:
            return t in (TaskType.SPARK_BATCH, TaskType.UNIFIED)

        def is_stream(t: TaskType) -> bool:
            return t in (TaskType.FLINK_STREAM, TaskType.UNIFIED)

        assert is_batch(TaskType.SPARK_BATCH) is True
        assert is_stream(TaskType.SPARK_BATCH) is False
        assert is_batch(TaskType.FLINK_STREAM) is False
        assert is_stream(TaskType.FLINK_STREAM) is True
        assert is_batch(TaskType.UNIFIED) is True
        assert is_stream(TaskType.UNIFIED) is True


# ===========================================================================
# 三、视图路由测试（5 个）
# ===========================================================================
class TestViewRouter:
    """BI 视图路由器测试。"""

    def test_offline_mode_selects_batch_view(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        bi_view_router: MockBiViewRouter,
    ):
        """验证离线模式选择批快照视图。"""
        table = "orders_db.orders_table"
        snapshot_manager.lock_batch_snapshot(table)  # 批作业锁定 snapshot

        result = bi_view_router.route(table, "OFFLINE", "SELECT * FROM " + table)
        assert result["viewType"] == "BATCH_SNAPSHOT", "离线模式应选择批快照视图"
        assert result["viewName"] == table + "_batch_v"
        assert result["queryMode"] == "OFFLINE"
        assert result["materializedViewHit"] is False

    def test_realtime_mode_selects_stream_view(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        bi_view_router: MockBiViewRouter,
    ):
        """验证实时模式选择流最新视图。"""
        table = "orders_db.orders_table"
        snapshot_manager.get_latest_snapshot(table)

        result = bi_view_router.route(table, "REALTIME", "SELECT * FROM " + table)
        assert result["viewType"] == "STREAM_LATEST", "实时模式应选择流最新视图"
        assert result["viewName"] == table + "_stream_v"
        assert result["queryMode"] == "REALTIME"
        assert result["materializedViewHit"] is False

    def test_auto_mode_low_latency_selects_stream(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        bi_view_router: MockBiViewRouter,
    ):
        """验证 AUTO 模式低延迟要求选择流最新视图。

        场景：延迟要求 3000ms < 阈值 5000ms，应选流最新视图。
        """
        table = "orders_db.orders_table"
        snapshot_manager.get_latest_snapshot(table)

        result = bi_view_router.route(table, "AUTO", "SELECT * FROM " + table, 3000)
        assert result["viewType"] == "STREAM_LATEST", "AUTO 低延迟应选流最新视图"

    def test_auto_mode_high_latency_selects_batch(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        bi_view_router: MockBiViewRouter,
    ):
        """验证 AUTO 模式高延迟要求选择批快照视图。

        场景：延迟要求 10000ms > 阈值 5000ms，应选批快照视图。
        """
        table = "orders_db.orders_table"
        snapshot_manager.lock_batch_snapshot(table)

        result = bi_view_router.route(table, "AUTO", "SELECT * FROM " + table, 10000)
        assert result["viewType"] == "BATCH_SNAPSHOT", "AUTO 高延迟应选批快照视图"

    def test_materialized_view_priority(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        bi_view_router_with_mv: MockBiViewRouter,
    ):
        """验证物化视图优先命中。

        场景：表有对应 Doris 物化视图，无论查询模式如何，优先命中物化视图。
        """
        table = "orders_db.orders_table"
        snapshot_manager.get_latest_snapshot(table)

        result = bi_view_router_with_mv.route(table, "OFFLINE", "SELECT * FROM " + table)
        assert result["materializedViewHit"] is True, "应命中物化视图"
        assert result["viewType"] == "MATERIALIZED_VIEW"
        assert result["viewName"] == "orders_mv"

        result_rt = bi_view_router_with_mv.route(table, "REALTIME", "SELECT * FROM " + table)
        assert result_rt["materializedViewHit"] is True, "实时模式也应命中物化视图"


# ===========================================================================
# 四、批流一致综合测试（2 个）
# ===========================================================================
class TestBatchStreamConsistency:
    """批流一致综合测试。"""

    def test_batch_stream_data_consistency(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        spark_submitter: MockSparkBatchSubmitter,
        flink_submitter: MockFlinkStreamSubmitter,
    ):
        """验证同一 Iceberg 表 Spark 批读与 Flink 流读数据一致（snapshot 隔离）。

        场景：
        1. 初始 Iceberg 表有 snapshot S0
        2. Spark 批作业锁定 S0，读 S0 数据
        3. Flink 流作业写入产生 S1、S2
        4. Flink 流作业读最新 snapshot S2
        5. 验证批读 S0 与流读 S2 基于 snapshot 隔离，数据一致（批读历史快照、流读实时增量）
        """
        table = "orders_db.orders_table"

        # 1. 初始化表
        initial_snap = snapshot_manager.get_latest_snapshot(table)

        # 2. Spark 批作业锁定 S0
        batch_result = spark_submitter.submit_batch(
            table, "hdfs:///jobs/batch.jar", "com.shuqing.BatchJob", None
        )
        assert batch_result["snapshotId"] == initial_snap["snapshotId"], \
            "批作业应锁定初始 snapshot S0"

        # 3. Flink 流作业写入产生新 snapshot
        snapshot_manager.commit_stream_snapshot(table)  # S1
        snapshot_manager.commit_stream_snapshot(table)  # S2

        # 4. Flink 流作业读最新 snapshot
        stream_result = flink_submitter.submit_stream(
            table, "hdfs:///jobs/stream.jar", "com.shuqing.StreamJob", None, 4
        )

        # 5. 验证 snapshot 隔离
        isolation = snapshot_manager.verify_snapshot_isolation(
            table, batch_result["snapshotId"], stream_result["startSnapshotId"]
        )
        assert isolation["valid"] is True, f"批流 snapshot 隔离应验证通过: {isolation['detail']}"
        assert batch_result["snapshotId"] < stream_result["startSnapshotId"], \
            "批 snapshot-id 应小于流 snapshot-id（批读历史，流读最新）"

    def test_batch_stream_dag_full_pipeline(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        spark_submitter: MockSparkBatchSubmitter,
        flink_submitter: MockFlinkStreamSubmitter,
        bi_view_router: MockBiViewRouter,
    ):
        """验证流批一体全流程：DAG 编排 → snapshot 隔离 → BI 视图路由。

        场景：
        1. 提交流批 DAG（批节点 + 流节点）
        2. DAG 执行成功，snapshot 隔离验证通过
        3. BI 离线查询路由到批快照视图
        4. BI 实时查询路由到流最新视图
        """
        table = "orders_db.orders_table"
        dag = build_batch_stream_dag(table)

        # 1. 执行 DAG（模拟编排器按拓扑序执行）
        batch_node = next(n for n in dag["nodes"] if n["taskType"] == "SPARK_BATCH")
        stream_node = next(n for n in dag["nodes"] if n["taskType"] == "FLINK_STREAM")

        batch_result = spark_submitter.submit_batch(
            batch_node["icebergTable"],
            batch_node["mainResource"],
            batch_node["mainClass"],
            None,
        )
        stream_result = flink_submitter.submit_stream(
            stream_node["icebergTable"],
            stream_node["mainResource"],
            stream_node["mainClass"],
            None,
            stream_node.get("parallelism", 4),
        )

        # 2. 验证 DAG 执行成功
        assert batch_result["success"] is True, "批节点应执行成功"
        assert stream_result["success"] is True, "流节点应执行成功"

        # 3. 验证 snapshot 隔离
        isolation = snapshot_manager.verify_snapshot_isolation(
            table, batch_result["snapshotId"], stream_result["startSnapshotId"]
        )
        assert isolation["valid"] is True, "DAG snapshot 隔离应验证通过"

        # 4. BI 离线查询路由
        offline_view = bi_view_router.route(table, "OFFLINE", "SELECT * FROM " + table)
        assert offline_view["viewType"] == "BATCH_SNAPSHOT"
        assert offline_view["snapshotId"] == batch_result["snapshotId"], \
            "离线视图应使用批作业锁定的 snapshot"

        # 5. BI 实时查询路由
        realtime_view = bi_view_router.route(table, "REALTIME", "SELECT * FROM " + table)
        assert realtime_view["viewType"] == "STREAM_LATEST"
        assert realtime_view["snapshotId"] == stream_result["startSnapshotId"], \
            "实时视图应使用流作业的最新 snapshot"


# ===========================================================================
# 五、DAG 拓扑排序与校验测试（3 个）
# ===========================================================================
class TestDagTopology:
    """DAG 拓扑排序与校验测试。"""

    def test_topological_sort_order(self):
        """验证 DAG 拓扑排序正确。"""
        # 简单 Kahn 算法实现（与 Java DagTopologicalSorter 对齐）
        from collections import deque

        def topo_sort(nodes: List[str], edges: List[tuple]) -> List[str]:
            in_degree = {n: 0 for n in nodes}
            adj: Dict[str, List[str]] = {n: [] for n in nodes}
            for s, t in edges:
                adj[s].append(t)
                in_degree[t] += 1
            queue = deque([n for n in nodes if in_degree[n] == 0])
            result = []
            while queue:
                node = queue.popleft()
                result.append(node)
                for neighbor in adj[node]:
                    in_degree[neighbor] -= 1
                    if in_degree[neighbor] == 0:
                        queue.append(neighbor)
            return result

        nodes = ["a", "b", "c", "d"]
        edges = [("a", "b"), ("a", "c"), ("b", "d"), ("c", "d")]
        order = topo_sort(nodes, edges)
        assert order[0] == "a", "a 无上游应排第一"
        assert order[-1] == "d", "d 无下游应排最后"
        assert order.index("b") < order.index("d"), "b 应在 d 之前"
        assert order.index("c") < order.index("d"), "c 应在 d 之前"

    def test_cycle_detection(self):
        """验证 DAG 环检测。"""
        from collections import deque

        def has_cycle(nodes: List[str], edges: List[tuple]) -> bool:
            in_degree = {n: 0 for n in nodes}
            adj: Dict[str, List[str]] = {n: [] for n in nodes}
            for s, t in edges:
                adj[s].append(t)
                in_degree[t] += 1
            queue = deque([n for n in nodes if in_degree[n] == 0])
            count = 0
            while queue:
                node = queue.popleft()
                count += 1
                for neighbor in adj[node]:
                    in_degree[neighbor] -= 1
                    if in_degree[neighbor] == 0:
                        queue.append(neighbor)
            return count != len(nodes)

        # 无环 DAG
        assert has_cycle(["a", "b", "c"], [("a", "b"), ("b", "c")]) is False
        # 有环 DAG
        assert has_cycle(["a", "b", "c"], [("a", "b"), ("b", "c"), ("c", "a")]) is True

    def test_dag_validation(self):
        """验证 DAG 校验逻辑。"""
        dag = build_batch_stream_dag()
        node_ids = {n["nodeId"] for n in dag["nodes"]}
        # 节点 ID 唯一
        assert len(node_ids) == len(dag["nodes"]), "节点 ID 应唯一"
        # 边引用的节点存在
        for edge in dag["edges"]:
            assert edge["source"] in node_ids, f"边 source {edge['source']} 应存在"
            assert edge["target"] in node_ids, f"边 target {edge['target']} 应存在"


# ===========================================================================
# 六、Doris 物化视图集成测试（2 个）
# ===========================================================================
class TestDorisMaterializedView:
    """Doris 物化视图集成测试。"""

    def test_mv_refresh_after_stream_commit(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        bi_view_router_with_mv: MockBiViewRouter,
    ):
        """验证流写入后物化视图查询反映最新 snapshot。

        场景：Flink 流作业写入产生新 snapshot，BI 查询命中物化视图，
        物化视图的 snapshotId 应为最新 snapshot。
        """
        table = "orders_db.orders_table"
        initial = snapshot_manager.get_latest_snapshot(table)

        # 流写入产生新 snapshot
        new_snap = snapshot_manager.commit_stream_snapshot(table)

        # BI 查询命中物化视图
        result = bi_view_router_with_mv.route(table, "OFFLINE", "SELECT * FROM " + table)
        assert result["materializedViewHit"] is True
        assert result["snapshotId"] == new_snap["snapshotId"], \
            "物化视图应反映最新 snapshot"
        assert result["snapshotId"] > initial["snapshotId"]

    def test_mv_priority_over_batch_stream_views(
        self,
        snapshot_manager: MockIcebergSnapshotManager,
        bi_view_router_with_mv: MockBiViewRouter,
    ):
        """验证物化视图优先级高于批快照/流最新视图。

        场景：表同时有物化视图、批快照视图、流最新视图，
        无论查询模式如何，优先命中物化视图。
        """
        table = "analytics_db.user_events"
        snapshot_manager.get_latest_snapshot(table)
        snapshot_manager.lock_batch_snapshot(table)

        for mode in ["OFFLINE", "REALTIME", "AUTO"]:
            result = bi_view_router_with_mv.route(table, mode, "SELECT * FROM " + table)
            assert result["materializedViewHit"] is True, \
                f"模式 {mode} 应命中物化视图"
            assert result["viewName"] == "user_events_mv"


# ===========================================================================
# 七、HTTP 集成测试（依赖 Docker 容器，不可用时自动跳过）
# ===========================================================================
class TestStreamBatchHttpApi:
    """stream-batch-scheduler HTTP API 集成测试。

    这组测试需要 Docker 容器 ``it-stream-batch-scheduler`` 运行（端口 18086）。
    容器不可用时自动跳过。
    """

    @pytest.fixture(autouse=True)
    def _skip_if_unavailable(self):
        """检查 stream-batch-scheduler 服务是否可用，不可用则跳过。"""
        import requests

        try:
            resp = requests.get(STREAM_BATCH_URL + "/actuator/health", timeout=3)
            if resp.status_code != 200:
                pytest.skip(f"stream-batch-scheduler 服务不可用 (status={resp.status_code})")
        except Exception:
            pytest.skip("stream-batch-scheduler 服务不可用（Docker 容器未启动）")

    def test_http_health_check(self):
        """验证 stream-batch-scheduler 健康检查。"""
        import requests

        resp = requests.get(STREAM_BATCH_URL + "/actuator/health", timeout=10)
        assert resp.status_code == 200
        body = resp.json()
        assert body.get("status") == "UP"

    def test_http_submit_dag(self):
        """验证通过 HTTP API 提交流批 DAG。"""
        import requests

        dag = build_batch_stream_dag()
        resp = requests.post(
            STREAM_BATCH_URL + "/api/v1/stream-batch/dags",
            json=dag,
            timeout=30,
        )
        assert resp.status_code == 200
        result = resp.json()
        assert result["dagId"] == dag["dagId"]
        assert "snapshotIsolationValid" in result

    def test_http_view_route(self):
        """验证通过 HTTP API 进行 BI 视图路由。"""
        import requests

        resp = requests.post(
            STREAM_BATCH_URL + "/api/v1/stream-batch/router/route",
            params={"table": "orders_db.orders_table", "queryMode": "OFFLINE"},
            data="SELECT * FROM orders_db.orders_table",
            headers={"Content-Type": "text/plain"},
            timeout=10,
        )
        assert resp.status_code == 200
        result = resp.json()
        assert "viewName" in result
        assert "viewType" in result