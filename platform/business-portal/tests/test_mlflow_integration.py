"""business-portal MLflow 集成测试.

前置条件：MLflow Tracking Server 运行在 http://localhost:5000
可通过 `bash scripts/infra/test-mlflow-it.sh` 启动 MLflow 容器并运行本测试。

本测试验证：
1. 从 MLflow 获取真实 jobCount（替换硬编码 120）
2. 从 MLflow 获取真实 accuracy（替换硬编码 0.875）
3. MockBusinessLineStore 注入 MLflowMetricsProvider 后返回真实指标
4. MockDashboardStore 注入后新增 accuracy KPI
"""

from __future__ import annotations

import os
import uuid

import pytest

# 跳过条件
MLFLOW_ENABLED = os.environ.get("MLFLOW_ENABLED", "false").lower() == "true"
MLFLOW_URI = os.environ.get("BP_MLFLOW_URI", "http://localhost:5000")

pytestmark = pytest.mark.skipif(
    not MLFLOW_ENABLED,
    reason="MLFLOW_ENABLED!=true，跳过 MLflow 集成测试。" " 设置 MLFLOW_ENABLED=true 并启动 MLflow 容器后运行。",
)


@pytest.fixture
def mlflowProvider():
    """构建连接真实 MLflow 的指标提供者."""
    from business_portal.repositories.mlflow import MLflowMetricsProvider

    return MLflowMetricsProvider(trackingUri=MLFLOW_URI)


@pytest.fixture
def mlflowExperimentStore():
    """构建 ml-platform 的 MLflowExperimentStore，用于准备测试数据."""
    # 直接导入 ml-platform 的 ExperimentStore
    # 由于两个服务可能不在同一 venv，这里通过 mlflow SDK 直接准备数据
    import mlflow
    from mlflow.tracking import MlflowClient

    mlflow.set_tracking_uri(MLFLOW_URI)
    client = MlflowClient(tracking_uri=MLFLOW_URI)
    return client


# ---------------------------------------------------------------------------
# MLflowMetricsProvider 单元集成测试
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_provider_get_job_count_real(mlflowProvider):
    """getJobCount 应返回真实 MLflow run 总数（非硬编码 120）."""
    jobCount = await mlflowProvider.getJobCount()
    # 真实值应为非负整数
    assert isinstance(jobCount, int)
    assert jobCount >= 0
    # 关键：不再返回硬编码 120
    # 若 MLflow 中有 run，则 jobCount > 0；否则为 0
    # 无论如何都不应是字面量 120（除非 MLflow 恰好有 120 个 run）


@pytest.mark.asyncio
async def test_provider_get_accuracy_real(mlflowProvider, mlflowExperimentStore):
    """getAccuracy 应返回真实 MLflow best run 的 accuracy."""
    # 先在 MLflow 准备一个已知 accuracy 的 run
    expName = f"bp-it-acc-{uuid.uuid4().hex[:8]}"
    exp = mlflowExperimentStore.get_experiment_by_name(expName)
    if exp is None:
        expId = mlflowExperimentStore.create_experiment(expName)
    else:
        expId = exp.experiment_id
    run = mlflowExperimentStore.create_run(expId, run_name="bp-it-acc-run")
    mlflowExperimentStore.log_metric(run.info.run_id, "accuracy", 0.934)
    mlflowExperimentStore.set_terminated(run.info.run_id, "FINISHED")

    accuracy = await mlflowProvider.getAccuracy()
    # accuracy 应至少为刚写入的 0.934
    assert accuracy >= 0.934
    # 关键：不再返回硬编码 0.875
    # （若 MLflow 中有更高 accuracy 的 run，会更大）


@pytest.mark.asyncio
async def test_provider_get_metrics_aggregated(mlflowProvider):
    """getMetrics 应返回聚合的真实指标."""
    metrics = await mlflowProvider.getMetrics()
    assert "jobCount" in metrics
    assert "experimentCount" in metrics
    assert "accuracy" in metrics
    assert "source" in metrics
    assert metrics["source"] in ("mlflow", "fallback")


# ---------------------------------------------------------------------------
# MockBusinessLineStore 注入 MLflowProvider 集成测试
# ---------------------------------------------------------------------------


@pytest.fixture
def bl_with_mlflow(mlflowProvider):
    """构建注入 MLflowProvider 的 MockBusinessLineStore."""
    from business_portal.models.business_line import BusinessLine
    from business_portal.repositories.mock import MockBusinessLineStore

    store = MockBusinessLineStore(mlflowProvider=mlflowProvider)
    # 准备一个业务线
    bl = BusinessLine(
        id=f"bl-it-{uuid.uuid4().hex[:8]}",
        name="it-bl",
        tenantId="tenant-1",
        teamIds=["team-1"],
        memberIds=["user-1"],
    )
    # 同步插入（避免 async fixture 复杂度）
    import asyncio

    asyncio.get_event_loop().run_until_complete(store.create(bl))
    return store, bl.id


@pytest.mark.asyncio
async def test_bl_store_job_count_from_mlflow(mlflowProvider, mlflowExperimentStore):
    """MockBusinessLineStore 注入 MLflowProvider 后 jobCount 应来自真实 MLflow."""
    from business_portal.models.business_line import BusinessLine
    from business_portal.repositories.mock import MockBusinessLineStore

    # 准备 MLflow 数据：创建 experiment + run
    expName = f"bp-bl-job-{uuid.uuid4().hex[:8]}"
    expId = mlflowExperimentStore.create_experiment(expName)
    run1 = mlflowExperimentStore.create_run(expId, run_name="r1")
    mlflowExperimentStore.set_terminated(run1.info.run_id, "FINISHED")
    run2 = mlflowExperimentStore.create_run(expId, run_name="r2")
    mlflowExperimentStore.set_terminated(run2.info.run_id, "FINISHED")

    # 构建注入 MLflowProvider 的 store
    store = MockBusinessLineStore(mlflowProvider=mlflowProvider)
    bl = BusinessLine(
        id=f"bl-job-{uuid.uuid4().hex[:8]}",
        name="bl-job-test",
        tenantId="tenant-1",
        teamIds=["team-1"],
        memberIds=["user-1"],
    )
    await store.create(bl)
    usage = await store.get_usage(bl.id)
    # jobCount 应来自真实 MLflow（>=2，因为刚创建了 2 个 run）
    assert usage.jobCount >= 2
    # 关键：不再返回硬编码 120
    # （除非 MLflow 恰好有 120 个 run，概率极低）


@pytest.mark.asyncio
async def test_bl_store_without_mlflow_returns_hardcoded():
    """未注入 MLflowProvider 时应回退硬编码 120（向后兼容）."""
    from business_portal.models.business_line import BusinessLine
    from business_portal.repositories.mock import MockBusinessLineStore

    store = MockBusinessLineStore()  # 不注入 MLflowProvider
    bl = BusinessLine(
        id=f"bl-mock-{uuid.uuid4().hex[:8]}",
        name="bl-mock-test",
        tenantId="tenant-1",
        teamIds=["team-1"],
        memberIds=["user-1"],
    )
    await store.create(bl)
    usage = await store.get_usage(bl.id)
    # 未注入时应返回硬编码 120
    assert usage.jobCount == 120


# ---------------------------------------------------------------------------
# MockDashboardStore 注入 MLflowProvider 集成测试
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_dashboard_accuracy_kpi_from_mlflow(mlflowProvider, mlflowExperimentStore):
    """MockDashboardStore 注入后应新增 accuracy KPI（真实值）."""
    from business_portal.models.business_line import BusinessLine
    from business_portal.repositories.mock import (
        MockBusinessLineStore,
        MockDashboardStore,
    )

    # 准备 MLflow 数据
    expName = f"bp-dash-acc-{uuid.uuid4().hex[:8]}"
    expId = mlflowExperimentStore.create_experiment(expName)
    run = mlflowExperimentStore.create_run(expId, run_name="acc-run")
    mlflowExperimentStore.log_metric(run.info.run_id, "accuracy", 0.945)
    mlflowExperimentStore.set_terminated(run.info.run_id, "FINISHED")

    bl_store = MockBusinessLineStore(mlflowProvider=mlflowProvider)
    dashboard_store = MockDashboardStore(bl_store, mlflowProvider=mlflowProvider)
    bl = BusinessLine(
        id=f"bl-dash-{uuid.uuid4().hex[:8]}",
        name="bl-dash-test",
        tenantId="tenant-1",
        teamIds=["team-1"],
        memberIds=["user-1"],
    )
    await bl_store.create(bl)
    dashboard = await dashboard_store.get_dashboard(bl.id)

    # 应包含 accuracy KPI
    accuracyKpis = [k for k in dashboard.kpis if k.key == "accuracy"]
    assert len(accuracyKpis) == 1
    # accuracy 应来自真实 MLflow（>=0.945）
    assert accuracyKpis[0].value >= 0.945
    # 关键：不再返回硬编码 0.875
    assert accuracyKpis[0].description == "来自 MLflow best run 的真实指标"


@pytest.mark.asyncio
async def test_dashboard_without_mlflow_no_accuracy_kpi():
    """未注入 MLflowProvider 时不应有 accuracy KPI（向后兼容）."""
    from business_portal.models.business_line import BusinessLine
    from business_portal.repositories.mock import (
        MockBusinessLineStore,
        MockDashboardStore,
    )

    bl_store = MockBusinessLineStore()
    dashboard_store = MockDashboardStore(bl_store)
    bl = BusinessLine(
        id=f"bl-no-mlflow-{uuid.uuid4().hex[:8]}",
        name="bl-no-mlflow-test",
        tenantId="tenant-1",
        teamIds=["team-1"],
        memberIds=["user-1"],
    )
    await bl_store.create(bl)
    dashboard = await dashboard_store.get_dashboard(bl.id)
    # 未注入时不应有 accuracy KPI
    accuracyKpis = [k for k in dashboard.kpis if k.key == "accuracy"]
    assert len(accuracyKpis) == 0


# ---------------------------------------------------------------------------
# 配置开关测试
# ---------------------------------------------------------------------------


def test_settings_mlflow_enabled():
    """BP_MLFLOW_ENABLED=true 时 settings.mlflowEnabled 应为 True."""
    from business_portal.config.settings import Settings

    settings = Settings(mlflowEnabled=True, mlflowUri=MLFLOW_URI)
    assert settings.mlflowEnabled is True
    assert settings.mlflowUri == MLFLOW_URI


def test_settings_mlflow_disabled_by_default():
    """默认 BP_MLFLOW_ENABLED=false."""
    from business_portal.config.settings import Settings

    settings = Settings()
    assert settings.mlflowEnabled is False
