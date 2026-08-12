"""规则模式评测。

规则模式通过正则/关键字匹配判定答案正确性：
1. 选择题：归一化为字母（A/B/C/D），与标准答案比较
2. 关键字匹配：预测文本包含标准答案关键字视为正确
3. 正则匹配：预测文本匹配给定正则模式视为正确

幻觉判定（基于事实核查）：
- 若样本有 context（参考事实），预测文本与 context 矛盾视为幻觉
- 简化实现：若预测文本包含与 context 中明确矛盾的关键字，标记幻觉
- 若无 context，幻觉标记为 False
"""

from __future__ import annotations

import re
from typing import Any, Optional

from app.models import EvalSample
from app.modes.base import EvalModeBase, JudgeResult, _is_correct_choice


class RuleMode(EvalModeBase):
    """规则模式评测。"""

    name = "rule"
    description = "规则模式：正则/关键字匹配判定答案正确性"

    def __init__(self, patterns: Optional[list[str]] = None):
        """
        Args:
            patterns: 正则/关键字模式列表（可选，用于额外匹配规则）
        """
        self.patterns = patterns or []
        # 编译正则模式
        self._compiled_patterns: list[re.Pattern] = []
        for p in self.patterns:
            try:
                self._compiled_patterns.append(re.compile(p, re.IGNORECASE))
            except re.error:
                # 非正则，作为关键字处理
                self._compiled_patterns.append(re.compile(re.escape(p), re.IGNORECASE))

    def judge(
        self,
        sample: EvalSample,
        prediction: str,
        context: Optional[dict[str, Any]] = None,
    ) -> JudgeResult:
        """规则模式判定。"""
        pred = prediction.strip()
        answer = sample.answer.strip()

        # 1. 选择题判定（choices 非空）
        if sample.choices:
            correct = _is_correct_choice(pred, answer)
            # 若字母匹配失败，尝试文本匹配（预测文本包含标准选项内容）
            if not correct and answer.upper() in "ABCDEFGH":
                try:
                    idx = ord(answer.upper()) - ord("A")
                    if 0 <= idx < len(sample.choices):
                        correct_text = sample.choices[idx].strip().upper()
                        if correct_text and correct_text in pred.upper():
                            correct = True
                except (IndexError, ValueError):
                    pass
        else:
            # 2. 开放域问答：关键字匹配
            correct = answer.upper() in pred.upper() if answer else False

        # 3. 额外正则/关键字模式匹配（若提供）
        if not correct and self._compiled_patterns:
            for pattern in self._compiled_patterns:
                if pattern.search(pred):
                    # 若模式匹配且与标准答案一致，视为正确
                    if answer and answer.upper() in pattern.pattern.upper():
                        correct = True
                        break

        # 4. 幻觉判定（基于事实核查）
        hallucination = self._check_hallucination(sample, pred)

        reason = f"rule_match: correct={correct}, " f"hallucination={hallucination}"
        return JudgeResult(
            correct=correct,
            hallucination=hallucination,
            reason=reason,
        )

    def _check_hallucination(self, sample: EvalSample, prediction: str) -> bool:
        """基于事实核查判定幻觉。

        简化规则：
        - 若样本有 context，且预测文本与 context 明显矛盾，标记幻觉
        - 矛盾判定：预测文本包含 "不是" + context 关键字，或包含否定词
        - 若无 context，返回 False

        Args:
            sample: 评测样本
            prediction: 预测文本

        Returns:
            True 表示存在幻觉
        """
        if not sample.context:
            return False
        pred_lower = prediction.lower()
        ctx_lower = sample.context.lower()
        # 简化：若预测包含 "不是"/"否"/"no"/"not" + context 关键字，视为矛盾
        negation_words = ["不是", "否", "错误", "no", "not", "wrong", "false"]
        for neg in negation_words:
            if neg in pred_lower:
                # 检查是否同时包含 context 的关键字
                ctx_keywords = [w for w in ctx_lower.split() if len(w) > 1]
                for kw in ctx_keywords:
                    if kw in pred_lower:
                        return True
        return False
