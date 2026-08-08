"""幻觉率指标。

hallucination = 幻觉样本数 / 总样本数

幻觉判定：
- 由评测模式在 PredictionResult.hallucination 字段标记
- 规则模式：基于事实核查（关键字/正则匹配参考答案）
- 模型模式：LLM as Judge 判定是否幻觉
- 人工模式：人工标注是否幻觉

本指标仅聚合已标记的幻觉结果，不负责幻觉判定本身。
"""

from __future__ import annotations

from typing import Iterable

from app.metrics.base import Metric
from app.models import PredictionResult


class HallucinationMetric(Metric):
    """幻觉率指标。"""

    name = "hallucination"
    description = "幻觉率 = 幻觉样本数 / 总样本数"

    def compute(self, predictions: Iterable[PredictionResult]) -> float:
        preds = list(predictions)
        if not preds:
            return 0.0
        hallucinated = sum(1 for p in preds if p.hallucination)
        return hallucinated / len(preds)
