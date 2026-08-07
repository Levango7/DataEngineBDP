"""F1 指标。

F1 = 2 * precision * recall / (precision + recall)

对于多分类选择题评测，precision = accuracy（预测为某类的样本中正确的比例），
recall 同上，因此 F1 = accuracy。

本实现采用通用定义：F1 = 2 * accuracy * recall / (accuracy + recall)，
保证语义独立，当 accuracy 与 recall 不等时 F1 体现调和平均。
"""

from __future__ import annotations

from typing import Iterable

from app.metrics.base import Metric
from app.metrics.recall import RecallMetric
from app.metrics.accuracy import AccuracyMetric
from app.models import PredictionResult


class F1Metric(Metric):
    """F1 指标。"""

    name = "f1"
    description = "F1 = 2 * precision * recall / (precision + recall)"

    def compute(self, predictions: Iterable[PredictionResult]) -> float:
        preds = list(predictions)
        if not preds:
            return 0.0
        # precision 用 accuracy 近似（多分类场景）
        precision = AccuracyMetric().compute(preds)
        recall = RecallMetric().compute(preds)
        if precision + recall == 0:
            return 0.0
        return 2 * precision * recall / (precision + recall)