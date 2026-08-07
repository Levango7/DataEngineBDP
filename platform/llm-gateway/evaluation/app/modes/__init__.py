"""评测模式模块。

三模式评测：
- RULE：规则模式，正则/关键字匹配判定答案正确性
- MODEL：模型模式，LLM as Judge，由评判模型判定答案正确性
- HUMAN：人工模式，人工标注界面，由人工标注答案正确性

每种模式实现 EvalMode 接口：
- judge(sample, prediction) -> JudgeResult（correct, hallucination）
"""

from __future__ import annotations

from app.modes.base import EvalModeBase, JudgeResult, get_mode
from app.modes.human_mode import HumanMode
from app.modes.model_mode import ModelMode
from app.modes.rule_mode import RuleMode

__all__ = [
    "EvalModeBase",
    "JudgeResult",
    "get_mode",
    "RuleMode",
    "ModelMode",
    "HumanMode",
]