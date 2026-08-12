"""基础模型与枚举."""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum

from pydantic import BaseModel, Field


def utcNow() -> datetime:
    """返回当前 UTC 时间（带 tzinfo），便于测试 mock."""
    return datetime.now(timezone.utc)


class TimestampMixin(BaseModel):
    """带创建/更新时间戳的混入."""

    createdAt: datetime = Field(default_factory=utcNow)
    updatedAt: datetime = Field(default_factory=utcNow)


class AlgorithmType(str, Enum):
    """支持的算法类型.

    - linear_regression:    线性回归
    - logistic_regression:  逻辑回归
    - random_forest:        随机森林
    - svm:                  支持向量机
    - kmeans:               K-Means 聚类
    """

    LINEAR_REGRESSION = "linear_regression"
    LOGISTIC_REGRESSION = "logistic_regression"
    RANDOM_FOREST = "random_forest"
    SVM = "svm"
    KMEANS = "kmeans"


class TrainingStatus(str, Enum):
    """训练任务状态机.

    状态转换：
        PENDING -> RUNNING -> (SUCCEEDED | FAILED)
        PENDING/RUNNING -> CANCELLED
    """

    PENDING = "pending"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    CANCELLED = "cancelled"


class ModelStatus(str, Enum):
    """模型状态."""

    DRAFT = "draft"  # 草稿
    READY = "ready"  # 可用
    TRAINING = "training"  # 训练中
    DEPLOYED = "deployed"  # 已部署
    ARCHIVED = "archived"  # 归档
    FAILED = "failed"  # 失败


class ExperimentStatus(str, Enum):
    """实验状态."""

    ACTIVE = "active"
    DELETED = "deleted"


class BackendType(str, Enum):
    """ML 后端类型."""

    MOCK = "mock"
    SKLEARN = "sklearn"
    SPARK = "spark"


class FeatureStoreType(str, Enum):
    """特征存储类型."""

    MOCK = "mock"
    REDIS = "redis"


class ExperimentStoreType(str, Enum):
    """实验存储类型."""

    MOCK = "mock"
    MLFLOW = "mlflow"
