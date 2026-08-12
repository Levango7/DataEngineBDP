"""部署相关数据模型."""

from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field

from llmops.models.base import DeploymentStatus, TimestampMixin


class DeployConfig(BaseModel):
    """部署配置.

    对齐设计：{ model, replica, gpu }
    """

    # 待部署模型 ID
    modelId: str = Field(..., description="待部署模型 ID")
    # 模型版本号（不指定则用当前生产版本）
    modelVersion: Optional[int] = Field(default=None, ge=1, description="模型版本号")
    # 副本数
    replica: int = Field(default=1, ge=1, le=32, description="副本数")
    # 每副本 GPU 卡数
    gpu: int = Field(default=1, ge=0, le=8, description="每副本 GPU 卡数")
    # CPU 核数
    cpu: int = Field(default=2, ge=1, le=64, description="CPU 核数")
    # 内存（GB）
    memory: int = Field(default=8, ge=1, le=256, description="内存 GB")
    # 推理后端（如 vllm, tgi, transformers）
    backend: str = Field(default="vllm", description="推理后端")
    # 部署名称（不指定则自动生成）
    name: Optional[str] = Field(default=None, description="部署名称")


class DeploymentStatusInfo(BaseModel):
    """部署状态快照."""

    status: DeploymentStatus = Field(..., description="部署状态")
    # 就绪副本数 / 目标副本数
    readyReplica: int = Field(default=0, ge=0)
    # 失败原因
    errorMessage: Optional[str] = Field(default=None)


class Deployment(TimestampMixin):
    """模型部署（推理端点）."""

    id: str = Field(..., description="部署 ID（UUID）")
    name: str = Field(..., description="部署名称")
    modelId: str = Field(..., description="部署的模型 ID")
    modelVersion: int = Field(..., ge=1, description="部署的模型版本")
    config: DeployConfig
    status: DeploymentStatusInfo = Field(default_factory=lambda: DeploymentStatusInfo(status=DeploymentStatus.CREATING))
    # 推理端点 URL（status=running 时有效）
    endpointUrl: Optional[str] = Field(default=None, description="推理端点 URL")
    # 部署到 L4.5.6 大模型网关后注册的路由名
    gatewayRoute: Optional[str] = Field(default=None, description="大模型网关路由名")
    startedAt: Optional[datetime] = Field(default=None)
    stoppedAt: Optional[datetime] = Field(default=None)
