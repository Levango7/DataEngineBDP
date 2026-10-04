"""实验管理数据模型."""

from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field

from ml_platform.models.base import (
    ExperimentStatus,
    TimestampMixin,
    utcNow,
)


class ExperimentConfig(BaseModel):
    """实验配置.

    Attributes:
        name:        实验名（工作空间内唯一）
        workspaceId: 工作空间 ID
        projectId:   项目 ID
        description: 描述
        tags:        标签
    """

    name: str = Field(..., description="实验名")
    workspaceId: Optional[str] = Field(default=None, description="工作空间 ID")
    projectId: Optional[str] = Field(default=None, description="项目 ID")
    description: Optional[str] = Field(default=None, description="描述")
    tags: dict[str, str] = Field(default_factory=dict, description="标签")
    # 归属租户 ID（路由层从 JWT 注入，不暴露给用户）
    tenantId: Optional[str] = Field(default=None, description="归属租户 ID（由路由层注入）")


class ExperimentInfo(TimestampMixin):
    """实验信息.

    Attributes:
        id:       实验 ID
        name:     实验名
        status:   状态
        config:   完整配置
        params:   累计参数
        metrics:  累计指标
        runCount: 运行次数
    """

    id: str = Field(..., description="实验 ID")
    name: str = Field(..., description="实验名")
    status: ExperimentStatus = Field(default=ExperimentStatus.ACTIVE, description="状态")
    config: ExperimentConfig = Field(..., description="实验配置")
    params: dict[str, Any] = Field(default_factory=dict, description="累计参数")
    metrics: dict[str, float] = Field(default_factory=dict, description="累计指标")
    runCount: int = Field(default=0, description="运行次数")
    # 归属租户 ID（租户隔离）
    tenantId: Optional[str] = Field(default=None, description="归属租户 ID")


class ModelInfo(TimestampMixin):
    """模型信息.

    Attributes:
        id:          模型 ID
        name:        模型名
        algorithm:   算法类型
        experimentId:所属实验 ID
        version:     版本号
        status:      状态
        artifactUri: 模型产物 URI
        metrics:     评估指标
        params:      训练参数
        tags:        标签
        description: 描述
    """

    id: str = Field(..., description="模型 ID")
    name: str = Field(..., description="模型名")
    algorithm: str = Field(..., description="算法类型")
    experimentId: Optional[str] = Field(default=None, description="所属实验 ID")
    version: int = Field(default=1, ge=1, description="版本号")
    status: str = Field(default="ready", description="状态")
    artifactUri: Optional[str] = Field(default=None, description="模型产物 URI")
    metrics: dict[str, float] = Field(default_factory=dict, description="评估指标")
    params: dict[str, Any] = Field(default_factory=dict, description="训练参数")
    tags: dict[str, str] = Field(default_factory=dict, description="标签")
    description: Optional[str] = Field(default=None, description="描述")
    # 归属租户 ID（租户隔离）
    tenantId: Optional[str] = Field(default=None, description="归属租户 ID")


class ModelVersion(BaseModel):
    """模型版本条目（台账 #1c：`GET /api/v1/models/{modelId}/versions` 的响应体）.

    字段对齐前端契约 ``frontend/src/api/dev-ml.ts`` 的 ``ModelVersion``：
    ``version`` 按契约是字符串（后端内部版本号为 int，此处显式转 str，
    避免 TS 侧声明 string 而收到 number）。

    Attributes:
        modelName:    模型名（同名即同一模型的不同版本）
        version:      版本号
        status:       该版本状态
        metrics:      该版本评估指标
        trainJobId:   来源训练作业 ID（当前 ModelInfo 未回指作业，缺失时为 None，不编造）
        modelPath:    模型产物路径（artifactUri）
        registeredAt: 注册时间（ISO-8601）
    """

    modelName: str = Field(..., description="模型名")
    version: str = Field(..., description="版本号")
    status: str = Field(..., description="状态")
    metrics: dict[str, float] = Field(default_factory=dict, description="评估指标")
    trainJobId: Optional[str] = Field(default=None, description="来源训练作业 ID")
    modelPath: Optional[str] = Field(default=None, description="模型产物路径")
    registeredAt: Optional[str] = Field(default=None, description="注册时间")

    @classmethod
    def fromModelInfo(cls, m: ModelInfo) -> "ModelVersion":
        """由 ModelInfo 投影出版本条目.

        Args:
            m: 模型信息。

        Returns:
            版本条目。
        """
        created = getattr(m, "createdAt", None)
        return cls(
            modelName=m.name,
            version=str(m.version),
            status=str(m.status),
            metrics=m.metrics or {},
            trainJobId=(m.tags or {}).get("trainJobId"),
            modelPath=m.artifactUri,
            registeredAt=created.isoformat() if created else None,
        )


class ModelMetrics(BaseModel):
    """模型指标快照.

    Attributes:
        modelId: 模型 ID
        metrics: 指标名 -> 值
        capturedAt: 采集时间
    """

    modelId: str = Field(..., description="模型 ID")
    metrics: dict[str, float] = Field(default_factory=dict, description="指标值")
    capturedAt: str = Field(
        default_factory=lambda: utcNow().isoformat(),
        description="采集时间",
    )
