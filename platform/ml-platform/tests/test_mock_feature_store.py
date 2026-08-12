"""Mock 特征存储测试."""

from __future__ import annotations

import pytest

from ml_platform.models import FeatureGroupConfig, FeatureSchema
from ml_platform.repositories import (
    EntityNotFoundError,
    FeatureGroupAlreadyExistsError,
    FeatureGroupNotFoundError,
)


@pytest.mark.asyncio
async def test_create_feature_group(mockFeatureStore):
    config = FeatureGroupConfig(
        name="user_features",
        entityKey="user_id",
        features=[
            FeatureSchema(name="age", dtype="int"),
            FeatureSchema(name="gender", dtype="string"),
        ],
    )
    groupId = await mockFeatureStore.create_feature_group(config)
    assert groupId is not None
    group = await mockFeatureStore.get_feature_group("user_features")
    assert group.name == "user_features"
    assert group.config.entityKey == "user_id"
    assert len(group.config.features) == 2


@pytest.mark.asyncio
async def test_create_duplicate_feature_group(mockFeatureStore):
    config = FeatureGroupConfig(name="g1")
    await mockFeatureStore.create_feature_group(config)
    with pytest.raises(FeatureGroupAlreadyExistsError):
        await mockFeatureStore.create_feature_group(config)


@pytest.mark.asyncio
async def test_get_feature_group_not_found(mockFeatureStore):
    with pytest.raises(FeatureGroupNotFoundError):
        await mockFeatureStore.get_feature_group("nonexistent")


@pytest.mark.asyncio
async def test_put_and_get_features(mockFeatureStore):
    await mockFeatureStore.create_feature_group(FeatureGroupConfig(name="g1"))
    await mockFeatureStore.put_features("g1", "user-1", {"age": 30, "gender": "M"})
    features = await mockFeatureStore.get_features("g1", "user-1")
    assert features["age"] == 30
    assert features["gender"] == "M"


@pytest.mark.asyncio
async def test_get_features_entity_not_found(mockFeatureStore):
    await mockFeatureStore.create_feature_group(FeatureGroupConfig(name="g1"))
    with pytest.raises(EntityNotFoundError):
        await mockFeatureStore.get_features("g1", "nonexistent")


@pytest.mark.asyncio
async def test_get_features_group_not_found(mockFeatureStore):
    with pytest.raises(FeatureGroupNotFoundError):
        await mockFeatureStore.get_features("nonexistent", "u1")


@pytest.mark.asyncio
async def test_put_features_group_not_found(mockFeatureStore):
    with pytest.raises(FeatureGroupNotFoundError):
        await mockFeatureStore.put_features("nonexistent", "u1", {"a": 1})


@pytest.mark.asyncio
async def test_delete_features(mockFeatureStore):
    await mockFeatureStore.create_feature_group(FeatureGroupConfig(name="g1"))
    await mockFeatureStore.put_features("g1", "u1", {"a": 1})
    await mockFeatureStore.delete_features("g1", "u1")
    with pytest.raises(EntityNotFoundError):
        await mockFeatureStore.get_features("g1", "u1")


@pytest.mark.asyncio
async def test_list_feature_groups(mockFeatureStore):
    await mockFeatureStore.create_feature_group(FeatureGroupConfig(name="g1"))
    await mockFeatureStore.create_feature_group(FeatureGroupConfig(name="g2"))
    groups = await mockFeatureStore.list_feature_groups()
    assert len(groups) == 2


@pytest.mark.asyncio
async def test_overwrite_features(mockFeatureStore):
    await mockFeatureStore.create_feature_group(FeatureGroupConfig(name="g1"))
    await mockFeatureStore.put_features("g1", "u1", {"a": 1})
    await mockFeatureStore.put_features("g1", "u1", {"a": 2, "b": 3})
    features = await mockFeatureStore.get_features("g1", "u1")
    assert features == {"a": 2, "b": 3}
