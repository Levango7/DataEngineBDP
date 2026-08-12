"""实验管理抽象接口（ExperimentStore）.

对齐 MLflow Tracking 的 Experiment → Run → metrics/params 概念。
实现：
    - MockExperimentStore:  内存实验管理
    - MLflowExperimentStore: （可选）MLflow Tracking 后端
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Optional

from ml_platform.models import ExperimentConfig, ExperimentInfo


class ExperimentStore(ABC):
    """实验管理抽象接口.

    职责：实验的创建、查询、参数/指标记录。
    """

    @abstractmethod
    async def create_experiment(self, config: ExperimentConfig) -> str:
        """创建实验，返回实验 ID.

        Args:
            config: 实验配置（名称、工作空间、项目）。

        Returns:
            实验 ID。

        Raises:
            ExperimentAlreadyExistsError: 同名实验已存在。
        """
        ...

    @abstractmethod
    async def get_experiment(self, experimentId: str) -> ExperimentInfo:
        """获取实验详情.

        Raises:
            ExperimentNotFoundError: 实验不存在。
        """
        ...

    @abstractmethod
    async def list_experiments(self) -> list[ExperimentInfo]:
        """列出所有实验."""
        ...

    @abstractmethod
    async def delete_experiment(self, experimentId: str) -> None:
        """删除实验.

        Raises:
            ExperimentNotFoundError: 实验不存在。
        """
        ...

    @abstractmethod
    async def log_metrics(self, experimentId: str, metrics: dict[str, float]) -> None:
        """记录指标.

        Args:
            experimentId: 实验 ID。
            metrics:      指标名 -> 值。

        Raises:
            ExperimentNotFoundError: 实验不存在。
        """
        ...

    @abstractmethod
    async def log_params(self, experimentId: str, params: dict) -> None:
        """记录参数.

        Args:
            experimentId: 实验 ID。
            params:       参数名 -> 值。

        Raises:
            ExperimentNotFoundError: 实验不存在。
        """
        ...

    async def find_experiment_by_name(self, name: str) -> Optional[ExperimentInfo]:
        """按名称查找实验（可选实现）."""
        experiments = await self.list_experiments()
        for e in experiments:
            if e.name == name:
                return e
        return None
