"""特征工程服务业务逻辑."""

from __future__ import annotations

from ml_platform.interfaces.feature_store import FeatureStore
from ml_platform.models import (
    FeatureGroup,
    FeatureGroupConfig,
)


class FeatureService:
    """特征工程服务（编排 FeatureStore）."""

    def __init__(self, featureStore: FeatureStore) -> None:
        self._store = featureStore

    async def createFeatureGroup(self, config: FeatureGroupConfig) -> FeatureGroup:
        """创建特征组，返回完整信息."""
        await self._store.create_feature_group(config)
        return await self._store.get_feature_group(config.name)

    async def getFeatureGroup(self, groupName: str) -> FeatureGroup:
        return await self._store.get_feature_group(groupName)

    async def listFeatureGroups(self) -> list[FeatureGroup]:
        return await self._store.list_feature_groups()

    async def getFeatures(self, groupName: str, entityId: str) -> dict:
        return await self._store.get_features(groupName, entityId)

    async def putFeatures(self, groupName: str, entityId: str, features: dict) -> None:
        await self._store.put_features(groupName, entityId, features)

    async def deleteFeatures(self, groupName: str, entityId: str) -> None:
        await self._store.delete_features(groupName, entityId)
