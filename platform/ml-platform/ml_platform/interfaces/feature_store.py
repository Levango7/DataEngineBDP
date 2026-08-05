"""特征存储抽象接口（FeatureStore）.

定义特征组的创建、特征读写契约。
实现：
    - MockFeatureStore:  内存特征存储
    - RedisFeatureStore: （可选）Redis 后端
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Optional

from ml_platform.models import FeatureGroup, FeatureGroupConfig


class FeatureStore(ABC):
    """特征存储抽象接口.

    职责：特征组的注册、特征的读写与查询。
    """

    # ---------- 特征组管理 ----------

    @abstractmethod
    async def create_feature_group(
        self, config: FeatureGroupConfig
    ) -> str:
        """创建特征组，返回特征组 ID.

        Args:
            config: 特征组配置（名称、实体键、特征 schema）。

        Returns:
            特征组 ID。

        Raises:
            FeatureGroupAlreadyExistsError: 同名特征组已存在。
        """
        ...

    @abstractmethod
    async def get_feature_group(
        self, groupName: str
    ) -> FeatureGroup:
        """获取特征组详情.

        Raises:
            FeatureGroupNotFoundError: 特征组不存在。
        """
        ...

    @abstractmethod
    async def list_feature_groups(self) -> list[FeatureGroup]:
        """列出所有特征组."""
        ...

    # ---------- 特征读写 ----------

    @abstractmethod
    async def get_features(
        self, groupName: str, entityId: str
    ) -> dict:
        """获取指定实体在某特征组的特征值.

        Args:
            groupName: 特征组名。
            entityId:  实体 ID。

        Returns:
            特征名 -> 值 的字典。

        Raises:
            FeatureGroupNotFoundError: 特征组不存在。
            EntityNotFoundError: 实体不存在。
        """
        ...

    @abstractmethod
    async def put_features(
        self, groupName: str, entityId: str, features: dict
    ) -> None:
        """写入/更新指定实体在某特征组的特征值.

        Args:
            groupName: 特征组名。
            entityId:  实体 ID。
            features:  特征名 -> 值 的字典。

        Raises:
            FeatureGroupNotFoundError: 特征组不存在。
        """
        ...

    @abstractmethod
    async def delete_features(
        self, groupName: str, entityId: str
    ) -> None:
        """删除指定实体在某特征组的特征.

        Raises:
            FeatureGroupNotFoundError: 特征组不存在。
        """
        ...

    async def find_feature_group(
        self, groupName: str
    ) -> Optional[FeatureGroup]:
        """按名称查找特征组（可选实现，默认基于 list）."""
        groups = await self.list_feature_groups()
        for g in groups:
            if g.name == groupName:
                return g
        return None