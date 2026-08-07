"""A/B 对比报告生成器。

对比两个模型的评测结果，生成 A/B 对比报告：
1. 加载两个已完成任务的评测结果
2. 逐指标计算差异（绝对值 + 百分比）
3. 高亮差异超过阈值的指标
4. 生成 Markdown / HTML 报告

差异计算：
- diff = value_b - value_a
- diff_percent = (diff / value_a) × 100，若 value_a 为 0 则用 value_b
- highlighted = |diff| > threshold（相对阈值，对延迟/成本用绝对阈值）

更优判定：
- accuracy/recall/f1：值越大越优
- latency_p95/cost/hallucination：值越小越优
"""

from __future__ import annotations

import logging
from typing import Optional

from app.core.job_manager import JobManager
from app.models import (
    ABReport,
    JobInfo,
    JobStatus,
    MetricDiff,
    MetricsBundle,
    utcnow,
)
from app.report.templates import ReportTemplates

logger = logging.getLogger(__name__)


# 六指标名称（有序）
_METRIC_NAMES: list[str] = [
    "accuracy",
    "recall",
    "f1",
    "latency_p95",
    "cost",
    "hallucination",
]


class ABReportGenerator:
    """A/B 对比报告生成器。"""

    def __init__(self, job_manager: JobManager):
        self.job_manager = job_manager

    def generate(
        self,
        job_a_id: str,
        job_b_id: str,
        highlight_threshold: float = 0.05,
    ) -> ABReport:
        """生成 A/B 对比报告。

        Args:
            job_a_id: 模型 A 的任务 ID
            job_b_id: 模型 B 的任务 ID
            highlight_threshold: 差异高亮阈值

        Returns:
            ABReport

        Raises:
            ValueError: 任务不存在或未完成
        """
        job_a = self.job_manager.get(job_a_id)
        job_b = self.job_manager.get(job_b_id)

        if job_a is None:
            raise ValueError(f"任务 {job_a_id} 不存在")
        if job_b is None:
            raise ValueError(f"任务 {job_b_id} 不存在")
        if job_a.status != JobStatus.SUCCEEDED:
            raise ValueError(
                f"任务 {job_a_id} 状态为 {job_a.status.value}，需 SUCCEEDED"
            )
        if job_b.status != JobStatus.SUCCEEDED:
            raise ValueError(
                f"任务 {job_b_id} 状态为 {job_b.status.value}，需 SUCCEEDED"
            )
        if job_a.results is None or job_b.results is None:
            raise ValueError("任务结果为空")

        # 计算各指标差异
        diffs = self._compute_diffs(
            job_a.results, job_b.results, highlight_threshold
        )

        # 生成总结
        summary = self._build_summary(job_a, job_b, diffs)

        # 构造报告（先无内容，再渲染）
        report = ABReport(
            job_a=job_a_id,
            job_b=job_b_id,
            model_a=job_a.model,
            model_b=job_b.model,
            dataset=job_a.dataset,
            generated_at=utcnow(),
            diffs=diffs,
            summary=summary,
            content_markdown="",
            content_html="",
        )

        # 渲染内容
        report.content_markdown = ReportTemplates.render_markdown(report)
        report.content_html = ReportTemplates.render_html(report)

        return report

    def _compute_diffs(
        self,
        metrics_a: MetricsBundle,
        metrics_b: MetricsBundle,
        threshold: float,
    ) -> list[MetricDiff]:
        """计算各指标差异。"""
        values_a = self._metrics_to_dict(metrics_a)
        values_b = self._metrics_to_dict(metrics_b)

        diffs: list[MetricDiff] = []
        for name in _METRIC_NAMES:
            value_a = values_a[name]
            value_b = values_b[name]
            diff = value_b - value_a

            # 差异百分比
            if abs(value_a) > 1e-9:
                diff_percent = (diff / abs(value_a)) * 100
            elif abs(value_b) > 1e-9:
                diff_percent = 100.0 if diff != 0 else 0.0
            else:
                diff_percent = 0.0

            # 高亮判定：
            # - 对比例指标（accuracy/recall/f1/hallucination）：|diff| > threshold
            # - 对绝对指标（latency_p95/cost）：|diff| > threshold（绝对值）
            highlighted = abs(diff) > threshold

            # 更优判定
            better = self._judge_better(name, value_a, value_b)

            diffs.append(MetricDiff(
                name=name,
                value_a=value_a,
                value_b=value_b,
                diff=diff,
                diff_percent=diff_percent,
                highlighted=highlighted,
                better=better,
            ))
        return diffs

    @staticmethod
    def _metrics_to_dict(m: MetricsBundle) -> dict[str, float]:
        """MetricsBundle → dict。"""
        return {
            "accuracy": m.accuracy,
            "recall": m.recall,
            "f1": m.f1,
            "latency_p95": m.latency_p95,
            "cost": m.cost,
            "hallucination": m.hallucination,
        }

    @staticmethod
    def _judge_better(name: str, value_a: float, value_b: float) -> str:
        """判定哪个模型更优。

        Args:
            name: 指标名
            value_a: 模型 A 的值
            value_b: 模型 B 的值

        Returns:
            "a" / "b" / "tie"
        """
        higher_better = ReportTemplates.HIGHER_BETTER.get(name, True)
        if abs(value_a - value_b) < 1e-9:
            return "tie"
        if higher_better:
            return "a" if value_a > value_b else "b"
        else:
            return "a" if value_a < value_b else "b"

    @staticmethod
    def _build_summary(
        job_a: JobInfo,
        job_b: JobInfo,
        diffs: list[MetricDiff],
    ) -> str:
        """生成报告总结。"""
        # 统计各模型更优的指标数
        a_better = sum(1 for d in diffs if d.better == "a")
        b_better = sum(1 for d in diffs if d.better == "b")
        tie = sum(1 for d in diffs if d.better == "tie")
        highlighted = sum(1 for d in diffs if d.highlighted)

        summary = (
            f"模型 A（{job_a.model}）在 {a_better} 个指标上更优，"
            f"模型 B（{job_b.model}）在 {b_better} 个指标上更优，"
            f"{tie} 个指标持平。"
            f"共 {highlighted} 个指标差异超过高亮阈值。"
        )

        # 若有高亮指标，列出关键差异
        highlighted_diffs = [d for d in diffs if d.highlighted]
        if highlighted_diffs:
            key_diffs = "; ".join(
                f"{ReportTemplates.METRIC_CN.get(d.name, d.name)}"
                f"(A={d.value_a:.4f}, B={d.value_b:.4f})"
                for d in highlighted_diffs[:3]
            )
            summary += f" 关键差异：{key_diffs}。"

        return summary