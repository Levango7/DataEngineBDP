"""Mock 模型存储测试."""
from __future__ import annotations

import uuid

import pytest

from llmops.models.base import ModelStatus, ModelType
from llmops.models.model import ModelFilter, ModelInfo, ModelParams, ModelVersion
from llmops.repositories import (
    ModelAlreadyExistsError,
    ModelNotFoundError,
    VersionNotFoundError,
)


def _make_base_model(name: str = "qiong-7B") -> ModelInfo:
    return ModelInfo(
        id=str(uuid.uuid4()),
        name=name,
        type=ModelType.BASE,
        params=ModelParams(paramSize="7B", architecture="qwen2"),
        tags={"provider": "shuqing"},
    )


def _make_ft_model(name: str, base_id: str) -> ModelInfo:
    return ModelInfo(
        id=str(uuid.uuid4()),
        name=name,
        type=ModelType.FT,
        baseModelId=base_id,
        params=ModelParams(finetuneMethod="lora"),
    )


@pytest.mark.asyncio
async def test_register_and_get_model(mock_store):
    """注册后可通过 id 获取."""
    m = _make_base_model()
    mid = await mock_store.register_model(m)
    assert mid == m.id

    got = await mock_store.get_model(mid)
    assert got.name == "qiong-7B"
    assert got.type == ModelType.BASE
    assert got.status == ModelStatus.DRAFT


@pytest.mark.asyncio
async def test_register_duplicate_name_raises(mock_store):
    """同名模型不可重复注册."""
    m1 = _make_base_model("qiong-7B")
    await mock_store.register_model(m1)
    m2 = _make_base_model("qiong-7B")
    with pytest.raises(ModelAlreadyExistsError):
        await mock_store.register_model(m2)


@pytest.mark.asyncio
async def test_get_nonexistent_model_raises(mock_store):
    with pytest.raises(ModelNotFoundError):
        await mock_store.get_model("nonexistent-id")


@pytest.mark.asyncio
async def test_list_models_with_filter(mock_store):
    """过滤条件生效."""
    base = _make_base_model("base-1")
    await mock_store.register_model(base)
    ft = _make_ft_model("ft-1", base.id)
    await mock_store.register_model(ft)

    # 全量
    all_models = await mock_store.list_models(ModelFilter())
    assert len(all_models) == 2

    # 按类型
    bases = await mock_store.list_models(ModelFilter(type=ModelType.BASE))
    assert len(bases) == 1
    assert bases[0].name == "base-1"

    fts = await mock_store.list_models(ModelFilter(type=ModelType.FT))
    assert len(fts) == 1
    assert fts[0].name == "ft-1"

    # 按名称模糊
    matched = await mock_store.list_models(ModelFilter(name="base"))
    assert len(matched) == 1

    # 按标签
    tagged = await mock_store.list_models(ModelFilter(tag="provider=shuqing"))
    assert len(tagged) == 1


@pytest.mark.asyncio
async def test_delete_model(mock_store):
    m = _make_base_model()
    mid = await mock_store.register_model(m)
    await mock_store.delete_model(mid)
    with pytest.raises(ModelNotFoundError):
        await mock_store.get_model(mid)


@pytest.mark.asyncio
async def test_delete_nonexistent_raises(mock_store):
    with pytest.raises(ModelNotFoundError):
        await mock_store.delete_model("no-such")


@pytest.mark.asyncio
async def test_add_version_and_auto_production(mock_store):
    """新增首个版本自动设为生产版本."""
    m = _make_base_model()
    mid = await mock_store.register_model(m)

    v1 = ModelVersion(version=1, modelId=mid, artifactUri="s3://bucket/m1")
    await mock_store.add_model_version(mid, v1)

    got = await mock_store.get_model(mid)
    assert len(got.versions) == 1
    assert got.currentVersion == 1
    assert got.versions[0].isProduction is True
    assert got.status == ModelStatus.READY


@pytest.mark.asyncio
async def test_set_production_version(mock_store):
    m = _make_base_model()
    mid = await mock_store.register_model(m)
    v1 = ModelVersion(version=1, modelId=mid)
    v2 = ModelVersion(version=2, modelId=mid)
    await mock_store.add_model_version(mid, v1)
    await mock_store.add_model_version(mid, v2)

    # 切换生产版本到 v2
    updated = await mock_store.set_production_version(mid, 2)
    assert updated.currentVersion == 2
    v2_after = [v for v in updated.versions if v.version == 2][0]
    v1_after = [v for v in updated.versions if v.version == 1][0]
    assert v2_after.isProduction is True
    assert v1_after.isProduction is False


@pytest.mark.asyncio
async def test_set_nonexistent_version_raises(mock_store):
    m = _make_base_model()
    mid = await mock_store.register_model(m)
    with pytest.raises(VersionNotFoundError):
        await mock_store.set_production_version(mid, 99)


@pytest.mark.asyncio
async def test_get_versions(mock_store):
    m = _make_base_model()
    mid = await mock_store.register_model(m)
    for i in range(1, 4):
        await mock_store.add_model_version(
            mid, ModelVersion(version=i, modelId=mid)
        )
    versions = await mock_store.get_model_versions(mid)
    assert [v.version for v in versions] == [1, 2, 3]


@pytest.mark.asyncio
async def test_update_model(mock_store):
    m = _make_base_model()
    mid = await mock_store.register_model(m)
    updated = await mock_store.update_model(
        mid, description="updated", tags={"env": "prod"}
    )
    assert updated.description == "updated"
    assert updated.tags["env"] == "prod"


@pytest.mark.asyncio
async def test_ft_model_requires_base(mock_store):
    """微调模型必须指定 baseModelId（Pydantic 校验）."""
    with pytest.raises(Exception):  # pydantic.ValidationError
        ModelInfo(id=str(uuid.uuid4()), name="bad-ft", type=ModelType.FT)


@pytest.mark.asyncio
async def test_base_model_should_not_have_base(mock_store):
    """基座模型不应指定 baseModelId（Pydantic 校验）."""
    with pytest.raises(Exception):  # pydantic.ValidationError
        ModelInfo(
            id=str(uuid.uuid4()),
            name="bad-base",
            type=ModelType.BASE,
            baseModelId="some-id",
        )