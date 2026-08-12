"""Token 成本指标。

cost = 总 Token 数 × 每 1K Token 单价

总 Token 数 = sum(prediction.total_tokens)
单价通过构造参数注入，默认 0.01 元/1K Token。
"""

from __future__ import annotations

from typing import Iterable

from app.metrics.base import Metric
from app.models import PredictionResult


class CostMetric(Metric):
    """Token 成本指标。"""

    name = "cost"
    description = "Token 成本 = 总 Token 数 × 每 1K Token 单价"

    def __init__(self, token_price_per_1k: float = 0.01):
        self.token_price_per_1k = token_price_per_1k

    def compute(self, predictions: Iterable[PredictionResult]) -> float:
        preds = list(predictions)
        if not preds:
            return 0.0
        total_tokens = sum(p.total_tokens for p in preds)
        # 每 1K Token 单价，因此除以 1000
        return total_tokens / 1000.0 * self.token_price_per_1k
