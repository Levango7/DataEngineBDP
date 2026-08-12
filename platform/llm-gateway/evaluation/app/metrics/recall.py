"""召回率指标。

对于多分类选择题评测，召回率与准确率等价：
recall = 正确预测数 / 总样本数

在更复杂的场景下（如开放域问答），召回率可定义为：
recall = 命中标准答案的预测数 / 标准答案总数

本实现采用多分类等价定义，保证与准确率一致但语义独立。
若 predictions 中存在 hallucination=True 的样本，召回率会相应降低，
体现"幻觉样本不应被视为命中"的语义。
"""

from __future__ import annotations

from typing import Iterable

from app.metrics.base import Metric
from app.models import PredictionResult


class RecallMetric(Metric):
    """召回率指标。"""

    name = "recall"
    description = "召回率 = 正确且非幻觉预测数 / 总样本数"

    def compute(self, predictions: Iterable[PredictionResult]) -> float:
        preds = list(predictions)
        if not preds:
            return 0.0
        # 正确且非幻觉视为有效召回
        hit = sum(1 for p in preds if p.correct and not p.hallucination)
        return hit / len(preds)
