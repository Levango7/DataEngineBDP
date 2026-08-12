"""监控业务逻辑."""

from __future__ import annotations

from llmops.interfaces.monitor import ModelMonitor
from llmops.models.monitor import (
    ErrorStats,
    LatencyStats,
    ModelMetrics,
    ThroughputStats,
)


class MonitorService:
    """监控服务（编排 ModelMonitor）."""

    def __init__(self, monitor: ModelMonitor) -> None:
        self._monitor = monitor

    async def get_metrics(self, deployment_id: str) -> ModelMetrics:
        return await self._monitor.get_metrics(deployment_id)

    async def get_latency(self, deployment_id: str) -> LatencyStats:
        return await self._monitor.get_latency(deployment_id)

    async def get_throughput(self, deployment_id: str) -> ThroughputStats:
        return await self._monitor.get_throughput(deployment_id)

    async def get_error_rate(self, deployment_id: str) -> ErrorStats:
        return await self._monitor.get_error_rate(deployment_id)
