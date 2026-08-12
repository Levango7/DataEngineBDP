"""标准集适配器模块。

支持 MMLU / CMMLU / CEval 三大标准集，提供：
- 数据集自动下载与缓存
- 标准集格式转换（转换为统一 EvalSample 格式）
- 内置样例数据（无需网络下载即可测试）
"""

from __future__ import annotations

from app.datasets.base import DatasetAdapter, get_adapter
from app.datasets.ceval import CEvalAdapter
from app.datasets.cmmlu import CMMLUAdapter
from app.datasets.mmlu import MMLUAdapter

__all__ = [
    "DatasetAdapter",
    "get_adapter",
    "MMLUAdapter",
    "CMMLUAdapter",
    "CEvalAdapter",
]
