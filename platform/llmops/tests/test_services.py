"""服务层测试."""

from __future__ import annotations

import uuid

import pytest

from llmops.models.base import ModelStatus, ModelType
from llmops.models.deployment import DeployConfig
from llmops.models.model import ModelInfo, ModelParams, ModelVersion
from llmops.models.training import TrainingConfig


def _make_base(name: str = "qiong-7B") -> ModelInfo:
    return ModelInfo(
        id=str(uuid.uuid4()),
        name=name,
        type=ModelType.BASE,
        params=ModelParams(paramSize="7B"),
    )


@pytest.mark.asyncio
async def test_model_service_register_and_get(registry):
    m = _make_base()
    saved = await registry.modelService.register_model(m)
    assert saved.id == m.id

    got = await registry.modelService.get_model(m.id)
    assert got.name == "qiong-7B"


@pytest.mark.asyncio
async def test_model_service_delete_deployed_raises(registry):
    """已部署的模型不可删除."""
    m = _make_base()
    await registry.modelService.register_model(m)
    # 添加版本
    await registry.store.add_model_version(m.id, ModelVersion(version=1, modelId=m.id))
    # 部署
    await registry.deploymentService.deploy_model(m.id, DeployConfig(modelId=m.id, modelVersion=1))
    with pytest.raises(ValueError, match="已部署"):
        await registry.modelService.delete_model(m.id)


@pytest.mark.asyncio
async def test_training_service_requires_base_model(registry):
    """训练必须基于已存在的基座模型."""
    cfg = TrainingConfig(
        baseModelId="nonexistent",
        outputModelName="ft-1",
        dataset="ds",
    )
    with pytest.raises(ValueError, match="基座模型不存在"):
        await registry.trainingService.create_training_job(cfg)


@pytest.mark.asyncio
async def test_training_service_requires_base_type(registry):
    """基座必须是 base 类型."""
    base = _make_base()
    await registry.modelService.register_model(base)
    # 注册一个 ft 模型作为错误基座
    ft = ModelInfo(
        id=str(uuid.uuid4()),
        name="ft-base",
        type=ModelType.FT,
        baseModelId=base.id,
    )
    await registry.modelService.register_model(ft)

    cfg = TrainingConfig(
        baseModelId=ft.id,
        outputModelName="ft-2",
        dataset="ds",
    )
    with pytest.raises(ValueError, match="不是 base 类型"):
        await registry.trainingService.create_training_job(cfg)


@pytest.mark.asyncio
async def test_training_service_full_flow(registry):
    """完整训练流程：创建 -> 推进 -> 评估 -> 注册产出."""
    base = _make_base()
    await registry.modelService.register_model(base)

    cfg = TrainingConfig(
        baseModelId=base.id,
        outputModelName="risk-domain-1.3B",
        dataset="risk-ds",
        epochs=2,
    )
    job = await registry.trainingService.create_training_job(cfg)
    assert job.status.status == "pending"

    # 推进到完成
    for _ in range(3):  # PENDING->RUNNING + 2 epochs
        await registry.trainer.advance(job.id)
    job = await registry.trainingService.get_training_status(job.id)
    assert job.status.status == "succeeded"

    # 评估
    metrics = await registry.trainingService.evaluate_model(job.id)
    assert metrics.accuracy > 0

    # 注册产出
    ft_model, version = await registry.trainingService.complete_and_register(job.id)
    assert ft_model.name == "risk-domain-1.3B"
    assert ft_model.type == ModelType.FT
    assert ft_model.baseModelId == base.id
    assert version.version == 1


@pytest.mark.asyncio
async def test_deployment_service_requires_model(registry):
    """部署不存在的模型应失败."""
    with pytest.raises(ValueError, match="模型不存在"):
        await registry.deploymentService.deploy_model("no-such", DeployConfig(modelId="no-such"))


@pytest.mark.asyncio
async def test_deployment_service_requires_version(registry):
    """没有版本的模型不可部署."""
    m = _make_base()
    await registry.modelService.register_model(m)
    with pytest.raises(ValueError, match="没有可用版本"):
        await registry.deploymentService.deploy_model(m.id, DeployConfig(modelId=m.id))


@pytest.mark.asyncio
async def test_deployment_service_flow(registry):
    """完整部署流程."""
    m = _make_base()
    await registry.modelService.register_model(m)
    await registry.store.add_model_version(m.id, ModelVersion(version=1, modelId=m.id))

    dep = await registry.deploymentService.deploy_model(m.id, DeployConfig(modelId=m.id, replica=2))
    assert dep.status.status == "creating"

    # 模型状态应更新为 DEPLOYED
    m_after = await registry.modelService.get_model(m.id)
    assert m_after.status == ModelStatus.DEPLOYED

    # 卸载
    await registry.deploymentService.undeploy_model(dep.id)
    dep_after = await registry.deploymentService.get_deployment_status(dep.id)
    assert dep_after.status.status == "stopping"


@pytest.mark.asyncio
async def test_monitor_service(registry):
    """监控服务指标获取."""
    m = _make_base()
    await registry.modelService.register_model(m)
    await registry.store.add_model_version(m.id, ModelVersion(version=1, modelId=m.id))
    dep = await registry.deploymentService.deploy_model(m.id, DeployConfig(modelId=m.id, modelVersion=1))

    metrics = await registry.monitorService.get_metrics(dep.id)
    assert metrics.deploymentId == dep.id

    latency = await registry.monitorService.get_latency(dep.id)
    assert latency.deploymentId == dep.id
