"""六指标计算模块。

计算六指标：
- accuracy：准确率 = 正确数 / 总数
- recall：召回率 = 正确数 / 标准答案总数（多分类场景下与 accuracy 等价）
- f1：F1 = 2 * precision * recall / (precision + recall)
- latency_p95：P95 延迟（毫秒）
- cost：Token 成本（总 Token 数 × 单价）
- hallucination：幻觉率 = 幻觉数 / 总数
"""

from __future__ import annotations

from app.metrics.base import Metric, compute_all
from app.metrics.accuracy import AccuracyMetric
from app.metrics.cost import CostMetric
from app.metrics.f1 import F1Metric
from app.metrics.hallucination import HallucinationMetric
from app.metrics.latency import LatencyP95Metric
from app.metrics.recall import RecallMetric

__all__ = [
    "Metric",
    "compute_all",
    "AccuracyMetric",
    "RecallMetric",
    "F1Metric",
    "LatencyP95Metric",
    "CostMetric",
    "HallucinationMetric",
]