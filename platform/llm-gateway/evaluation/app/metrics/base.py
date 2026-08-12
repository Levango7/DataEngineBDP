"""指标基类与聚合计算。

定义统一接口，所有指标需实现 compute(predictions) -> float。
compute_all 函数一次性计算六指标，返回 MetricsBundle。
"""

from __future__ import annotations

import abc
from typing import Iterable

from app.models import MetricsBundle, PredictionResult


class Metric(abc.ABC):
    """指标抽象基类。"""

    name: str = "base"
    description: str = ""

    @abc.abstractmethod
    def compute(self, predictions: Iterable[PredictionResult]) -> float:
        """计算指标值。"""


def compute_all(
    predictions: list[PredictionResult],
    token_price_per_1k: float = 0.01,
) -> MetricsBundle:
    """一次性计算六指标。

    Args:
        predictions: 预测结果列表
        token_price_per_1k: 每 1K Token 单价（元）

    Returns:
        MetricsBundle，包含六指标
    """
    # 延迟导入避免循环依赖
    from app.metrics.accuracy import AccuracyMetric
    from app.metrics.cost import CostMetric
    from app.metrics.f1 import F1Metric
    from app.metrics.hallucination import HallucinationMetric
    from app.metrics.latency import LatencyP95Metric
    from app.metrics.recall import RecallMetric

    if not predictions:
        return MetricsBundle(
            accuracy=0.0,
            recall=0.0,
            f1=0.0,
            latency_p95=0.0,
            cost=0.0,
            hallucination=0.0,
        )

    accuracy = AccuracyMetric().compute(predictions)
    recall = RecallMetric().compute(predictions)
    f1 = F1Metric().compute(predictions)
    latency_p95 = LatencyP95Metric().compute(predictions)
    cost = CostMetric(token_price_per_1k=token_price_per_1k).compute(predictions)
    hallucination = HallucinationMetric().compute(predictions)

    return MetricsBundle(
        accuracy=accuracy,
        recall=recall,
        f1=f1,
        latency_p95=latency_p95,
        cost=cost,
        hallucination=hallucination,
    )
