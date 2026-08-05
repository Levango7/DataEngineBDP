"""pytest 共享 fixtures."""
from __future__ import annotations

import os
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from fastapi.testclient import TestClient

# 强制 Mock 模式
os.environ.setdefault("LLMOPS_STORE_TYPE", "mock")

from llmops.api.app import create_app
from llmops.config.settings import Settings, reset_settings
from llmops.repositories.mock import (
    MockModelDeployer,
    MockModelMonitor,
    MockModelStore,
    MockModelTrainer,
)
from llmops.services.deployment_service import DeploymentService
from llmops.services.model_service import ModelService
from llmops.services.monitor_service import MonitorService
from llmops.services.registry import ServiceRegistry
from llmops.services.training_service import TrainingService


@pytest.fixture
def mock_store() -> MockModelStore:
    return MockModelStore()


@pytest.fixture
def mock_trainer() -> MockModelTrainer:
    return MockModelTrainer()


@pytest.fixture
def mock_deployer() -> MockModelDeployer:
    return MockModelDeployer()


@pytest.fixture
def mock_monitor() -> MockModelMonitor:
    return MockModelMonitor()


@pytest.fixture
def settings() -> Settings:
    reset_settings()
    return Settings(storeType="mock")


@pytest.fixture
def registry(
    mock_store: MockModelStore,
    mock_trainer: MockModelTrainer,
    mock_deployer: MockModelDeployer,
    mock_monitor: MockModelMonitor,
    settings: Settings,
) -> ServiceRegistry:
    """构建使用独立 Mock 实例的 registry（每个测试隔离）."""
    return ServiceRegistry(
        settings=settings,
        store=mock_store,
        trainer=mock_trainer,
        deployer=mock_deployer,
        monitor=mock_monitor,
        modelService=ModelService(mock_store),
        trainingService=TrainingService(mock_trainer, mock_store),
        deploymentService=DeploymentService(mock_deployer, mock_store, mock_monitor),
        monitorService=MonitorService(mock_monitor),
    )


@pytest.fixture
def app(registry: ServiceRegistry):
    return create_app(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient（FastAPI 自动处理 async 路由）."""
    return TestClient(app)