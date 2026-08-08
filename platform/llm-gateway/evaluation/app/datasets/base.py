"""标准集适配器基类。

定义统一接口，所有标准集适配器（MMLU/CMMLU/CEval）需实现：
- load(limit)：加载数据集，返回 EvalSample 列表
- name：标准集名称
- language：语言（en/zh）

数据集加载策略：
1. 优先使用内置样例数据（保证测试可在无网络环境运行）
2. 若内置数据不足且配置允许，从 HuggingFace datasets 下载
3. 下载后缓存到本地，下次直接读缓存

设计要点：
- 内置样例数据覆盖多任务类别，保证评测可执行
- 真实下载逻辑封装在 _download_remote，可被 mock 替换
"""

from __future__ import annotations

import abc
import logging

from app.models import EvalSample

logger = logging.getLogger(__name__)


class DatasetAdapter(abc.ABC):
    """标准集适配器抽象基类。"""

    # 子类必须覆盖
    name: str = "base"
    language: str = "en"
    description: str = ""

    def __init__(self, cache_dir: str = "./.cache/datasets"):
        self.cache_dir = cache_dir

    @abc.abstractmethod
    def _builtin_samples(self) -> list[EvalSample]:
        """返回内置样例数据。

        每个适配器必须提供内置样例，保证无网络环境也可运行。
        样例应覆盖多个任务类别，至少 10 条。
        """

    def _download_remote(self, limit: int = 0) -> list[EvalSample]:
        """从远程下载完整数据集（子类可覆盖）。

        默认实现返回空列表，表示不下载远程数据。
        真实环境下子类可通过 HuggingFace datasets 下载。

        Args:
            limit: 限制样本数，0 表示全部

        Returns:
            远程数据样本列表（空列表表示未下载）
        """
        return []

    def load(self, limit: int = 0) -> list[EvalSample]:
        """加载数据集。

        优先使用内置样例数据；若 limit 大于内置数据量且允许远程下载，
        则尝试下载远程数据补充。

        Args:
            limit: 限制样本数，0 表示全部（仅内置）

        Returns:
            EvalSample 列表
        """
        samples = self._builtin_samples()
        logger.info("[%s] 内置样例 %d 条", self.name, len(samples))

        # 若需要更多数据，尝试远程下载
        if limit > len(samples) or (limit == 0 and len(samples) < 50):
            try:
                remote = self._download_remote(limit)
                if remote:
                    # 合并去重（按 id）
                    existing_ids = {s.id for s in samples}
                    for s in remote:
                        if s.id not in existing_ids:
                            samples.append(s)
                            existing_ids.add(s.id)
                    logger.info("[%s] 远程补充后共 %d 条", self.name, len(samples))
            except Exception as e:  # noqa: BLE001
                logger.warning("[%s] 远程下载失败，仅使用内置数据: %s", self.name, e)

        # 应用 limit
        if limit > 0:
            samples = samples[:limit]

        return samples

    def stats(self) -> dict[str, int]:
        """返回数据集统计信息（按 subject 分组）。"""
        samples = self._builtin_samples()
        stats: dict[str, int] = {}
        for s in samples:
            stats[s.subject] = stats.get(s.subject, 0) + 1
        return stats


# ---------------------------------------------------------------------------
# 适配器注册表
# ---------------------------------------------------------------------------
_ADAPTERS: dict[str, type[DatasetAdapter]] = {}


def register_adapter(name: str, cls: type[DatasetAdapter]) -> None:
    """注册适配器。"""
    _ADAPTERS[name] = cls


def get_adapter(name: str, cache_dir: str = "./.cache/datasets") -> DatasetAdapter:
    """根据名称获取适配器实例。

    Args:
        name: 标准集名称（mmlu/cmmlu/ceval）
        cache_dir: 缓存目录

    Returns:
        适配器实例

    Raises:
        ValueError: 不支持的标准集名称
    """
    # 延迟导入避免循环依赖
    from app.datasets.ceval import CEvalAdapter
    from app.datasets.cmmlu import CMMLUAdapter
    from app.datasets.mmlu import MMLUAdapter

    registry: dict[str, type[DatasetAdapter]] = {
        "mmlu": MMLUAdapter,
        "cmmlu": CMMLUAdapter,
        "ceval": CEvalAdapter,
    }
    name_lower = name.lower()
    if name_lower not in registry:
        raise ValueError(f"不支持的标准集: {name}，支持: {list(registry.keys())}")
    return registry[name_lower](cache_dir=cache_dir)
