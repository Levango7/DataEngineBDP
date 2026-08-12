"""基础模型与枚举."""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum

from pydantic import BaseModel, Field


def utc_now() -> datetime:
    """返回当前 UTC 时间（带 tzinfo），便于测试 mock."""
    return datetime.now(timezone.utc)


class TimestampMixin(BaseModel):
    """带创建/更新时间戳的混入."""

    createdAt: datetime = Field(default_factory=utc_now)
    updatedAt: datetime = Field(default_factory=utc_now)


class ResourceType(str, Enum):
    """资源类型（对齐资产目录 L3.5）."""

    MODEL = "model"
    DATASET = "dataset"
    ENDPOINT = "endpoint"


class ModelType(str, Enum):
    """模型类型.

    - base: 基座模型（如 qiong-7B）
    - ft:   微调模型（领域模型，如 风控-领域-1.3B）
    """

    BASE = "base"
    FT = "ft"


class ModelStatus(str, Enum):
    """模型状态."""

    DRAFT = "draft"  # 草稿/刚注册
    READY = "ready"  # 可用
    TRAINING = "training"  # 训练中
    DEPLOYED = "deployed"  # 已部署
    ARCHIVED = "archived"  # 归档
    FAILED = "failed"  # 失败


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


class DeploymentStatus(str, Enum):
    """部署状态机.

    状态转换：
        CREATING -> RUNNING -> (STOPPING -> STOPPED)
        CREATING/RUNNING -> FAILED
    """

    CREATING = "creating"
    RUNNING = "running"
    STOPPING = "stopping"
    STOPPED = "stopped"
    FAILED = "failed"
