"""LLMOps Pydantic 数据模型.

对齐设计文档 L4.5.5 接口契约：
    POST /api/llmops/v1/models      { name, type(base|ft), base?, params }
    POST /api/llmops/v1/fine-tunes  { baseModel, dataset, epochs, gpu, lr }
    POST /api/llmops/v1/endpoints   { model, replica, gpu }
"""
from llmops.models.base import (
    TimestampMixin,
    ResourceType,
    ModelType,
    ModelStatus,
    TrainingStatus,
    DeploymentStatus,
)
from llmops.models.model import (
    ModelInfo,
    ModelVersion,
    ModelFilter,
    ModelParams,
)
from llmops.models.training import (
    TrainingConfig,
    TrainingJob,
    TrainingJobStatus,
    EvalMetrics,
)
from llmops.models.deployment import (
    DeployConfig,
    Deployment,
    DeploymentStatusInfo,
)
from llmops.models.monitor import (
    ModelMetrics,
    LatencyStats,
    ThroughputStats,
    ErrorStats,
)

__all__ = [
    # base
    "TimestampMixin",
    "ResourceType",
    "ModelType",
    "ModelStatus",
    "TrainingStatus",
    "DeploymentStatus",
    # model
    "ModelInfo",
    "ModelVersion",
    "ModelFilter",
    "ModelParams",
    # training
    "TrainingConfig",
    "TrainingJob",
    "TrainingJobStatus",
    "EvalMetrics",
    # deployment
    "DeployConfig",
    "Deployment",
    "DeploymentStatusInfo",
    # monitor
    "ModelMetrics",
    "LatencyStats",
    "ThroughputStats",
    "ErrorStats",
]