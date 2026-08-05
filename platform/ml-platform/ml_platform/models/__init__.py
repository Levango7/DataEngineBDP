"""ML Platform Pydantic 数据模型.

对齐设计文档 L4.5.6 接口契约：
    POST /api/v1/experiments         { name, workspaceId, projectId }
    POST /api/v1/training/jobs       { algorithm, dataset, params, ... }
    POST /api/v1/models/{id}/predict { data }
    POST /api/v1/feature-groups      { name, entityKey, features }
"""
from ml_platform.models.base import (
    AlgorithmType,
    BackendType,
    ExperimentStatus,
    ExperimentStoreType,
    FeatureStoreType,
    ModelStatus,
    TimestampMixin,
    TrainingStatus,
    utcNow,
)
from ml_platform.models.experiment import (
    ExperimentConfig,
    ExperimentInfo,
    ModelInfo,
    ModelMetrics,
)
from ml_platform.models.feature import (
    FeatureGroup,
    FeatureGroupConfig,
    FeatureSchema,
)
from ml_platform.models.prediction import (
    EvalConfig,
    EvalResult,
    PredictionRequest,
    PredictionResult,
)
from ml_platform.models.training import (
    TrainingConfig,
    TrainingJob,
    TrainingResult,
)

__all__ = [
    # base
    "TimestampMixin",
    "utcNow",
    "AlgorithmType",
    "TrainingStatus",
    "ModelStatus",
    "ExperimentStatus",
    "BackendType",
    "FeatureStoreType",
    "ExperimentStoreType",
    # training
    "TrainingConfig",
    "TrainingJob",
    "TrainingResult",
    # prediction
    "PredictionRequest",
    "PredictionResult",
    "EvalConfig",
    "EvalResult",
    # feature
    "FeatureSchema",
    "FeatureGroupConfig",
    "FeatureGroup",
    # experiment
    "ExperimentConfig",
    "ExperimentInfo",
    "ModelInfo",
    "ModelMetrics",
]