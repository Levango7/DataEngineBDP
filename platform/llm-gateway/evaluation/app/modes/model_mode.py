"""模型模式评测（LLM as Judge）。

模型模式由评判模型（judge model）判定答案正确性：
1. 构造评判 prompt：包含问题、标准答案、模型预测
2. 调用评判模型（通过 LLM 网关）
3. 解析评判模型输出，得到 correct 与 hallucination

评判 prompt 模板：
```
你是一个评测专家。请判定以下模型预测是否正确。

问题：{question}
标准答案：{answer}
模型预测：{prediction}

请输出 JSON 格式：
{"correct": true/false, "hallucination": true/false, "reason": "..."}
```

若评判模型不可用（网络错误），回退到规则模式判定，保证可用性。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Optional

from app.modes.base import EvalModeBase, JudgeResult, _is_correct_choice
from app.models import EvalSample

logger = logging.getLogger(__name__)


# 评判 prompt 模板
_JUDGE_PROMPT_TEMPLATE = """你是一个评测专家。请判定以下模型预测是否正确。

问题：{question}
候选答案：{choices}
标准答案：{answer}
模型预测：{prediction}

请严格输出 JSON 格式（不要输出其他内容）：
{{"correct": true/false, "hallucination": true/false, "reason": "判定理由"}}

判定规则：
- correct：预测与标准答案语义一致时为 true
- hallucination：预测包含与事实矛盾的内容时为 true，否则 false
"""


class ModelMode(EvalModeBase):
    """模型模式评测（LLM as Judge）。"""

    name = "model"
    description = "模型模式：LLM as Judge，由评判模型判定答案正确性"

    def __init__(self, judge_model: str = "mock-gpt-4"):
        """
        Args:
            judge_model: 评判模型名（通过 LLM 网关调用）
        """
        self.judge_model = judge_model

    def judge(
        self,
        sample: EvalSample,
        prediction: str,
        context: Optional[dict[str, Any]] = None,
    ) -> JudgeResult:
        """模型模式判定。

        Args:
            sample: 评测样本
            prediction: 模型预测答案
            context: 必须包含 "llm_client"（LLM 网关客户端）

        Returns:
            JudgeResult
        """
        llm_client = None
        if context:
            llm_client = context.get("llm_client")

        if llm_client is None:
            # 无 LLM 客户端，回退到规则模式
            logger.warning("模型模式无 LLM 客户端，回退到规则判定")
            return self._fallback_judge(sample, prediction)

        # 构造评判 prompt
        prompt = _JUDGE_PROMPT_TEMPLATE.format(
            question=sample.question,
            choices=", ".join(sample.choices) if sample.choices else "无",
            answer=sample.answer,
            prediction=prediction,
        )

        try:
            # 调用评判模型
            response = llm_client.chat(
                model=self.judge_model,
                messages=[{"role": "user", "content": prompt}],
            )
            judge_text = response.get("content", "")
            return self._parse_judge_response(judge_text, sample, prediction)
        except Exception as e:  # noqa: BLE001
            logger.warning("评判模型调用失败，回退到规则判定: %s", e)
            return self._fallback_judge(sample, prediction)

    def _parse_judge_response(
        self,
        judge_text: str,
        sample: EvalSample,
        prediction: str,
    ) -> JudgeResult:
        """解析评判模型的 JSON 输出。

        评判模型可能输出：
        - 纯 JSON
        - JSON 嵌在 markdown 代码块中
        - 非标准格式（回退到规则判定）
        """
        # 尝试提取 JSON
        json_str = self._extract_json(judge_text)
        if json_str:
            try:
                data = json.loads(json_str)
                return JudgeResult(
                    correct=bool(data.get("correct", False)),
                    hallucination=bool(data.get("hallucination", False)),
                    reason=str(data.get("reason", "")),
                )
            except (json.JSONDecodeError, ValueError):
                pass

        # JSON 解析失败，回退到规则判定
        return self._fallback_judge(sample, prediction)

    @staticmethod
    def _extract_json(text: str) -> str:
        """从文本中提取 JSON 字符串。

        支持：
        - 纯 JSON
        - markdown 代码块包裹的 JSON
        - 文本中嵌入的 JSON
        """
        text = text.strip()
        # 尝试直接解析
        if text.startswith("{") and text.endswith("}"):
            return text
        # 尝试从 markdown 代码块提取
        code_block_match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
        if code_block_match:
            return code_block_match.group(1)
        # 尝试从文本中提取第一个 JSON 对象
        json_match = re.search(r"\{[^{}]*\"correct\"[^{}]*\}", text, re.DOTALL)
        if json_match:
            return json_match.group(0)
        return ""

    def _fallback_judge(self, sample: EvalSample, prediction: str) -> JudgeResult:
        """回退到规则判定（无 LLM 客户端或调用失败时）。"""
        from app.modes.rule_mode import RuleMode

        rule = RuleMode()
        return rule.judge(sample, prediction)