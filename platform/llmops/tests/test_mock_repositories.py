"""Mock 训练/部署/监控测试."""
from __future__ import annotations

import uuid

import pytest

from llmops.models.base import DeploymentStatus, TrainingStatus
from llmops.models.deployment import DeployConfig
from llmops.models.training import TrainingConfig
from llmops.repositories import (
    DeploymentNotFoundError,
    TrainingJobNotFoundError,
    TrainingJobNotCancellableError,
    TrainingJobNotFinishedError,
)


# ---------- Trainer ----------

@pytest.mark.asyncio
async def test_create_training_job(mock_trainer):
    cfg = TrainingConfig(
        baseModelId="base-1",
        outputModelName="ft-1",
        dataset="ds-1",
        epochs=3,
    )
    job_id = await mock_trainer.create_training_job(cfg)
    assert job_id

    job = await mock_trainer.get_training_status(job_id)
    assert job.status.status == TrainingStatus.PENDING
    assert job.status.totalEpochs == 3
    assert job.status.progress == 0.0


@pytest.mark.asyncio
async def test_training_state_machine(mock_trainer):
    """PENDING -> RUNNING -> SUCCEEDED 状态机."""
    cfg = TrainingConfig(
        baseModelId="base-1",
        outputModelName="ft-1",
        dataset="ds-1",
        epochs=2,
    )
    job_id = await mock_trainer.create_training_job(cfg)

    # PENDING -> RUNNING
    job = await mock_trainer.advance(job_id)
    assert job.status.status == TrainingStatus.RUNNING
    assert job.startedAt is not None

    # RUNNING, epoch 1
    job = await mock_trainer.advance(job_id)
    assert job.status.currentEpoch == 1
    assert job.status.progress == 0.5

    # RUNNING -> SUCCEEDED, epoch 2
    job = await mock_trainer.advance(job_id)
    assert job.status.status == TrainingStatus.SUCCEEDED
    assert job.status.progress == 1.0
    assert job.status.outputModelId is not None
    assert job.finishedAt is not None


@pytest.mark.asyncio
async def test_cancel_pending_training(mock_trainer):
    cfg = TrainingConfig(baseModelId="b", outputModelName="f", dataset="d")
    job_id = await mock_trainer.create_training_job(cfg)
    await mock_trainer.cancel_training(job_id)
    job = await mock_trainer.get_training_status(job_id)
    assert job.status.status == TrainingStatus.CANCELLED


@pytest.mark.asyncio
async def test_cancel_finished_training_raises(mock_trainer):
    cfg = TrainingConfig(
        baseModelId="b", outputModelName="f", dataset="d", epochs=1
    )
    job_id = await mock_trainer.create_training_job(cfg)
    await mock_trainer.advance(job_id)  # -> RUNNING
    await mock_trainer.advance(job_id)  # -> SUCCEEDED
    with pytest.raises(TrainingJobNotCancellableError):
        await mock_trainer.cancel_training(job_id)


@pytest.mark.asyncio
async def test_evaluate_unfinished_raises(mock_trainer):
    cfg = TrainingConfig(baseModelId="b", outputModelName="f", dataset="d")
    job_id = await mock_trainer.create_training_job(cfg)
    with pytest.raises(TrainingJobNotFinishedError):
        await mock_trainer.evaluate_model(job_id)


@pytest.mark.asyncio
async def test_evaluate_returns_metrics(mock_trainer):
    cfg = TrainingConfig(
        baseModelId="b", outputModelName="f", dataset="d", epochs=3
    )
    job_id = await mock_trainer.create_training_job(cfg)
    for _ in range(4):  # PENDING->RUNNING + 3 epochs
        await mock_trainer.advance(job_id)
    metrics = await mock_trainer.evaluate_model(job_id)
    assert 0.0 <= metrics.accuracy <= 1.0
    assert 0.0 <= metrics.hallucinationRate <= 1.0
    assert metrics.upliftVsBase > 0  # epochs=3 时 accuracy=0.85 > 0.75


@pytest.mark.asyncio
async def test_get_nonexistent_job_raises(mock_trainer):
    with pytest.raises(TrainingJobNotFoundError):
        await mock_trainer.get_training_status("no-such")


@pytest.mark.asyncio
async def test_list_training_jobs(mock_trainer):
    for i in range(3):
        await mock_trainer.create_training_job(
            TrainingConfig(
                baseModelId="b", outputModelName=f"f{i}", dataset="d"
            )
        )
    jobs = await mock_trainer.list_training_jobs()
    assert len(jobs) == 3


# ---------- Deployer ----------

@pytest.mark.asyncio
async def test_deploy_and_status(mock_deployer):
    cfg = DeployConfig(modelId="m1", replica=2, gpu=1)
    dep_id = await mock_deployer.deploy_model("m1", cfg)
    assert dep_id

    dep = await mock_deployer.get_deployment_status(dep_id)
    assert dep.status.status == DeploymentStatus.CREATING
    assert dep.config.replica == 2


@pytest.mark.asyncio
async def test_deploy_state_machine(mock_deployer):
    """CREATING -> RUNNING."""
    cfg = DeployConfig(modelId="m1")
    dep_id = await mock_deployer.deploy_model("m1", cfg)

    dep = await mock_deployer.advance(dep_id)
    assert dep.status.status == DeploymentStatus.RUNNING
    assert dep.endpointUrl is not None
    assert dep.gatewayRoute is not None
    assert dep.startedAt is not None


@pytest.mark.asyncio
async def test_undeploy(mock_deployer):
    cfg = DeployConfig(modelId="m1")
    dep_id = await mock_deployer.deploy_model("m1", cfg)
    await mock_deployer.advance(dep_id)  # -> RUNNING
    await mock_deployer.undeploy_model(dep_id)  # -> STOPPING
    dep = await mock_deployer.get_deployment_status(dep_id)
    assert dep.status.status == DeploymentStatus.STOPPING

    dep = await mock_deployer.advance(dep_id)  # -> STOPPED
    assert dep.status.status == DeploymentStatus.STOPPED
    assert dep.stoppedAt is not None
    assert dep.endpointUrl is None


@pytest.mark.asyncio
async def test_get_nonexistent_deployment_raises(mock_deployer):
    with pytest.raises(DeploymentNotFoundError):
        await mock_deployer.get_deployment_status("no-such")


# ---------- Monitor ----------

@pytest.mark.asyncio
async def test_monitor_metrics(mock_monitor):
    mock_monitor.register_deployment("dep-1")
    m = await mock_monitor.get_metrics("dep-1")
    assert m.deploymentId == "dep-1"
    assert 0.0 <= m.accuracy <= 1.0
    assert m.qps > 0
    assert m.sampleCount > 0


@pytest.mark.asyncio
async def test_monitor_latency(mock_monitor):
    mock_monitor.register_deployment("dep-1")
    lat = await mock_monitor.get_latency("dep-1")
    assert lat.p50Ms <= lat.p95Ms <= lat.p99Ms
    assert lat.minMs <= lat.avgMs <= lat.maxMs


@pytest.mark.asyncio
async def test_monitor_throughput(mock_monitor):
    mock_monitor.register_deployment("dep-1")
    tp = await mock_monitor.get_throughput("dep-1")
    assert tp.rps > 0
    assert tp.tps > 0


@pytest.mark.asyncio
async def test_monitor_error_rate(mock_monitor):
    mock_monitor.register_deployment("dep-1")
    err = await mock_monitor.get_error_rate("dep-1")
    assert 0.0 <= err.errorRate <= 1.0
    assert err.errorCount <= err.totalRequests


@pytest.mark.asyncio
async def test_monitor_unknown_deployment_raises(mock_monitor):
    with pytest.raises(DeploymentNotFoundError):
        await mock_monitor.get_metrics("no-such")


@pytest.mark.asyncio
async def test_monitor_metrics_deterministic(mock_monitor):
    """同一部署的指标应确定性稳定（基于 id 哈希）."""
    mock_monitor.register_deployment("dep-stable")
    m1 = await mock_monitor.get_metrics("dep-stable")
    m2 = await mock_monitor.get_metrics("dep-stable")
    assert m1.accuracy == m2.accuracy
    assert m1.qps == m2.qps