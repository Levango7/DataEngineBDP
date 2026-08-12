"""模型监控抽象接口（Model Monitor）.

提供运行时指标采集：综合指标 / 延迟 / 吞吐 / 错误率。
对齐 L4.5.6 大模型网关的计量链路（Prometheus 采集）。
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from llmops.models.monitor import (
    ErrorStats,
    LatencyStats,
    ModelMetrics,
    ThroughputStats,
)


class ModelMonitor(ABC):
    """模型监控抽象接口.

    职责：采集部署的运行时指标。
    实现：MockModelMonitor（生成模拟数据）/ Prometheus 客户端。
    """

    @abstractmethod
    async def get_metrics(self, deployment_id: str) -> ModelMetrics:
        """获取部署的综合指标.

        Raises:
            DeploymentNotFoundError: 部署不存在。
        """
        ...

    @abstractmethod
    async def get_latency(self, deployment_id: str) -> LatencyStats:
        """获取延迟统计.

        Raises:
            DeploymentNotFoundError: 部署不存在。
        """
        ...

    @abstractmethod
    async def get_throughput(self, deployment_id: str) -> ThroughputStats:
        """获取吞吐量统计.

        Raises:
            DeploymentNotFoundError: 部署不存在。
        """
        ...

    @abstractmethod
    async def get_error_rate(self, deployment_id: str) -> ErrorStats:
        """获取错误率统计.

        Raises:
            DeploymentNotFoundError: 部署不存在。
        """
        ...
