"""MLflow ExperimentStore 单元测试（注入假 MlflowClient，不依赖真实 MLflow）.

覆盖：
1. list_experiments 合并远端列表时保留本地累计 metrics/params/runCount/config
2. delete_experiment 远端失败时抛 MlPlatformError 且本地索引不动
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from ml_platform.models import ExperimentConfig
from ml_platform.repositories import MlPlatformError
from ml_platform.repositories.mlflow import MLflowExperimentStore


class FakeMlflowClient:
    """最小 MlflowClient 桩：内存保存 experiments/runs."""

    def __init__(self) -> None:
        self.experiments: dict[str, SimpleNamespace] = {}
        self.runs: list[SimpleNamespace] = []
        self.failDelete = False
        self._nextId = 0

    def _newId(self) -> str:
        self._nextId += 1
        return str(self._nextId)

    def create_experiment(self, name: str) -> str:
        for exp in self.experiments.values():
            if exp.name == name:
                raise RuntimeError(f"Experiment '{name}' already exists.")
        expId = self._newId()
        self.experiments[expId] = SimpleNamespace(
            experiment_id=expId,
            name=name,
            lifecycle_stage="active",
        )
        return expId

    def get_experiment(self, experiment_id: str) -> SimpleNamespace:
        return self.experiments[experiment_id]

    def search_experiments(self, view_type=None, filter_string=None):
        return [e for e in self.experiments.values() if "deleted" not in e.lifecycle_stage]

    def delete_experiment(self, experiment_id: str) -> None:
        if self.failDelete:
            raise RuntimeError("mlflow tracking server unavailable")
        exp = self.experiments.get(experiment_id)
        if exp is None:
            raise RuntimeError(f"experiment {experiment_id} not found")
        exp.lifecycle_stage = "deleted"

    def create_run(self, experiment_id: str, run_name: str = ""):
        runId = f"run-{self._newId()}"
        run = SimpleNamespace(info=SimpleNamespace(run_id=runId))
        self.runs.append(run)
        return run

    def log_metric(self, run_id: str, key: str, value: float) -> None:
        pass

    def log_param(self, run_id: str, key: str, value: str) -> None:
        pass

    def set_terminated(self, run_id: str, status: str) -> None:
        pass


@pytest.fixture
def store() -> MLflowExperimentStore:
    s = MLflowExperimentStore(trackingUri="http://test")
    s._client = FakeMlflowClient()
    return s


class TestListExperimentsMerge:
    @pytest.mark.asyncio
    async def test_list_preserves_local_accumulated_state(self, store):
        config = ExperimentConfig(name="exp-a", workspaceId="ws-1", description="原始描述")
        expId = await store.create_experiment(config)
        await store.log_metrics(expId, {"accuracy": 0.9})
        await store.log_params(expId, {"lr": "0.01"})
        infos = await store.list_experiments()
        info = next(i for i in infos if i.id == expId)
        assert info.metrics["accuracy"] == pytest.approx(0.9)
        assert info.params["lr"] == "0.01"
        assert info.runCount == 1
        assert info.config.description == "原始描述"
        assert info.config.workspaceId == "ws-1"

    @pytest.mark.asyncio
    async def test_list_creates_default_entry_for_remote_only_experiment(self, store):
        store._client.create_experiment("remote-only")
        infos = await store.list_experiments()
        info = next(i for i in infos if i.name == "remote-only")
        assert info.id in store._experiments
        assert info.runCount == 0
        assert info.metrics == {}
        assert info.config.name == "remote-only"


class TestDeleteExperimentFailure:
    @pytest.mark.asyncio
    async def test_delete_remote_failure_raises_and_keeps_local(self, store):
        expId = await store.create_experiment(ExperimentConfig(name="exp-a"))
        store._client.failDelete = True
        with pytest.raises(MlPlatformError):
            await store.delete_experiment(expId)
        assert expId in store._experiments
        assert store._nameIndex.get("exp-a") == expId
        info = await store.get_experiment(expId)
        assert info.id == expId

    @pytest.mark.asyncio
    async def test_delete_success_removes_local_index(self, store):
        expId = await store.create_experiment(ExperimentConfig(name="exp-a"))
        await store.delete_experiment(expId)
        assert expId not in store._experiments
        assert "exp-a" not in store._nameIndex
