"""P95 延迟指标。

latency_p95 = 所有样本延迟的第 95 百分位数（毫秒）

使用线性插值法计算百分位数（与 numpy.percentile 默认方法一致）。
"""

from __future__ import annotations

from typing import Iterable

from app.metrics.base import Metric
from app.models import PredictionResult


class LatencyP95Metric(Metric):
    """P95 延迟指标。"""

    name = "latency_p95"
    description = "P95 延迟（毫秒），所有样本延迟的第 95 百分位数"

    def compute(self, predictions: Iterable[PredictionResult]) -> float:
        preds = list(predictions)
        if not preds:
            return 0.0
        latencies = sorted(p.latency_ms for p in preds)
        return _percentile(latencies, 95)


def _percentile(sorted_values: list[float], q: float) -> float:
    """计算百分位数（线性插值法）。

    Args:
        sorted_values: 已排序的值列表
        q: 百分位数（0-100）

    Returns:
        百分位数值
    """
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]

    # 线性插值法（与 numpy.percentile 默认一致）
    rank = (q / 100) * (len(sorted_values) - 1)
    lower = int(rank)
    upper = lower + 1
    if upper >= len(sorted_values):
        return sorted_values[-1]
    frac = rank - lower
    return sorted_values[lower] + frac * (sorted_values[upper] - sorted_values[lower])
