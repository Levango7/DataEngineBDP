"""CMMLU 标准集适配器。

CMMLU（Chinese Massive Multitask Language Understanding）：中文多任务语言理解评测集，
覆盖 67 个任务类别，4 选 1 选择题，中文版本。

本适配器提供：
- 内置样例数据（中文，覆盖多个任务类别）
- 远程下载接口（HuggingFace: haonan-li/cmmlu）
- 格式转换：CMMLU 原始格式 → EvalSample

参考：https://github.com/haonan-li/CMMLU
"""

from __future__ import annotations

import logging

from app.datasets.base import DatasetAdapter
from app.models import EvalSample

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 内置样例数据（中文，覆盖多个任务类别）
# ---------------------------------------------------------------------------
_CMMLU_BUILTIN: list[dict] = [
    {
        "id": "cmmlu-001",
        "question": "以下哪个数是质数？",
        "choices": ["9", "15", "17", "21"],
        "answer": "C",
        "subject": "elementary_mathematics",
    },
    {
        "id": "cmmlu-002",
        "question": "中国的首都是哪里？",
        "choices": ["上海", "北京", "广州", "深圳"],
        "answer": "B",
        "subject": "geography",
    },
    {
        "id": "cmmlu-003",
        "question": "《红楼梦》的作者是谁？",
        "choices": ["罗贯中", "曹雪芹", "施耐庵", "吴承恩"],
        "answer": "B",
        "subject": "high_school_chinese",
    },
    {
        "id": "cmmlu-004",
        "question": "水的化学式是什么？",
        "choices": ["CO2", "H2O", "O2", "NaCl"],
        "answer": "B",
        "subject": "chemistry",
    },
    {
        "id": "cmmlu-005",
        "question": "Python 中定义函数使用哪个关键字？",
        "choices": ["func", "def", "function", "lambda"],
        "answer": "B",
        "subject": "computer_science",
    },
    {
        "id": "cmmlu-006",
        "question": "长城位于哪个国家？",
        "choices": ["日本", "韩国", "中国", "印度"],
        "answer": "C",
        "subject": "geography",
    },
    {
        "id": "cmmlu-007",
        "question": "7 乘以 8 等于多少？",
        "choices": ["54", "56", "64", "48"],
        "answer": "B",
        "subject": "elementary_mathematics",
    },
    {
        "id": "cmmlu-008",
        "question": "光速约为多少千米/秒？",
        "choices": ["30 万", "3 万", "300 万", "3000"],
        "answer": "A",
        "subject": "physics",
    },
    {
        "id": "cmmlu-009",
        "question": "植物光合作用吸收的气体是？",
        "choices": ["氧气", "二氧化碳", "氮气", "氢气"],
        "answer": "B",
        "subject": "biology",
    },
    {
        "id": "cmmlu-010",
        "question": "唐朝最著名的诗人不包括以下哪位？",
        "choices": ["李白", "杜甫", "白居易", "苏轼"],
        "answer": "D",
        "subject": "high_school_chinese",
    },
    {
        "id": "cmmlu-011",
        "question": "下列哪个不是操作系统？",
        "choices": ["Windows", "Linux", "Photoshop", "macOS"],
        "answer": "C",
        "subject": "computer_science",
    },
    {
        "id": "cmmlu-012",
        "question": "中国最长的河流是？",
        "choices": ["黄河", "长江", "珠江", "黑龙江"],
        "answer": "B",
        "subject": "geography",
    },
]


class CMMLUAdapter(DatasetAdapter):
    """CMMLU 标准集适配器。"""

    name = "cmmlu"
    language = "zh"
    description = "CMMLU：中文多任务语言理解评测集（67 任务，4 选 1）"

    def _builtin_samples(self) -> list[EvalSample]:
        """返回内置 CMMLU 样例数据。"""
        return [
            EvalSample(
                id=s["id"],
                question=s["question"],
                choices=s["choices"],
                answer=s["answer"],
                subject=s["subject"],
            )
            for s in _CMMLU_BUILTIN
        ]

    def _download_remote(self, limit: int = 0) -> list[EvalSample]:
        """从 HuggingFace 下载 CMMLU 数据集。"""
        try:
            from datasets import load_dataset  # type: ignore
        except ImportError:
            logger.info("datasets 库未安装，跳过远程下载")
            return []

        try:
            ds = load_dataset(
                "haonan-li/cmmlu", "all", split="test",
                cache_dir=self.cache_dir,
            )
            samples: list[EvalSample] = []
            for i, item in enumerate(ds):
                if limit > 0 and i >= limit:
                    break
                choices = item.get("choices", [])
                answer_idx = item.get("answer", 0)
                answer_letter = chr(ord("A") + answer_idx) if isinstance(
                    answer_idx, int
                ) else str(answer_idx)
                samples.append(
                    EvalSample(
                        id=f"cmmlu-remote-{i:05d}",
                        question=item.get("question", ""),
                        choices=list(choices),
                        answer=answer_letter,
                        subject=item.get("subject", "unknown"),
                    )
                )
            logger.info("CMMLU 远程下载 %d 条", len(samples))
            return samples
        except Exception as e:  # noqa: BLE001
            logger.warning("CMMLU 远程下载失败: %s", e)
            return []