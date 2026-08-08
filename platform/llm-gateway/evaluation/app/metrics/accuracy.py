"""准确率指标。

accuracy = 正确预测数 / 总预测数
"""

from __future__ import annotations

from typing import Iterable

from app.metrics.base import Metric
from app.models import PredictionResult


class AccuracyMetric(Metric):
    """准确率指标。"""

    name = "accuracy"
    description = "准确率 = 正确预测数 / 总预测数"

    def compute(self, predictions: Iterable[PredictionResult]) -> float:
        preds = list(predictions)
        if not preds:
            return 0.0
        correct = sum(1 for p in preds if p.correct)
        return correct / len(preds)
