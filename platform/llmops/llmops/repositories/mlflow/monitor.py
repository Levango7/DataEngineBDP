"""MLflow 监控实现 - 骨架.

实际指标由 Prometheus 采集（对齐 L4.5.6 计量链路：
    大模型网关 → X4 APISIX（prometheus 插件）→ L5.2 运营后台账单引擎）
本模块为骨架，通过 Prometheus client 拉取指标。
"""
from __future__ import annotations

from datetime import timedelta

from llmops.interfaces.monitor import ModelMonitor
from llmops.models.base import utc_now
from llmops.models.monitor import (
    ErrorStats,
    LatencyStats,
    ModelMetrics,
    ThroughputStats,
)
from llmops.repositories import DeploymentNotFoundError
from llmops.repositories.mlflow.client import MLflowClient


class MLflowModelMonitor(ModelMonitor):
    """基于 Prometheus 的监控（骨架）."""

    def __init__(self, client: MLflowClient) -> None:
        self._client = client
        self._known_deployments: set[str] = set()

    def register_deployment(self, deployment_id: str) -> None:
        self._known_deployments.add(deployment_id)

    def unregister_deployment(self, deployment_id: str) -> None:
        self._known_deployments.discard(deployment_id)

    def _check(self, deployment_id: str) -> None:
        if deployment_id not in self._known_deployments:
            raise DeploymentNotFoundError(deployment_id)

    async def get_metrics(self, deployment_id: str) -> ModelMetrics:
        self._check(deployment_id)
        # 骨架：实际通过 prometheus_client 查询
        now = utc_now()
        return ModelMetrics(
            deploymentId=deployment_id,
            windowStart=now - timedelta(minutes=5),
            windowEnd=now,
        )

    async def get_latency(self, deployment_id: str) -> LatencyStats:
        self._check(deployment_id)
        now = utc_now()
        return LatencyStats(
            deploymentId=deployment_id,
            windowStart=now - timedelta(minutes=5),
            windowEnd=now,
        )

    async def get_throughput(self, deployment_id: str) -> ThroughputStats:
        self._check(deployment_id)
        now = utc_now()
        return ThroughputStats(
            deploymentId=deployment_id,
            windowStart=now - timedelta(minutes=5),
            windowEnd=now,
        )

    async def get_error_rate(self, deployment_id: str) -> ErrorStats:
        self._check(deployment_id)
        now = utc_now()
        return ErrorStats(
            deploymentId=deployment_id,
            windowStart=now - timedelta(minutes=5),
            windowEnd=now,
        )