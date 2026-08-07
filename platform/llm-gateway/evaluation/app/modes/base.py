"""评测模式基类。

定义统一接口，所有评测模式需实现 judge 方法：
judge(sample, prediction) -> JudgeResult

JudgeResult 包含：
- correct：答案是否正确
- hallucination：是否幻觉
- reason：判定理由（调试用）
"""

from __future__ import annotations

import abc
from dataclasses import dataclass, field
from typing import Any, Optional

from app.models import EvalSample


@dataclass
class JudgeResult:
    """评测判定结果。"""

    correct: bool = False
    hallucination: bool = False
    reason: str = ""


class EvalModeBase(abc.ABC):
    """评测模式抽象基类。"""

    name: str = "base"
    description: str = ""

    @abc.abstractmethod
    def judge(
        self,
        sample: EvalSample,
        prediction: str,
        context: Optional[dict[str, Any]] = None,
    ) -> JudgeResult:
        """判定单条预测结果。

        Args:
            sample: 评测样本（含标准答案）
            prediction: 模型预测答案
            context: 上下文（如 LLM 客户端、人工标注等）

        Returns:
            JudgeResult
        """


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def _normalize_answer(answer: str) -> str:
    """归一化答案文本，便于比较。

    - 去除首尾空白
    - 转大写
    - 提取字母选项（A/B/C/D）
    """
    text = answer.strip().upper()
    # 若是单个字母，直接返回
    if len(text) == 1 and text in "ABCDEFGH":
        return text
    # 尝试提取首字母选项
    if text and text[0] in "ABCDEFGH" and (
        len(text) == 1 or text[1] in (".", ")", " ", "：", ":")
    ):
        return text[0]
    return text


def _is_correct_choice(prediction: str, answer: str) -> bool:
    """判定选择题答案是否正确。

    支持两种格式：
    - 字母选项：A/B/C/D
    - 文本答案：直接比较（忽略大小写与空白）
    """
    pred_norm = _normalize_answer(prediction)
    answer_norm = _normalize_answer(answer)
    if pred_norm == answer_norm:
        return True
    # 若 answer 是字母，prediction 是文本，尝试匹配选项内容
    # 此处仅做字母比较，文本匹配由具体模式处理
    return False


# ---------------------------------------------------------------------------
# 模式注册表
# ---------------------------------------------------------------------------
def get_mode(name: str, **kwargs: Any) -> EvalModeBase:
    """根据名称获取评测模式实例。

    Args:
        name: 模式名称（rule/model/human）
        **kwargs: 模式特定参数

    Returns:
        评测模式实例

    Raises:
        ValueError: 不支持的模式名称
    """
    # 延迟导入避免循环依赖
    from app.modes.human_mode import HumanMode
    from app.modes.model_mode import ModelMode
    from app.modes.rule_mode import RuleMode

    registry: dict[str, type[EvalModeBase]] = {
        "rule": RuleMode,
        "model": ModelMode,
        "human": HumanMode,
    }
    name_lower = name.lower()
    if name_lower not in registry:
        raise ValueError(
            f"不支持的评测模式: {name}，支持: {list(registry.keys())}"
        )
    return registry[name_lower](**kwargs)