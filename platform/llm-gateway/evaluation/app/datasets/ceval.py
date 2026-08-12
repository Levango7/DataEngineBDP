"""CEval 标准集适配器。

CEval（Chinese Evaluation）：中文大模型评测集，
覆盖 52 个任务类别（4 个难度等级），4 选 1 选择题。

本适配器提供：
- 内置样例数据（中文，覆盖多个任务类别与难度）
- 远程下载接口（HuggingFace: ceval/ceval-exam）
- 格式转换：CEval 原始格式 → EvalSample

参考：https://github.com/SJTU-LIT/ceval
"""

from __future__ import annotations

import logging

from app.datasets.base import DatasetAdapter
from app.models import EvalSample

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 内置样例数据（中文，覆盖多个任务类别）
# ---------------------------------------------------------------------------
_CEVAL_BUILTIN: list[dict] = [
    {
        "id": "ceval-001",
        "question": "以下哪个是中国四大发明之一？",
        "choices": ["蒸汽机", "造纸术", "电灯", "电话"],
        "answer": "B",
        "subject": "high_school_chinese",
    },
    {
        "id": "ceval-002",
        "question": "勾股定理中，若两直角边为 3 和 4，斜边长度为？",
        "choices": ["5", "6", "7", "8"],
        "answer": "A",
        "subject": "high_school_mathematics",
    },
    {
        "id": "ceval-003",
        "question": "下列哪个是中国古代的朝代？",
        "choices": ["唐朝", "罗马", "波斯", "埃及"],
        "answer": "A",
        "subject": "high_school_history",
    },
    {
        "id": "ceval-004",
        "question": "地球绕太阳公转一周大约需要多少天？",
        "choices": ["24 天", "30 天", "365 天", "100 天"],
        "answer": "C",
        "subject": "high_school_geography",
    },
    {
        "id": "ceval-005",
        "question": "下列哪个是可再生能源？",
        "choices": ["煤炭", "石油", "太阳能", "天然气"],
        "answer": "C",
        "subject": "high_school_chemistry",
    },
    {
        "id": "ceval-006",
        "question": "人体最大的器官是？",
        "choices": ["心脏", "肝脏", "皮肤", "肺"],
        "answer": "C",
        "subject": "high_school_biology",
    },
    {
        "id": "ceval-007",
        "question": "下列哪个不是中国的直辖市？",
        "choices": ["北京", "上海", "广州", "天津"],
        "answer": "C",
        "subject": "middle_school_geography",
    },
    {
        "id": "ceval-008",
        "question": "计算机中 1 Byte 等于多少 bit？",
        "choices": ["4", "8", "16", "32"],
        "answer": "B",
        "subject": "computer_network",
    },
    {
        "id": "ceval-009",
        "question": "下列哪个法律是中国的根本大法？",
        "choices": ["刑法", "宪法", "民法", "行政法"],
        "answer": "B",
        "subject": "law",
    },
    {
        "id": "ceval-010",
        "question": "经济学中，GDP 的全称是？",
        "choices": [
            "国内生产总值",
            "国民生产总值",
            "国内消费总值",
            "国民收入总值",
        ],
        "answer": "A",
        "subject": "economics",
    },
    {
        "id": "ceval-011",
        "question": "下列哪个不是 Python 的数据类型？",
        "choices": ["int", "str", "list", "array"],
        "answer": "D",
        "subject": "computer_science",
    },
    {
        "id": "ceval-012",
        "question": "中医理论中，五行不包括以下哪个？",
        "choices": ["金", "木", "水", "风"],
        "answer": "D",
        "subject": "traditional_chinese_medicine",
    },
]


class CEvalAdapter(DatasetAdapter):
    """CEval 标准集适配器。"""

    name = "ceval"
    language = "zh"
    description = "CEval：中文大模型评测集（52 任务，4 难度，4 选 1）"

    def _builtin_samples(self) -> list[EvalSample]:
        """返回内置 CEval 样例数据。"""
        return [
            EvalSample(
                id=s["id"],
                question=s["question"],
                choices=s["choices"],
                answer=s["answer"],
                subject=s["subject"],
            )
            for s in _CEVAL_BUILTIN
        ]

    def _download_remote(self, limit: int = 0) -> list[EvalSample]:
        """从 HuggingFace 下载 CEval 数据集。"""
        try:
            from datasets import load_dataset  # type: ignore
        except ImportError:
            logger.info("datasets 库未安装，跳过远程下载")
            return []

        try:
            ds = load_dataset(
                "ceval/ceval-exam",
                "all",
                split="test",
                cache_dir=self.cache_dir,
            )
            samples: list[EvalSample] = []
            for i, item in enumerate(ds):
                if limit > 0 and i >= limit:
                    break
                choices = [
                    item.get("A", ""),
                    item.get("B", ""),
                    item.get("C", ""),
                    item.get("D", ""),
                ]
                answer = item.get("answer", "")
                samples.append(
                    EvalSample(
                        id=f"ceval-remote-{i:05d}",
                        question=item.get("question", ""),
                        choices=choices,
                        answer=answer,
                        subject=item.get("subject", "unknown"),
                    )
                )
            logger.info("CEval 远程下载 %d 条", len(samples))
            return samples
        except Exception as e:  # noqa: BLE001
            logger.warning("CEval 远程下载失败: %s", e)
            return []
