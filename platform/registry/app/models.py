"""模型仓库注册部署服务数据模型.

定义模型注册、部署管理的完整数据结构：
- 模型注册请求/记录
- 部署请求/记录
- 健康检查结果
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


# ============================================================
# 部署状态枚举
# ============================================================
class DeploymentStatus(str, Enum):
    """部署状态."""

    PENDING = "pending"
    RUNNING = "running"
    STOPPED = "stopped"
    FAILED = "failed"
    UPDATING = "updating"


TERMINAL_STATUSES = frozenset({DeploymentStatus.STOPPED, DeploymentStatus.FAILED})


# ============================================================
# 模型注册
# ============================================================
class ModelRegisterRequest(BaseModel):
    """模型注册请求."""

    modelName: str = Field(min_length=1, max_length=128)
    version: str = Field(default="0.1.0")
    path: str = Field(description="模型文件路径")
    baseModel: str = Field(default="")
    framework: str = Field(default="peft")
    method: str = Field(default="lora")
    tenantId: str = Field(default="default")
    metadata: dict[str, Any] = Field(default_factory=dict)


class ModelRecord(BaseModel):
    """模型记录."""

    modelName: str
    version: str
    path: str
    baseModel: str = ""
    framework: str = ""
    method: str = ""
    tenantId: str = "default"
    metadata: dict[str, Any] = Field(default_factory=dict)
    createdAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    isActive: bool = True


class ModelListResponse(BaseModel):
    """模型列表响应."""

    total: int = Field(ge=0)
    models: list[ModelRecord] = Field(default_factory=list)


# ============================================================
# 部署
# ============================================================
class DeployRequest(BaseModel):
    """部署请求."""

    modelName: str
    version: str = Field(default="")
    runtime: str = Field(default="vllm", description="推理运行时：vllm/triton/simple")
    port: int = Field(default=8000, ge=1024, le=65535)
    replicas: int = Field(default=1, ge=1, le=10)
    gpuCount: int = Field(default=1, ge=1, le=8)
    tenantId: str = Field(default="default")
    env: dict[str, str] = Field(default_factory=dict)


class DeploymentRecord(BaseModel):
    """部署记录."""

    deploymentId: str
    modelName: str
    version: str
    runtime: str
    port: int
    replicas: int
    gpuCount: int
    tenantId: str
    status: DeploymentStatus = DeploymentStatus.PENDING
    endpoint: Optional[str] = None
    containerId: Optional[str] = None
    createdAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updatedAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    finishedAt: Optional[datetime] = None
    healthy: bool = False
    error: Optional[str] = None
    env: dict[str, str] = Field(default_factory=dict)

    def is_terminal(self) -> bool:
        return self.status in TERMINAL_STATUSES

    def touch(self) -> None:
        self.updatedAt = datetime.now(timezone.utc)


class DeploymentListResponse(BaseModel):
    """部署列表响应."""

    total: int = Field(ge=0)
    deployments: list[DeploymentRecord] = Field(default_factory=list)


# ============================================================
# 健康检查
# ============================================================
class HealthCheckResult(BaseModel):
    """健康检查结果."""

    deploymentId: str
    healthy: bool
    endpoint: Optional[str] = None
    latencyMs: float = 0.0
    error: Optional[str] = None
    checkedAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class HealthResponse(BaseModel):
    """服务健康检查响应."""

    status: str = "UP"
    service: str = "model-registry"
    version: str = "0.1.0"
    mockMode: bool = True
