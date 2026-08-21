"""服务注册表 - 根据配置构建 Mock / Sklearn / Spark 实现并注入服务层.

设计模式：依赖注入 + 工厂。
配置开关：
    ML_BACKEND_TYPE=mock / sklearn / spark
    ML_FEATURE_STORE_TYPE=mock / redis
    ML_EXPERIMENT_STORE_TYPE=mock / mlflow
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from ml_platform.config.settings import Settings, getSettings
from ml_platform.interfaces.backend import MLBackend
from ml_platform.interfaces.experiment_store import ExperimentStore
from ml_platform.interfaces.feature_store import FeatureStore
from ml_platform.services.evaluation_service import EvaluationService
from ml_platform.services.experiment_service import ExperimentService
from ml_platform.services.feature_service import FeatureService
from ml_platform.services.prediction_service import PredictionService
from ml_platform.services.training_service import TrainingService


@dataclass
class ServiceRegistry:
    """服务注册表，聚合所有仓储与服务."""

    settings: Settings
    backend: MLBackend
    featureStore: FeatureStore
    experimentStore: ExperimentStore
    trainingService: TrainingService
    predictionService: PredictionService
    evaluationService: EvaluationService
    featureService: FeatureService
    experimentService: ExperimentService


def buildServices(
    settings: Optional[Settings] = None,
) -> ServiceRegistry:
    """根据配置构建服务注册表.

    Args:
        settings: 配置，不传则使用全局单例。

    Returns:
        ServiceRegistry 实例。
    """
    if settings is None:
        settings = getSettings()

    backend = _buildBackend(settings)
    featureStore = _buildFeatureStore(settings)
    experimentStore = _buildExperimentStore(settings)

    trainingService = TrainingService(backend, experimentStore)
    predictionService = PredictionService(backend)
    evaluationService = EvaluationService(backend)
    featureService = FeatureService(featureStore)
    experimentService = ExperimentService(experimentStore)

    return ServiceRegistry(
        settings=settings,
        backend=backend,
        featureStore=featureStore,
        experimentStore=experimentStore,
        trainingService=trainingService,
        predictionService=predictionService,
        evaluationService=evaluationService,
        featureService=featureService,
        experimentService=experimentService,
    )


def _buildBackend(settings: Settings) -> MLBackend:
    if settings.isMlflowBackend:
        from ml_platform.repositories.mlflow import MLflowMLBackend

        return MLflowMLBackend(
            trackingUri=settings.mlflowUri,
            registryUri=settings.effectiveRegistryUri,
        )
    if settings.isMockBackend:
        from ml_platform.repositories.mock import MockMLBackend

        return MockMLBackend()
    if settings.isSklearnBackend:
        from ml_platform.repositories.sklearn import (
            SklearnMLBackend,
        )

        return SklearnMLBackend()
    if settings.isSparkBackend:
        # Spark MLlib 后端骨架，暂未实现，回退到 Mock
        from ml_platform.repositories.mock import MockMLBackend

        return MockMLBackend()
    # 默认 Mock
    from ml_platform.repositories.mock import MockMLBackend

    return MockMLBackend()


def _buildFeatureStore(settings: Settings) -> FeatureStore:
    if settings.isMockFeatureStore:
        from ml_platform.repositories.mock import (
            MockFeatureStore,
        )

        return MockFeatureStore()
    # Redis 后端骨架，暂未实现，回退到 Mock
    from ml_platform.repositories.mock import MockFeatureStore

    return MockFeatureStore()


def _buildExperimentStore(
    settings: Settings,
) -> ExperimentStore:
    if settings.isMlflowExperimentStore:
        from ml_platform.repositories.mlflow import (
            MLflowExperimentStore,
        )

        return MLflowExperimentStore(trackingUri=settings.mlflowUri)
    if settings.isMockExperimentStore:
        from ml_platform.repositories.mock import (
            MockExperimentStore,
        )

        return MockExperimentStore()
    # 兜底 Mock
    from ml_platform.repositories.mock import (
        MockExperimentStore,
    )

    return MockExperimentStore()
