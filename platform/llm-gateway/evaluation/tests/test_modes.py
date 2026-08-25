"""modes 评测模式单元测试。

覆盖：base 工具函数 / rule_mode / human_mode / get_mode 注册表
"""

from __future__ import annotations

from app.models import EvalSample
from app.modes.base import (
    JudgeResult,
    _is_correct_choice,
    _normalize_answer,
    get_mode,
)
from app.modes.human_mode import HumanMode
from app.modes.rule_mode import RuleMode
import pytest


# ---------------------------------------------------------------------------
# _normalize_answer
# ---------------------------------------------------------------------------
class TestNormalizeAnswer:
    def test_single_letter(self) -> None:
        assert _normalize_answer("A") == "A"

    def test_letter_with_paren(self) -> None:
        assert _normalize_answer("B)") == "B"

    def test_letter_with_dot(self) -> None:
        assert _normalize_answer("C.") == "C"

    def test_text_uppercase(self) -> None:
        assert _normalize_answer("hello") == "HELLO"

    def test_strip_whitespace(self) -> None:
        assert _normalize_answer("  d  ") == "D"


# ---------------------------------------------------------------------------
# _is_correct_choice
# ---------------------------------------------------------------------------
class TestIsCorrectChoice:
    def test_matching_letter(self) -> None:
        assert _is_correct_choice("A", "A") is True

    def test_different_letter(self) -> None:
        assert _is_correct_choice("B", "A") is False

    def test_case_insensitive(self) -> None:
        assert _is_correct_choice("a", "A") is True

    def test_text_match(self) -> None:
        assert _is_correct_choice("Paris", "paris") is True


# ---------------------------------------------------------------------------
# RuleMode
# ---------------------------------------------------------------------------
class TestRuleMode:
    def test_choice_correct(self) -> None:
        sample = EvalSample(id="s1", question="q", choices=["A", "B", "C", "D"], answer="A")
        result = RuleMode().judge(sample, "A")
        assert result.correct is True
        assert result.hallucination is False

    def test_choice_wrong(self) -> None:
        sample = EvalSample(id="s1", question="q", choices=["A", "B", "C", "D"], answer="A")
        result = RuleMode().judge(sample, "B")
        assert result.correct is False

    def test_choice_text_match(self) -> None:
        """预测文本包含正确选项内容视为正确。"""
        sample = EvalSample(
            id="s1",
            question="q",
            choices=["Apple", "Banana", "Cherry", "Date"],
            answer="A",
        )
        result = RuleMode().judge(sample, "The answer is Apple")
        assert result.correct is True

    def test_open_domain_keyword_match(self) -> None:
        sample = EvalSample(id="s1", question="q", answer="Python")
        result = RuleMode().judge(sample, "I love Python programming")
        assert result.correct is True

    def test_open_domain_no_match(self) -> None:
        sample = EvalSample(id="s1", question="q", answer="Python")
        result = RuleMode().judge(sample, "I love Java")
        assert result.correct is False

    def test_hallucination_with_context(self) -> None:
        """预测包含否定词 + context 关键字应标记幻觉。"""
        sample = EvalSample(
            id="s1",
            question="q",
            answer="yes",
            context="The Earth is round",
        )
        result = RuleMode().judge(sample, "The Earth is not round")
        assert result.hallucination is True

    def test_no_hallucination_without_context(self) -> None:
        sample = EvalSample(id="s1", question="q", answer="yes")
        result = RuleMode().judge(sample, "anything")
        assert result.hallucination is False

    def test_with_patterns(self) -> None:
        """额外正则模式匹配。"""
        sample = EvalSample(id="s1", question="q", answer="yes")
        mode = RuleMode(patterns=["yes"])
        result = mode.judge(sample, "yes")
        assert result.correct is True

    def test_name_and_description(self) -> None:
        m = RuleMode()
        assert m.name == "rule"
        assert "规则" in m.description


# ---------------------------------------------------------------------------
# HumanMode
# ---------------------------------------------------------------------------
class TestHumanMode:
    def test_preset_label_correct(self) -> None:
        sample = EvalSample(id="s1", question="q", answer="A")
        mode = HumanMode(human_labels={"s1": True})
        result = mode.judge(sample, "B")
        assert result.correct is True
        assert "预置标注" in result.reason

    def test_preset_label_wrong(self) -> None:
        sample = EvalSample(id="s1", question="q", answer="A")
        mode = HumanMode(human_labels={"s1": False})
        result = mode.judge(sample, "A")
        assert result.correct is False

    def test_runtime_label(self) -> None:
        sample = EvalSample(id="s1", question="q", answer="A")
        mode = HumanMode()
        result = mode.judge(sample, "A", context={"human_labels": {"s1": True}})
        assert result.correct is True
        assert "运行时标注" in result.reason

    def test_fallback_to_rule(self) -> None:
        """无预置标注应回退到规则判定。"""
        sample = EvalSample(id="s1", question="q", choices=["A", "B"], answer="A")
        mode = HumanMode()
        result = mode.judge(sample, "A")
        assert result.correct is True
        assert "human_fallback" in result.reason

    def test_pending_samples(self) -> None:
        samples = [
            EvalSample(id="s1", question="q", answer="A"),
            EvalSample(id="s2", question="q", answer="B"),
            EvalSample(id="s3", question="q", answer="C"),
        ]
        mode = HumanMode(human_labels={"s1": True})
        pending = mode.pending_samples(samples)
        assert len(pending) == 2
        assert all(s.id in {"s2", "s3"} for s in pending)

    def test_name(self) -> None:
        assert HumanMode().name == "human"


# ---------------------------------------------------------------------------
# get_mode 注册表
# ---------------------------------------------------------------------------
class TestGetMode:
    def test_get_rule_mode(self) -> None:
        mode = get_mode("rule")
        assert isinstance(mode, RuleMode)

    def test_get_human_mode(self) -> None:
        mode = get_mode("human")
        assert isinstance(mode, HumanMode)

    def test_case_insensitive(self) -> None:
        mode = get_mode("RULE")
        assert isinstance(mode, RuleMode)

    def test_unknown_mode_raises(self) -> None:
        with pytest.raises(ValueError, match="不支持"):
            get_mode("unknown")

    def test_pass_kwargs(self) -> None:
        mode = get_mode("human", human_labels={"s1": True})
        assert mode.human_labels == {"s1": True}


# ---------------------------------------------------------------------------
# JudgeResult 数据类
# ---------------------------------------------------------------------------
class TestJudgeResult:
    def test_defaults(self) -> None:
        r = JudgeResult()
        assert r.correct is False
        assert r.hallucination is False
        assert r.reason == ""

    def test_custom_values(self) -> None:
        r = JudgeResult(correct=True, hallucination=False, reason="ok")
        assert r.correct is True
        assert r.reason == "ok"
