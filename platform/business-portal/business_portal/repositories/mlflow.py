"""MLflow 指标仓储 - 从真实 MLflow 拉取业务线门户所需指标.

设计要点：
1. jobCount / projectCount 从 MLflow experiments 数量与 runs 数量获取
2. accuracy 从 MLflow best run 的 metrics 获取
3. 替换 Mock 中的硬编码 jobCount=120 / accuracy=0.875
4. 通过环境变量 BP_MLFLOW_ENABLED=true 切换

本模块仅提供 MLflow 指标采集器 MLflowMetricsProvider，
具体 Store 实现仍由 Mock / SQLite 提供，二者组合使用：
    - MockStore 提供业务线/目录/报表等业务数据
    - MLflowMetricsProvider 提供 jobCount/accuracy 等真实指标
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

logger = logging.getLogger(__name__)


class MLflowMetricsProvider:
    """从真实 MLflow 拉取业务线门户所需指标.

    Args:
        trackingUri: MLflow Tracking URI，默认 http://localhost:5000
        fallbackJobCount:  MLflow 不可用时回退的 jobCount
        fallbackAccuracy:  MLflow 不可用时回退的 accuracy
    """

    def __init__(
        self,
        trackingUri: str = "http://localhost:5000",
        fallbackJobCount: int = 0,
        fallbackAccuracy: float = 0.0,
    ) -> None:
        self.trackingUri = trackingUri
        self.fallbackJobCount = fallbackJobCount
        self.fallbackAccuracy = fallbackAccuracy
        self._client = None

    def _getClient(self):
        """延迟初始化 MlflowClient."""
        if self._client is None:
            try:
                import mlflow
                from mlflow.tracking import MlflowClient
            except ImportError as e:  # pragma: no cover
                raise RuntimeError(f"mlflow 未安装: {e}。请安装 mlflow>=2.0") from e
            mlflow.set_tracking_uri(self.trackingUri)
            self._client = MlflowClient(tracking_uri=self.trackingUri)
        return self._client

    async def getJobCount(self) -> int:
        """从 MLflow 获取作业数 = 所有 active experiment 下的 run 总数.

        Returns:
            run 总数；MLflow 不可用时返回 fallbackJobCount。
        """
        try:
            client = self._getClient()
            experiments = await asyncio.to_thread(
                client.search_experiments,
                None,
                "lifecycle_stage = 'active'",
            )
            total = 0
            for exp in experiments:
                try:
                    runs = await asyncio.to_thread(client.search_runs, [exp.experiment_id])
                except Exception:
                    continue
                total += len(runs)
            return total
        except Exception as e:
            logger.warning("MLflow 不可用，回退 fallbackJobCount=%s: %s", self.fallbackJobCount, e)
            return self.fallbackJobCount

    async def getExperimentCount(self) -> int:
        """从 MLflow 获取 experiment 数量."""
        try:
            client = self._getClient()
            experiments = await asyncio.to_thread(
                client.search_experiments,
                None,
                "lifecycle_stage = 'active'",
            )
            return len(experiments)
        except Exception as e:
            logger.warning("MLflow 不可用，返回 0: %s", e)
            return 0

    async def getAccuracy(self) -> float:
        """从 MLflow best run 获取 accuracy 指标.

        跨所有 active experiment 寻找 accuracy 最高的 run。
        Returns:
            最优 accuracy；MLflow 不可用或无指标时返回 fallbackAccuracy。
        """
        try:
            client = self._getClient()
            experiments = await asyncio.to_thread(
                client.search_experiments,
                None,
                "lifecycle_stage = 'active'",
            )
            allAccuracies: list[float] = []
            for exp in experiments:
                try:
                    runs = await asyncio.to_thread(client.search_runs, [exp.experiment_id])
                except Exception:
                    continue
                for r in runs:
                    if "accuracy" in r.data.metrics:
                        allAccuracies.append(r.data.metrics["accuracy"].value)
            if not allAccuracies:
                return self.fallbackAccuracy
            return max(allAccuracies)
        except Exception as e:
            logger.warning("MLflow 不可用，回退 fallbackAccuracy=%s: %s", self.fallbackAccuracy, e)
            return self.fallbackAccuracy

    async def getMetrics(self) -> dict[str, Any]:
        """一次性获取所有真实指标.

        Returns:
            {
                "jobCount": int,
                "experimentCount": int,
                "accuracy": float,
                "source": "mlflow" | "fallback",
            }
        """
        jobCount = await self.getJobCount()
        experimentCount = await self.getExperimentCount()
        accuracy = await self.getAccuracy()
        source = "mlflow" if jobCount > 0 or experimentCount > 0 else "fallback"
        return {
            "jobCount": jobCount,
            "experimentCount": experimentCount,
            "accuracy": accuracy,
            "source": source,
        }


__all__ = ["MLflowMetricsProvider"]
