"""pytest 共享 fixtures."""

from __future__ import annotations

import os

from fastapi.testclient import TestClient
import pytest

# 强制 Mock 模式
os.environ.setdefault("ML_BACKEND_TYPE", "mock")
os.environ.setdefault("ML_FEATURE_STORE_TYPE", "mock")
os.environ.setdefault("ML_EXPERIMENT_STORE_TYPE", "mock")

from ml_platform.api.app import createApp  # noqa: E402
from ml_platform.config.settings import Settings, resetSettings  # noqa: E402
from ml_platform.repositories.mock import (  # noqa: E402
    MockExperimentStore,
    MockFeatureStore,
    MockMLBackend,
)
from ml_platform.services.evaluation_service import EvaluationService  # noqa: E402
from ml_platform.services.experiment_service import ExperimentService  # noqa: E402
from ml_platform.services.feature_service import FeatureService  # noqa: E402
from ml_platform.services.prediction_service import PredictionService  # noqa: E402
from ml_platform.services.registry import ServiceRegistry  # noqa: E402
from ml_platform.services.training_service import TrainingService  # noqa: E402


@pytest.fixture
def mockBackend() -> MockMLBackend:
    return MockMLBackend()


@pytest.fixture
def mockFeatureStore() -> MockFeatureStore:
    return MockFeatureStore()


@pytest.fixture
def mockExperimentStore() -> MockExperimentStore:
    return MockExperimentStore()


@pytest.fixture
def settings() -> Settings:
    """测试用配置：三个后端位全部走 Mock.

    原先只传 `backendType="mock"`，另两位靠 `os.environ.setdefault("ML_*_STORE_TYPE")`
    生效——但那些带下划线的变量名在修复前根本绑不上（见 settings._envKwargs 说明），
    于是 /health 报的是真实默认值 redis/mlflow，`testHealth` 因此恒红却从未被采集。
    这里显式传参，让"测试跑在 Mock 栈上"这件事与断言一致。
    """
    resetSettings()
    return Settings(
        backendType="mock",
        featureStoreType="mock",
        experimentStoreType="mock",
    )


@pytest.fixture
def registry(
    mockBackend: MockMLBackend,
    mockFeatureStore: MockFeatureStore,
    mockExperimentStore: MockExperimentStore,
    settings: Settings,
) -> ServiceRegistry:
    """构建使用独立 Mock 实例的 registry（每个测试隔离）."""
    return ServiceRegistry(
        settings=settings,
        backend=mockBackend,
        featureStore=mockFeatureStore,
        experimentStore=mockExperimentStore,
        trainingService=TrainingService(mockBackend, mockExperimentStore),
        predictionService=PredictionService(mockBackend),
        evaluationService=EvaluationService(mockBackend),
        featureService=FeatureService(mockFeatureStore),
        experimentService=ExperimentService(mockExperimentStore),
    )


@pytest.fixture
def app(registry: ServiceRegistry):
    return createApp(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient（FastAPI 自动处理 async 路由）."""
    return TestClient(app)
