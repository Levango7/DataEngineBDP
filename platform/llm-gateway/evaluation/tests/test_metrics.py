"""metrics 指标计算单元测试。

覆盖六指标：accuracy / recall / f1 / latency_p95 / cost / hallucination
以及 compute_all 聚合函数。
"""

from __future__ import annotations

from app.metrics.accuracy import AccuracyMetric
from app.metrics.base import compute_all
from app.metrics.cost import CostMetric
from app.metrics.f1 import F1Metric
from app.metrics.hallucination import HallucinationMetric
from app.metrics.latency import LatencyP95Metric
from app.metrics.recall import RecallMetric
from app.models import PredictionResult
import pytest


def _make_pred(
    sample_id: str = "s1",
    correct: bool = True,
    latency_ms: float = 100.0,
    total_tokens: int = 10,
    hallucination: bool = False,
) -> PredictionResult:
    """构造测试用 PredictionResult。"""
    return PredictionResult(
        sample_id=sample_id,
        prediction="A",
        correct=correct,
        latency_ms=latency_ms,
        prompt_tokens=total_tokens // 2,
        completion_tokens=total_tokens - total_tokens // 2,
        total_tokens=total_tokens,
        hallucination=hallucination,
    )


# ---------------------------------------------------------------------------
# AccuracyMetric
# ---------------------------------------------------------------------------
class TestAccuracyMetric:
    def test_all_correct(self) -> None:
        preds = [_make_pred("s1", correct=True), _make_pred("s2", correct=True)]
        assert AccuracyMetric().compute(preds) == 1.0

    def test_all_wrong(self) -> None:
        preds = [_make_pred("s1", correct=False), _make_pred("s2", correct=False)]
        assert AccuracyMetric().compute(preds) == 0.0

    def test_half_correct(self) -> None:
        preds = [_make_pred("s1", correct=True), _make_pred("s2", correct=False)]
        assert AccuracyMetric().compute(preds) == 0.5

    def test_empty(self) -> None:
        assert AccuracyMetric().compute([]) == 0.0

    def test_name_and_description(self) -> None:
        m = AccuracyMetric()
        assert m.name == "accuracy"
        assert "准确率" in m.description


# ---------------------------------------------------------------------------
# RecallMetric
# ---------------------------------------------------------------------------
class TestRecallMetric:
    def test_all_correct_no_hallucination(self) -> None:
        preds = [
            _make_pred("s1", correct=True, hallucination=False),
            _make_pred("s2", correct=True, hallucination=False),
        ]
        assert RecallMetric().compute(preds) == 1.0

    def test_hallucination_reduces_recall(self) -> None:
        """幻觉样本不计入召回。"""
        preds = [
            _make_pred("s1", correct=True, hallucination=False),
            _make_pred("s2", correct=True, hallucination=True),
        ]
        assert RecallMetric().compute(preds) == 0.5

    def test_empty(self) -> None:
        assert RecallMetric().compute([]) == 0.0

    def test_name(self) -> None:
        assert RecallMetric().name == "recall"


# ---------------------------------------------------------------------------
# F1Metric
# ---------------------------------------------------------------------------
class TestF1Metric:
    def test_perfect(self) -> None:
        preds = [_make_pred("s1", correct=True), _make_pred("s2", correct=True)]
        assert F1Metric().compute(preds) == 1.0

    def test_zero(self) -> None:
        preds = [_make_pred("s1", correct=False), _make_pred("s2", correct=False)]
        assert F1Metric().compute(preds) == 0.0

    def test_half_with_hallucination(self) -> None:
        """两个都 correct=True，一个 hallucination=True。
        accuracy = 2/2 = 1.0, recall = 1/2 = 0.5
        F1 = 2*1.0*0.5/(1.0+0.5) = 0.6667
        """
        preds = [
            _make_pred("s1", correct=True, hallucination=False),
            _make_pred("s2", correct=True, hallucination=True),
        ]
        f1 = F1Metric().compute(preds)
        assert f1 == pytest.approx(2 * 1.0 * 0.5 / (1.0 + 0.5))

    def test_empty(self) -> None:
        assert F1Metric().compute([]) == 0.0


# ---------------------------------------------------------------------------
# LatencyP95Metric
# ---------------------------------------------------------------------------
class TestLatencyP95Metric:
    def test_single_value(self) -> None:
        preds = [_make_pred(latency_ms=100.0)]
        assert LatencyP95Metric().compute(preds) == 100.0

    def test_p95_of_20_values(self) -> None:
        """20 个值 0..19，P95 应接近 18.05（线性插值）。"""
        preds = [_make_pred(f"s{i}", latency_ms=float(i)) for i in range(20)]
        p95 = LatencyP95Metric().compute(preds)
        # rank = 0.95 * 19 = 18.05, lower=18, frac=0.05 → 18 + 0.05*(19-18) = 18.05
        assert p95 == pytest.approx(18.05)

    def test_empty(self) -> None:
        assert LatencyP95Metric().compute([]) == 0.0

    def test_name(self) -> None:
        assert LatencyP95Metric().name == "latency_p95"


# ---------------------------------------------------------------------------
# CostMetric
# ---------------------------------------------------------------------------
class TestCostMetric:
    def test_default_price(self) -> None:
        """总 1000 token × 0.01/1K = 0.01 元。"""
        preds = [_make_pred(total_tokens=1000)]
        assert CostMetric().compute(preds) == pytest.approx(0.01)

    def test_custom_price(self) -> None:
        preds = [_make_pred(total_tokens=2000)]
        assert CostMetric(token_price_per_1k=0.02).compute(preds) == pytest.approx(0.04)

    def test_empty(self) -> None:
        assert CostMetric().compute([]) == 0.0

    def test_name(self) -> None:
        assert CostMetric().name == "cost"


# ---------------------------------------------------------------------------
# HallucinationMetric
# ---------------------------------------------------------------------------
class TestHallucinationMetric:
    def test_no_hallucination(self) -> None:
        preds = [_make_pred(hallucination=False), _make_pred(hallucination=False)]
        assert HallucinationMetric().compute(preds) == 0.0

    def test_all_hallucination(self) -> None:
        preds = [_make_pred(hallucination=True), _make_pred(hallucination=True)]
        assert HallucinationMetric().compute(preds) == 1.0

    def test_half_hallucination(self) -> None:
        preds = [_make_pred(hallucination=True), _make_pred(hallucination=False)]
        assert HallucinationMetric().compute(preds) == 0.5

    def test_empty(self) -> None:
        assert HallucinationMetric().compute([]) == 0.0


# ---------------------------------------------------------------------------
# compute_all
# ---------------------------------------------------------------------------
class TestComputeAll:
    def test_empty_predictions(self) -> None:
        bundle = compute_all([])
        assert bundle.accuracy == 0.0
        assert bundle.recall == 0.0
        assert bundle.f1 == 0.0
        assert bundle.latency_p95 == 0.0
        assert bundle.cost == 0.0
        assert bundle.hallucination == 0.0

    def test_full_bundle(self) -> None:
        preds = [
            _make_pred("s1", correct=True, latency_ms=100.0, total_tokens=500, hallucination=False),
            _make_pred("s2", correct=False, latency_ms=200.0, total_tokens=1500, hallucination=True),
        ]
        bundle = compute_all(preds, token_price_per_1k=0.01)
        assert bundle.accuracy == 0.5
        # s1 correct & not hallucination → 1 hit / 2 = 0.5
        assert bundle.recall == 0.5
        assert bundle.hallucination == 0.5
        # 总 token = 2000, cost = 2000/1000 * 0.01 = 0.02
        assert bundle.cost == pytest.approx(0.02)
        # P95 of [100, 200] → rank=0.95*1=0.95, 100 + 0.95*(200-100) = 195
        assert bundle.latency_p95 == pytest.approx(195.0)

    def test_custom_price(self) -> None:
        preds = [_make_pred(total_tokens=10000)]
        bundle = compute_all(preds, token_price_per_1k=0.05)
        # 10000/1000 * 0.05 = 0.5
        assert bundle.cost == pytest.approx(0.5)
