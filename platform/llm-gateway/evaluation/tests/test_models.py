"""models Pydantic 数据模型单元测试。"""

from __future__ import annotations

from app.models import (
    ALL_METRICS,
    DatasetName,
    EvalMode,
    EvalSample,
    JobStatus,
    MetricName,
    MetricsBundle,
    PredictionResult,
)
from pydantic import ValidationError
import pytest


# ---------------------------------------------------------------------------
# 枚举
# ---------------------------------------------------------------------------
class TestEnums:
    def test_eval_mode_values(self) -> None:
        assert EvalMode.RULE.value == "rule"
        assert EvalMode.MODEL.value == "model"
        assert EvalMode.HUMAN.value == "human"

    def test_job_status_values(self) -> None:
        assert JobStatus.PENDING.value == "pending"
        assert JobStatus.RUNNING.value == "running"
        assert JobStatus.SUCCEEDED.value == "succeeded"
        assert JobStatus.FAILED.value == "failed"
        assert JobStatus.TERMINATED.value == "terminated"

    def test_dataset_name_values(self) -> None:
        assert DatasetName.MMLU.value == "mmlu"
        assert DatasetName.CMMLU.value == "cmmlu"
        assert DatasetName.CEVAL.value == "ceval"
        assert DatasetName.CUSTOM.value == "custom"

    def test_metric_name_values(self) -> None:
        assert MetricName.ACCURACY.value == "accuracy"
        assert MetricName.RECALL.value == "recall"
        assert MetricName.F1.value == "f1"
        assert MetricName.LATENCY_P95.value == "latency_p95"
        assert MetricName.COST.value == "cost"
        assert MetricName.HALLUCINATION.value == "hallucination"

    def test_all_metrics_count(self) -> None:
        assert len(ALL_METRICS) == 6


# ---------------------------------------------------------------------------
# EvalSample
# ---------------------------------------------------------------------------
class TestEvalSample:
    def test_minimal(self) -> None:
        s = EvalSample(id="s1", question="What is 2+2?")
        assert s.id == "s1"
        assert s.choices == []
        assert s.answer == ""
        assert s.subject == "unknown"
        assert s.context == ""

    def test_full(self) -> None:
        s = EvalSample(
            id="s1",
            question="q",
            choices=["A", "B", "C", "D"],
            answer="A",
            subject="math",
            context="2+2=4",
        )
        assert s.choices == ["A", "B", "C", "D"]
        assert s.subject == "math"

    def test_extra_fields_allowed(self) -> None:
        s = EvalSample(id="s1", question="q", extra_field="value")  # type: ignore[call-arg]
        assert s.model_extra is not None  # extra 字段被保留


# ---------------------------------------------------------------------------
# PredictionResult
# ---------------------------------------------------------------------------
class TestPredictionResult:
    def test_defaults(self) -> None:
        p = PredictionResult(sample_id="s1")
        assert p.prediction == ""
        assert p.correct is False
        assert p.latency_ms == 0.0
        assert p.total_tokens == 0
        assert p.hallucination is False
        assert p.raw_response == {}

    def test_full(self) -> None:
        p = PredictionResult(
            sample_id="s1",
            prediction="A",
            correct=True,
            latency_ms=123.45,
            prompt_tokens=10,
            completion_tokens=5,
            total_tokens=15,
            hallucination=False,
            raw_response={"key": "value"},
        )
        assert p.correct is True
        assert p.total_tokens == 15


# ---------------------------------------------------------------------------
# MetricsBundle
# ---------------------------------------------------------------------------
class TestMetricsBundle:
    def test_valid(self) -> None:
        b = MetricsBundle(
            accuracy=0.9,
            recall=0.85,
            f1=0.87,
            latency_p95=100.0,
            cost=0.5,
            hallucination=0.1,
        )
        assert b.accuracy == 0.9

    def test_accuracy_out_of_range(self) -> None:
        with pytest.raises(ValidationError):
            MetricsBundle(accuracy=1.5, recall=0, f1=0, latency_p95=0, cost=0, hallucination=0)

    def test_negative_latency(self) -> None:
        with pytest.raises(ValidationError):
            MetricsBundle(accuracy=0, recall=0, f1=0, latency_p95=-1, cost=0, hallucination=0)

    def test_to_list(self) -> None:
        b = MetricsBundle(
            accuracy=0.9,
            recall=0.85,
            f1=0.87,
            latency_p95=100.0,
            cost=0.5,
            hallucination=0.1,
        )
        lst = b.to_list()
        assert len(lst) == 6
        names = [r.name for r in lst]
        assert "accuracy" in names
        assert "recall" in names
        assert "f1" in names
        assert "latency_p95" in names
        assert "cost" in names
        assert "hallucination" in names
