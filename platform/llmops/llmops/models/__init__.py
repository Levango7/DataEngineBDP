"""LLMOps Pydantic 数据模型.

对齐设计文档 L4.5.5 接口契约：
    POST /api/llmops/v1/models      { name, type(base|ft), base?, params }
    POST /api/llmops/v1/fine-tunes  { baseModel, dataset, epochs, gpu, lr }
    POST /api/llmops/v1/endpoints   { model, replica, gpu }
"""

from llmops.models.base import (
    DeploymentStatus,
    ModelStatus,
    ModelType,
    ResourceType,
    TimestampMixin,
    TrainingStatus,
)
from llmops.models.deployment import (
    DeployConfig,
    Deployment,
    DeploymentStatusInfo,
)
from llmops.models.model import (
    ModelFilter,
    ModelInfo,
    ModelParams,
    ModelVersion,
)
from llmops.models.monitor import (
    ErrorStats,
    LatencyStats,
    ModelMetrics,
    ThroughputStats,
)
from llmops.models.training import (
    EvalMetrics,
    TrainingConfig,
    TrainingJob,
    TrainingJobStatus,
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
