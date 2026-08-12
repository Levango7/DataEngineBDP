"""Mock 模型监控 - 生成模拟指标数据.

为运行中的部署生成确定性的"合理"指标，便于前端联调与测试。
真实环境由 Prometheus 采集（对齐 L4.5.6 计量链路）。
"""

from __future__ import annotations

from datetime import timedelta
import hashlib

from llmops.interfaces.monitor import ModelMonitor
from llmops.models.base import utc_now
from llmops.models.monitor import (
    ErrorStats,
    LatencyStats,
    ModelMetrics,
    ThroughputStats,
)
from llmops.repositories import DeploymentNotFoundError


def _seed(deployment_id: str) -> int:
    """从 deployment_id 生成确定性种子，保证同一部署指标稳定."""
    h = hashlib.md5(deployment_id.encode("utf-8")).digest()
    return int.from_bytes(h[:4], "big")


def _pseudo_random(seed: int, index: int) -> float:
    """简单确定性伪随机，返回 [0, 1)."""
    x = (seed * 1103515245 + index * 12345 + 12345) & 0x7FFFFFFF
    return x / 0x7FFFFFFF


class MockModelMonitor(ModelMonitor):
    """生成模拟监控指标的监控实现."""

    def __init__(self) -> None:
        # 记录已知部署（由 service 层注册，或通过 list_deployments 间接校验）
        self._known_deployments: set[str] = set()

    def register_deployment(self, deployment_id: str) -> None:
        """注册已知部署（service 层调用）."""
        self._known_deployments.add(deployment_id)

    def unregister_deployment(self, deployment_id: str) -> None:
        self._known_deployments.discard(deployment_id)

    def _check(self, deployment_id: str) -> None:
        if deployment_id not in self._known_deployments:
            raise DeploymentNotFoundError(deployment_id)

    # ---------- ModelMonitor ----------

    async def get_metrics(self, deployment_id: str) -> ModelMetrics:
        self._check(deployment_id)
        seed = _seed(deployment_id)
        now = utc_now()
        return ModelMetrics(
            deploymentId=deployment_id,
            accuracy=round(0.80 + _pseudo_random(seed, 1) * 0.15, 4),
            hallucinationRate=round(0.02 + _pseudo_random(seed, 2) * 0.05, 4),
            upliftVsBase=round(5.0 + _pseudo_random(seed, 3) * 15.0, 1),
            qps=round(10.0 + _pseudo_random(seed, 4) * 90.0, 2),
            errorRate=round(_pseudo_random(seed, 5) * 0.02, 4),
            windowStart=now - timedelta(minutes=5),
            windowEnd=now,
            sampleCount=1000 + int(_pseudo_random(seed, 6) * 9000),
        )

    async def get_latency(self, deployment_id: str) -> LatencyStats:
        self._check(deployment_id)
        seed = _seed(deployment_id)
        now = utc_now()
        base = 100.0 + _pseudo_random(seed, 7) * 200.0  # 100~300ms 基线
        return LatencyStats(
            deploymentId=deployment_id,
            avgMs=round(base, 2),
            p50Ms=round(base * 0.9, 2),
            p95Ms=round(base * 1.5, 2),
            p99Ms=round(base * 2.0, 2),
            maxMs=round(base * 2.5, 2),
            minMs=round(base * 0.5, 2),
            windowStart=now - timedelta(minutes=5),
            windowEnd=now,
            sampleCount=1000 + int(_pseudo_random(seed, 8) * 9000),
        )

    async def get_throughput(self, deployment_id: str) -> ThroughputStats:
        self._check(deployment_id)
        seed = _seed(deployment_id)
        now = utc_now()
        rps = round(10.0 + _pseudo_random(seed, 9) * 90.0, 2)
        tps = round(rps * (50 + _pseudo_random(seed, 10) * 100), 2)
        return ThroughputStats(
            deploymentId=deployment_id,
            rps=rps,
            tps=tps,
            totalRequests=int(rps * 300),
            totalTokens=int(tps * 300),
            windowStart=now - timedelta(minutes=5),
            windowEnd=now,
        )

    async def get_error_rate(self, deployment_id: str) -> ErrorStats:
        self._check(deployment_id)
        seed = _seed(deployment_id)
        now = utc_now()
        total = 1000 + int(_pseudo_random(seed, 11) * 9000)
        err = int(total * _pseudo_random(seed, 12) * 0.02)
        return ErrorStats(
            deploymentId=deployment_id,
            errorRate=round(err / total if total else 0.0, 4),
            errorCount=err,
            totalRequests=total,
            errorBreakdown={
                "timeout": int(err * 0.6),
                "oom": int(err * 0.3),
                "other": max(0, err - int(err * 0.6) - int(err * 0.3)),
            },
            windowStart=now - timedelta(minutes=5),
            windowEnd=now,
        )
