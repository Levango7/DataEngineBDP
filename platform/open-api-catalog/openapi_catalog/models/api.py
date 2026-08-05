"""API 定义模型."""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field, model_validator

from openapi_catalog.models.base import (
    APIStatus,
    AuthType,
    CostStrategy,
    HttpMethod,
    ParamLocation,
    ParamType,
    SLALevel,
    TimestampMixin,
)


class APIParam(BaseModel):
    """API 参数定义."""

    name: str = Field(..., min_length=1, max_length=128, description="参数名")
    location: ParamLocation = Field(..., description="参数位置")
    type: ParamType = Field(..., description="参数类型")
    required: bool = Field(default=True, description="是否必填")
    description: str | None = Field(default=None, description="参数描述")
    default: Any | None = Field(default=None, description="默认值")
    example: Any | None = Field(default=None, description="示例值")
    enum: list[Any] | None = Field(default=None, description="枚举值")


class APIResponse(BaseModel):
    """API 响应定义."""

    statusCode: int = Field(..., ge=100, le=599, description="HTTP 状态码")
    description: str | None = Field(default=None, description="响应描述")
    schema: dict[str, Any] | None = Field(
        default=None, description="响应 Schema（OpenAPI 3.0）"
    )
    example: Any | None = Field(default=None, description="响应示例")


class APIUpstream(BaseModel):
    """API 后端上游配置."""

    type: str = Field(..., description="上游类型: trino/doris/llm/udf/http")
    url: str = Field(..., description="后端 URL")
    method: HttpMethod = Field(..., description="后端方法")
    timeout: int = Field(default=30000, ge=1, description="超时(ms)")
    retries: int = Field(default=0, ge=0, description="重试次数")


class APIDefinition(TimestampMixin):
    """API 定义（服务目录条目）.

    对应详细设计 §4 API 条目字段：
        apiId / 名称 / 描述 / 版本 / 提供方租户 / 消费者列表 /
        调用统计 / SLA 等级 / 计费策略 / 分类 / 标签 / 契约 URL
    """

    id: str = Field(default="", description="API 唯一标识")
    name: str = Field(..., min_length=1, max_length=128, description="API 名称")
    version: str = Field(
        ..., pattern=r"^\d+\.\d+\.\d+$", description="语义化版本号 v1.2.3"
    )
    description: str | None = Field(default=None, description="API 描述")
    category: str = Field(default="default", description="分类")
    tags: list[str] = Field(default_factory=list, description="标签列表")

    # 路由
    method: HttpMethod = Field(..., description="HTTP 方法")
    path: str = Field(
        ..., pattern=r"^/", description="API 路径（必须以 / 开头）"
    )

    # 契约
    params: list[APIParam] = Field(default_factory=list, description="参数列表")
    responses: list[APIResponse] = Field(
        default_factory=lambda: [
            APIResponse(statusCode=200, description="成功")
        ],
        description="响应列表",
    )

    # 鉴权
    authType: AuthType = Field(
        default=AuthType.API_KEY, description="认证方式"
    )

    # 上游
    upstream: APIUpstream = Field(..., description="后端上游")

    # SLA & 计费
    sla: SLALevel = Field(default=SLALevel.SILVER, description="SLA 等级")
    costStrategy: CostStrategy = Field(
        default=CostStrategy.BY_CALL, description="计费策略"
    )
    costUnitPrice: float = Field(
        default=0.0, ge=0, description="单价（按次:元/次；按量:元/KB；月包:元/月）"
    )
    monthlyQuota: int | None = Field(
        default=None, ge=0, description="月包配额（仅 monthly_package）"
    )

    # 状态
    status: APIStatus = Field(
        default=APIStatus.DRAFT, description="发布状态"
    )

    # 租户
    providerTenantId: str = Field(..., description="提供方租户 ID")

    # 调用统计（运行时填充）
    callCount: int = Field(default=0, ge=0, description="累计调用次数")
    errorCount: int = Field(default=0, ge=0, description="累计错误次数")
    totalLatencyMs: float = Field(
        default=0.0, ge=0, description="累计延迟(ms)"
    )
    totalTrafficBytes: int = Field(
        default=0, ge=0, description="累计流量(bytes)"
    )

    @model_validator(mode="after")
    def _validate_monthly_quota(self) -> "APIDefinition":
        """月包策略必须指定 monthlyQuota."""
        if self.costStrategy == CostStrategy.MONTHLY_PACKAGE:
            if self.monthlyQuota is None:
                raise ValueError("月包计费策略必须指定 monthlyQuota")
        return self

    @property
    def avgLatencyMs(self) -> float:
        """平均延迟(ms)."""
        if self.callCount == 0:
            return 0.0
        return self.totalLatencyMs / self.callCount

    @property
    def errorRate(self) -> float:
        """错误率(0~1)."""
        if self.callCount == 0:
            return 0.0
        return self.errorCount / self.callCount

    @property
    def successRate(self) -> float:
        """成功率(0~1)."""
        return 1.0 - self.errorRate


class APIFilter(BaseModel):
    """API 列表过滤条件."""

    name: str | None = Field(default=None, description="名称模糊匹配")
    category: str | None = Field(default=None, description="分类过滤")
    tag: str | None = Field(default=None, description="标签过滤")
    status: APIStatus | None = Field(default=None, description="状态过滤")
    providerTenantId: str | None = Field(
        default=None, description="提供方租户过滤"
    )
    keyword: str | None = Field(default=None, description="全文搜索关键词")
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)


class APIUpdateRequest(BaseModel):
    """API 更新请求."""

    description: str | None = None
    category: str | None = None
    tags: list[str] | None = None
    params: list[APIParam] | None = None
    responses: list[APIResponse] | None = None
    sla: SLALevel | None = None
    costStrategy: CostStrategy | None = None
    costUnitPrice: float | None = Field(default=None, ge=0)
    monthlyQuota: int | None = Field(default=None, ge=0)
    upstream: APIUpstream | None = None