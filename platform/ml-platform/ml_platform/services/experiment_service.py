"""实验管理服务业务逻辑."""
from __future__ import annotations

from ml_platform.interfaces.experiment_store import ExperimentStore
from ml_platform.models import ExperimentConfig, ExperimentInfo


class ExperimentService:
    """实验管理服务（编排 ExperimentStore）."""

    def __init__(self, experimentStore: ExperimentStore) -> None:
        self._store = experimentStore

    async def createExperiment(
        self, config: ExperimentConfig
    ) -> ExperimentInfo:
        """创建实验，返回完整信息."""
        experimentId = await self._store.create_experiment(config)
        return await self._store.get_experiment(experimentId)

    async def getExperiment(
        self, experimentId: str
    ) -> ExperimentInfo:
        return await self._store.get_experiment(experimentId)

    async def listExperiments(self) -> list[ExperimentInfo]:
        return await self._store.list_experiments()

    async def deleteExperiment(
        self, experimentId: str
    ) -> None:
        await self._store.delete_experiment(experimentId)

    async def logMetrics(
        self, experimentId: str, metrics: dict[str, float]
    ) -> ExperimentInfo:
        await self._store.log_metrics(experimentId, metrics)
        return await self._store.get_experiment(experimentId)

    async def logParams(
        self, experimentId: str, params: dict
    ) -> ExperimentInfo:
        await self._store.log_params(experimentId, params)
        return await self._store.get_experiment(experimentId)