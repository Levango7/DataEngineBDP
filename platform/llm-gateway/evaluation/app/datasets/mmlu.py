"""MMLU 标准集适配器。

MMLU（Massive Multitask Language Understanding）：英文多任务语言理解评测集，
覆盖 57 个任务类别（STEM/人文/社科/其他），4 选 1 选择题。

本适配器提供：
- 内置样例数据（覆盖多个任务类别，无网络可运行）
- 远程下载接口（HuggingFace: cais/mmlu）
- 格式转换：MMLU 原始格式 → EvalSample

参考：https://github.com/hendrycks/test
"""

from __future__ import annotations

import logging
from typing import Optional

from app.datasets.base import DatasetAdapter
from app.models import EvalSample

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 内置样例数据（覆盖多个任务类别）
# ---------------------------------------------------------------------------
_MMLU_BUILTIN: list[dict] = [
    {
        "id": "mmlu-001",
        "question": "Which of the following is a prime number?",
        "choices": ["9", "15", "17", "21"],
        "answer": "C",  # 17
        "subject": "elementary_mathematics",
    },
    {
        "id": "mmlu-002",
        "question": "What is the capital of France?",
        "choices": ["London", "Paris", "Berlin", "Madrid"],
        "answer": "B",
        "subject": "geography",
    },
    {
        "id": "mmlu-003",
        "question": "Which planet is known as the Red Planet?",
        "choices": ["Venus", "Mars", "Jupiter", "Saturn"],
        "answer": "B",
        "subject": "astronomy",
    },
    {
        "id": "mmlu-004",
        "question": "Who wrote the play 'Romeo and Juliet'?",
        "choices": ["Charles Dickens", "William Shakespeare", "Jane Austen", "Mark Twain"],
        "answer": "B",
        "subject": "high_school_european_history",
    },
    {
        "id": "mmlu-005",
        "question": "What is the chemical symbol for water?",
        "choices": ["CO2", "H2O", "O2", "NaCl"],
        "answer": "B",
        "subject": "chemistry",
    },
    {
        "id": "mmlu-006",
        "question": "In Python, which keyword is used to define a function?",
        "choices": ["func", "def", "function", "lambda"],
        "answer": "B",
        "subject": "computer_science",
    },
    {
        "id": "mmlu-007",
        "question": "What does 'CPU' stand for?",
        "choices": [
            "Central Processing Unit",
            "Computer Personal Unit",
            "Central Process Utility",
            "Core Processing Unit",
        ],
        "answer": "A",
        "subject": "computer_science",
    },
    {
        "id": "mmlu-008",
        "question": "The Great Wall is located in which country?",
        "choices": ["Japan", "Korea", "China", "India"],
        "answer": "C",
        "subject": "geography",
    },
    {
        "id": "mmlu-009",
        "question": "Which of the following is NOT a programming language?",
        "choices": ["Python", "Java", "HTML", "Cobra"],
        "answer": "D",
        "subject": "computer_science",
    },
    {
        "id": "mmlu-010",
        "question": "What is 7 multiplied by 8?",
        "choices": ["54", "56", "64", "48"],
        "answer": "B",
        "subject": "elementary_mathematics",
    },
    {
        "id": "mmlu-011",
        "question": "Who is known as the father of modern physics?",
        "choices": ["Isaac Newton", "Albert Einstein", "Galileo Galilei", "Niels Bohr"],
        "answer": "B",
        "subject": "physics",
    },
    {
        "id": "mmlu-012",
        "question": "Which gas do plants absorb from the atmosphere for photosynthesis?",
        "choices": ["Oxygen", "Carbon Dioxide", "Nitrogen", "Hydrogen"],
        "answer": "B",
        "subject": "biology",
    },
]


class MMLUAdapter(DatasetAdapter):
    """MMLU 标准集适配器。"""

    name = "mmlu"
    language = "en"
    description = "MMLU：英文多任务语言理解评测集（57 任务，4 选 1）"

    def _builtin_samples(self) -> list[EvalSample]:
        """返回内置 MMLU 样例数据。"""
        return [
            EvalSample(
                id=s["id"],
                question=s["question"],
                choices=s["choices"],
                answer=s["answer"],
                subject=s["subject"],
            )
            for s in _MMLU_BUILTIN
        ]

    def _download_remote(self, limit: int = 0) -> list[EvalSample]:
        """从 HuggingFace 下载 MMLU 数据集。

        使用 datasets 库下载 cais/mmlu，转换为 EvalSample。
        若 datasets 库未安装或网络不可达，返回空列表。

        Args:
            limit: 限制样本数

        Returns:
            MMLU 样本列表（空列表表示未下载）
        """
        try:
            from datasets import load_dataset  # type: ignore
        except ImportError:
            logger.info("datasets 库未安装，跳过远程下载")
            return []

        try:
            # 加载 test split
            ds = load_dataset(
                "cais/mmlu", "all", split="test",
                cache_dir=self.cache_dir,
            )
            samples: list[EvalSample] = []
            for i, item in enumerate(ds):
                if limit > 0 and i >= limit:
                    break
                choices = item.get("choices", [])
                answer_idx = item.get("answer", 0)
                # MMLU answer 是索引，转换为字母 A/B/C/D
                answer_letter = chr(ord("A") + answer_idx) if isinstance(
                    answer_idx, int
                ) else str(answer_idx)
                samples.append(
                    EvalSample(
                        id=f"mmlu-remote-{i:05d}",
                        question=item.get("question", ""),
                        choices=list(choices),
                        answer=answer_letter,
                        subject=item.get("subject", "unknown"),
                    )
                )
            logger.info("MMLU 远程下载 %d 条", len(samples))
            return samples
        except Exception as e:  # noqa: BLE001
            logger.warning("MMLU 远程下载失败: %s", e)
            return []