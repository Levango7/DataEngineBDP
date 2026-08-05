"""Mock 特征存储 - 内存实现."""
from __future__ import annotations

import uuid
from typing import Any

from ml_platform.interfaces.feature_store import FeatureStore
from ml_platform.models import (
    FeatureGroup,
    FeatureGroupConfig,
    utcNow,
)
from ml_platform.repositories import (
    EntityNotFoundError,
    FeatureGroupAlreadyExistsError,
    FeatureGroupNotFoundError,
)


class MockFeatureStore(FeatureStore):
    """内存特征存储.

    数据结构：
        _groups:        groupName -> FeatureGroup
        _features:      groupName -> { entityId -> { feature -> value } }
    """

    def __init__(self) -> None:
        self._groups: dict[str, FeatureGroup] = {}
        self._features: dict[str, dict[str, dict[str, Any]]] = {}

    # ---------- 特征组管理 ----------

    async def create_feature_group(
        self, config: FeatureGroupConfig
    ) -> str:
        if config.name in self._groups:
            raise FeatureGroupAlreadyExistsError(config.name)
        groupId = str(uuid.uuid4())
        now = utcNow()
        group = FeatureGroup(
            id=groupId,
            name=config.name,
            config=config,
        )
        group.createdAt = now
        group.updatedAt = now
        self._groups[config.name] = group
        self._features[config.name] = {}
        return groupId

    async def get_feature_group(
        self, groupName: str
    ) -> FeatureGroup:
        if groupName not in self._groups:
            raise FeatureGroupNotFoundError(groupName)
        return self._groups[groupName]

    async def list_feature_groups(self) -> list[FeatureGroup]:
        return sorted(
            self._groups.values(),
            key=lambda g: g.createdAt,
            reverse=True,
        )

    # ---------- 特征读写 ----------

    async def get_features(
        self, groupName: str, entityId: str
    ) -> dict:
        if groupName not in self._groups:
            raise FeatureGroupNotFoundError(groupName)
        entityFeatures = self._features[groupName].get(entityId)
        if entityFeatures is None:
            raise EntityNotFoundError(groupName, entityId)
        return dict(entityFeatures)

    async def put_features(
        self, groupName: str, entityId: str, features: dict
    ) -> None:
        if groupName not in self._groups:
            raise FeatureGroupNotFoundError(groupName)
        self._features[groupName][entityId] = dict(features)
        # 更新时间戳
        self._groups[groupName].updatedAt = utcNow()

    async def delete_features(
        self, groupName: str, entityId: str
    ) -> None:
        if groupName not in self._groups:
            raise FeatureGroupNotFoundError(groupName)
        self._features[groupName].pop(entityId, None)

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._groups.clear()
        self._features.clear()

    def __len__(self) -> int:
        return len(self._groups)