"""pytest 共享 fixtures."""
from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient

# 强制 Mock 模式
os.environ.setdefault("ML_BACKEND_TYPE", "mock")
os.environ.setdefault("ML_FEATURE_STORE_TYPE", "mock")
os.environ.setdefault("ML_EXPERIMENT_STORE_TYPE", "mock")

from ml_platform.api.app import createApp
from ml_platform.config.settings import Settings, resetSettings
from ml_platform.repositories.mock import (
    MockExperimentStore,
    MockFeatureStore,
    MockMLBackend,
)
from ml_platform.services.evaluation_service import EvaluationService
from ml_platform.services.experiment_service import ExperimentService
from ml_platform.services.feature_service import FeatureService
from ml_platform.services.prediction_service import PredictionService
from ml_platform.services.registry import ServiceRegistry
from ml_platform.services.training_service import TrainingService


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
    resetSettings()
    return Settings(backendType="mock")


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
        trainingService=TrainingService(
            mockBackend, mockExperimentStore
        ),
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