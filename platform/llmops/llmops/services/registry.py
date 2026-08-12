"""服务注册表 - 根据配置构建 Mock 或 MLflow 实现并注入服务层.

设计模式：依赖注入 + 工厂。
配置开关：LLMOPS_STORE_TYPE=mock / mlflow
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from llmops.config.settings import Settings, get_settings
from llmops.interfaces.deployer import ModelDeployer
from llmops.interfaces.monitor import ModelMonitor
from llmops.interfaces.store import ModelStore
from llmops.interfaces.trainer import ModelTrainer
from llmops.services.deployment_service import DeploymentService
from llmops.services.model_service import ModelService
from llmops.services.monitor_service import MonitorService
from llmops.services.training_service import TrainingService


@dataclass
class ServiceRegistry:
    """服务注册表，聚合所有仓储与服务."""

    settings: Settings
    store: ModelStore
    trainer: ModelTrainer
    deployer: ModelDeployer
    monitor: ModelMonitor
    modelService: ModelService
    trainingService: TrainingService
    deploymentService: DeploymentService
    monitorService: MonitorService


def build_services(settings: Optional[Settings] = None) -> ServiceRegistry:
    """根据配置构建服务注册表.

    Args:
        settings: 配置，不传则使用全局单例。

    Returns:
        ServiceRegistry 实例。
    """
    if settings is None:
        settings = get_settings()

    if settings.isMock:
        store, trainer, deployer, monitor = _build_mock()
    else:
        store, trainer, deployer, monitor = _build_mlflow(settings)

    model_service = ModelService(store)
    training_service = TrainingService(trainer, store)
    deployment_service = DeploymentService(deployer, store, monitor)
    monitor_service = MonitorService(monitor)

    return ServiceRegistry(
        settings=settings,
        store=store,
        trainer=trainer,
        deployer=deployer,
        monitor=monitor,
        modelService=model_service,
        trainingService=training_service,
        deploymentService=deployment_service,
        monitorService=monitor_service,
    )


def _build_mock() -> tuple[ModelStore, ModelTrainer, ModelDeployer, ModelMonitor]:
    from llmops.repositories.mock import (
        MockModelDeployer,
        MockModelMonitor,
        MockModelStore,
        MockModelTrainer,
    )

    return (
        MockModelStore(),
        MockModelTrainer(),
        MockModelDeployer(),
        MockModelMonitor(),
    )


def _build_mlflow(
    settings: Settings,
) -> tuple[ModelStore, ModelTrainer, ModelDeployer, ModelMonitor]:
    from llmops.repositories.mlflow import (
        MLflowModelDeployer,
        MLflowModelMonitor,
        MLflowModelStore,
        MLflowModelTrainer,
    )
    from llmops.repositories.mlflow.client import MLflowClient

    client = MLflowClient(
        tracking_uri=settings.mlflowUri,
        registry_uri=settings.effectiveRegistryUri,
    )
    return (
        MLflowModelStore(client),
        MLflowModelTrainer(client),
        MLflowModelDeployer(client),
        MLflowModelMonitor(client),
    )
