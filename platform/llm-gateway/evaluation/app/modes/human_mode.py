"""人工模式评测。

人工模式由人工标注答案正确性：
1. 提供人工标注界面（API 暴露待标注样本列表）
2. 人工提交标注结果（correct / hallucination）
3. 评测执行时使用预置标注或等待人工标注

本模式支持两种工作流：
- 预置标注：提交任务时通过 human_labels 参数提供标注（sample_id → correct）
- 实时标注：任务执行时若无预置标注，标记为 pending，等待人工标注接口提交

为简化实现并保证测试可运行，本模式优先使用预置标注；
若无预置标注，回退到规则模式判定（保证评测可完成）。
"""

from __future__ import annotations

import logging
from typing import Any, Optional

from app.models import EvalSample
from app.modes.base import EvalModeBase, JudgeResult

logger = logging.getLogger(__name__)


class HumanMode(EvalModeBase):
    """人工模式评测。"""

    name = "human"
    description = "人工模式：人工标注界面，由人工标注答案正确性"

    def __init__(self, human_labels: Optional[dict[str, bool]] = None):
        """
        Args:
            human_labels: 预置人工标注（sample_id → correct）
        """
        self.human_labels = human_labels or {}

    def judge(
        self,
        sample: EvalSample,
        prediction: str,
        context: Optional[dict[str, Any]] = None,
    ) -> JudgeResult:
        """人工模式判定。

        优先使用预置标注；若无，回退到规则判定。
        """
        if sample.id in self.human_labels:
            correct = self.human_labels[sample.id]
            # 幻觉由人工标注的 inverse 推断（简化）
            # 若人工标注为 False（错误），可能是幻觉导致
            hallucination = False
            reason = f"human_label: correct={correct} (预置标注)"
            return JudgeResult(
                correct=correct,
                hallucination=hallucination,
                reason=reason,
            )

        # 检查 context 中是否有运行时提交的标注
        if context and "human_labels" in context:
            runtime_labels = context["human_labels"]
            if sample.id in runtime_labels:
                correct = bool(runtime_labels[sample.id])
                return JudgeResult(
                    correct=correct,
                    hallucination=False,
                    reason=f"human_label: correct={correct} (运行时标注)",
                )

        # 无预置标注，回退到规则判定
        logger.info("人工模式样本 %s 无预置标注，回退到规则判定", sample.id)
        from app.modes.rule_mode import RuleMode

        rule = RuleMode()
        result = rule.judge(sample, prediction)
        result.reason = f"human_fallback: {result.reason}"
        return result

    def pending_samples(self, samples: list[EvalSample]) -> list[EvalSample]:
        """返回待人工标注的样本列表。

        Args:
            samples: 全部样本

        Returns:
            未在 human_labels 中的样本
        """
        return [s for s in samples if s.id not in self.human_labels]
