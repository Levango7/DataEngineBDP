"""Mock 实验管理测试."""

from __future__ import annotations

import pytest

from ml_platform.models import ExperimentConfig
from ml_platform.repositories import (
    ExperimentAlreadyExistsError,
    ExperimentNotFoundError,
)


@pytest.mark.asyncio
async def test_create_experiment(mockExperimentStore):
    config = ExperimentConfig(
        name="exp-1",
        workspaceId="ws-1",
        projectId="proj-1",
    )
    experimentId = await mockExperimentStore.create_experiment(config)
    assert experimentId is not None
    info = await mockExperimentStore.get_experiment(experimentId)
    assert info.name == "exp-1"
    assert info.config.workspaceId == "ws-1"
    assert info.status.value == "active"


@pytest.mark.asyncio
async def test_create_duplicate_experiment(mockExperimentStore):
    config = ExperimentConfig(name="exp-1")
    await mockExperimentStore.create_experiment(config)
    with pytest.raises(ExperimentAlreadyExistsError):
        await mockExperimentStore.create_experiment(config)


@pytest.mark.asyncio
async def test_get_experiment_not_found(mockExperimentStore):
    with pytest.raises(ExperimentNotFoundError):
        await mockExperimentStore.get_experiment("nonexistent")


@pytest.mark.asyncio
async def test_log_metrics(mockExperimentStore):
    experimentId = await mockExperimentStore.create_experiment(ExperimentConfig(name="exp-1"))
    await mockExperimentStore.log_metrics(experimentId, {"accuracy": 0.9, "auc": 0.85})
    info = await mockExperimentStore.get_experiment(experimentId)
    assert info.metrics["accuracy"] == 0.9
    assert info.metrics["auc"] == 0.85
    assert info.runCount == 1


@pytest.mark.asyncio
async def test_log_params(mockExperimentStore):
    experimentId = await mockExperimentStore.create_experiment(ExperimentConfig(name="exp-1"))
    await mockExperimentStore.log_params(experimentId, {"lr": 0.01, "epochs": 10})
    info = await mockExperimentStore.get_experiment(experimentId)
    assert info.params["lr"] == 0.01
    assert info.params["epochs"] == 10


@pytest.mark.asyncio
async def test_log_metrics_experiment_not_found(
    mockExperimentStore,
):
    with pytest.raises(ExperimentNotFoundError):
        await mockExperimentStore.log_metrics("nonexistent", {"a": 1.0})


@pytest.mark.asyncio
async def test_log_params_experiment_not_found(
    mockExperimentStore,
):
    with pytest.raises(ExperimentNotFoundError):
        await mockExperimentStore.log_params("nonexistent", {"a": 1})


@pytest.mark.asyncio
async def test_list_experiments(mockExperimentStore):
    await mockExperimentStore.create_experiment(ExperimentConfig(name="exp-1"))
    await mockExperimentStore.create_experiment(ExperimentConfig(name="exp-2"))
    experiments = await mockExperimentStore.list_experiments()
    assert len(experiments) == 2


@pytest.mark.asyncio
async def test_delete_experiment(mockExperimentStore):
    experimentId = await mockExperimentStore.create_experiment(ExperimentConfig(name="exp-1"))
    await mockExperimentStore.delete_experiment(experimentId)
    with pytest.raises(ExperimentNotFoundError):
        await mockExperimentStore.get_experiment(experimentId)


@pytest.mark.asyncio
async def test_delete_experiment_not_found(mockExperimentStore):
    with pytest.raises(ExperimentNotFoundError):
        await mockExperimentStore.delete_experiment("nonexistent")


@pytest.mark.asyncio
async def test_log_metrics_accumulates(mockExperimentStore):
    """多次 log_metrics 应累加."""
    experimentId = await mockExperimentStore.create_experiment(ExperimentConfig(name="exp-1"))
    await mockExperimentStore.log_metrics(experimentId, {"accuracy": 0.8})
    await mockExperimentStore.log_metrics(experimentId, {"accuracy": 0.9, "auc": 0.85})
    info = await mockExperimentStore.get_experiment(experimentId)
    assert info.metrics["accuracy"] == 0.9  # 后写覆盖
    assert info.metrics["auc"] == 0.85
    assert info.runCount == 2
