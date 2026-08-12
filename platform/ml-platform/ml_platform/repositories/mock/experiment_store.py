"""Mock 实验管理 - 内存实现.

对齐 MLflow Tracking 的 Experiment → metrics/params 概念。
"""

from __future__ import annotations

import uuid

from ml_platform.interfaces.experiment_store import ExperimentStore
from ml_platform.models import (
    ExperimentConfig,
    ExperimentInfo,
    ExperimentStatus,
    utcNow,
)
from ml_platform.repositories import (
    ExperimentAlreadyExistsError,
    ExperimentNotFoundError,
)


class MockExperimentStore(ExperimentStore):
    """内存实验管理.

    数据结构：
        _experiments: experimentId -> ExperimentInfo
        _nameIndex:   name -> experimentId
    """

    def __init__(self) -> None:
        self._experiments: dict[str, ExperimentInfo] = {}
        self._nameIndex: dict[str, str] = {}

    async def create_experiment(self, config: ExperimentConfig) -> str:
        if config.name in self._nameIndex:
            raise ExperimentAlreadyExistsError(config.name)
        experimentId = str(uuid.uuid4())
        now = utcNow()
        info = ExperimentInfo(
            id=experimentId,
            name=config.name,
            status=ExperimentStatus.ACTIVE,
            config=config,
        )
        info.createdAt = now
        info.updatedAt = now
        self._experiments[experimentId] = info
        self._nameIndex[config.name] = experimentId
        return experimentId

    async def get_experiment(self, experimentId: str) -> ExperimentInfo:
        if experimentId not in self._experiments:
            raise ExperimentNotFoundError(experimentId)
        return self._experiments[experimentId]

    async def list_experiments(self) -> list[ExperimentInfo]:
        return sorted(
            self._experiments.values(),
            key=lambda e: e.createdAt,
            reverse=True,
        )

    async def delete_experiment(self, experimentId: str) -> None:
        if experimentId not in self._experiments:
            raise ExperimentNotFoundError(experimentId)
        info = self._experiments.pop(experimentId)
        self._nameIndex.pop(info.name, None)

    async def log_metrics(self, experimentId: str, metrics: dict[str, float]) -> None:
        info = await self.get_experiment(experimentId)
        info.metrics.update(metrics)
        info.runCount += 1
        info.updatedAt = utcNow()

    async def log_params(self, experimentId: str, params: dict) -> None:
        info = await self.get_experiment(experimentId)
        info.params.update(params)
        info.updatedAt = utcNow()

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._experiments.clear()
        self._nameIndex.clear()

    def __len__(self) -> int:
        return len(self._experiments)
