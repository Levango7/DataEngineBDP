"""模型存储抽象接口（Model Registry）.

对齐 MLflow Model Registry 的注册/版本/Stage 概念，扩展大模型特有字段。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Optional

from llmops.models.model import ModelFilter, ModelInfo, ModelVersion


class ModelStore(ABC):
    """模型存储抽象接口.

    职责：模型的注册、查询、删除、版本管理。
    实现：MockModelStore（内存字典）/ MLflowModelStore（MLflow SDK）。
    """

    @abstractmethod
    async def register_model(self, model_info: ModelInfo) -> str:
        """注册一个新模型，返回 model_id.

        Args:
            model_info: 模型信息（id 可由实现生成或使用传入值）。

        Returns:
            模型 ID。
        """
        ...

    @abstractmethod
    async def get_model(self, model_id: str) -> ModelInfo:
        """根据 ID 获取模型详情.

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    @abstractmethod
    async def list_models(self, filter: ModelFilter) -> list[ModelInfo]:
        """按条件列出模型."""
        ...

    @abstractmethod
    async def delete_model(self, model_id: str) -> None:
        """删除模型.

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    @abstractmethod
    async def get_model_versions(self, model_id: str) -> list[ModelVersion]:
        """获取模型的所有版本.

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    @abstractmethod
    async def add_model_version(self, model_id: str, version: ModelVersion) -> ModelVersion:
        """为模型新增一个版本（训练完成后调用）.

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    @abstractmethod
    async def set_production_version(self, model_id: str, version: int) -> ModelInfo:
        """设置模型的生产版本.

        Raises:
            ModelNotFoundError: 模型不存在。
            VersionNotFoundError: 版本不存在。
        """
        ...

    @abstractmethod
    async def update_model(self, model_id: str, **fields) -> ModelInfo:
        """更新模型字段（部分更新）.

        Raises:
            ModelNotFoundError: 模型不存在。
        """
        ...

    async def find_model_by_name(self, name: str) -> Optional[ModelInfo]:
        """按名称查找模型（可选实现，默认基于 list_models）.

        Returns:
            模型信息或 None。
        """
        results = await self.list_models(ModelFilter(name=name, limit=1))
        return results[0] if results else None
