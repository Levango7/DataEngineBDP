"""API 调用计量模型."""

from __future__ import annotations

from datetime import datetime

from openapi_catalog.models.base import CostStrategy, utc_now
from pydantic import BaseModel, Field


class CallMetric(BaseModel):
    """单次调用计量记录.

    对应详细设计 §6 计量数据模型：
        CallMetric {
            callId, apiId, version,
            consumerTenantId, providerTenantId,
            timestamp, latencyMs,
            requestBytes, responseBytes,
            statusCode,
            costStrategy, costAmount
        }
    """

    callId: str = Field(..., description="唯一调用 ID")
    apiId: str = Field(..., description="API ID")
    apiVersion: str = Field(..., description="API 版本")
    subscriptionId: str = Field(..., description="订阅 ID")
    consumerTenantId: str = Field(..., description="消费者租户 ID")
    providerTenantId: str = Field(..., description="提供方租户 ID")
    timestamp: datetime = Field(default_factory=utc_now, description="调用时间")
    latencyMs: float = Field(..., ge=0, description="端到端延迟(ms)")
    requestBytes: int = Field(default=0, ge=0, description="请求字节数")
    responseBytes: int = Field(default=0, ge=0, description="响应字节数")
    statusCode: int = Field(..., ge=100, le=599, description="HTTP 状态码")
    costStrategy: CostStrategy = Field(..., description="计费策略")
    costAmount: float = Field(default=0.0, ge=0, description="本次折算金额")
    errorMessage: str | None = Field(default=None, description="错误信息")


class APIMetrics(BaseModel):
    """API 聚合计量.

    对应详细设计 §4 调用统计：近 7/30 日调用量、成功率、P99 延迟。
    """

    apiId: str = Field(..., description="API ID")
    callCount: int = Field(default=0, ge=0, description="调用次数")
    successCount: int = Field(default=0, ge=0, description="成功次数")
    errorCount: int = Field(default=0, ge=0, description="错误次数")
    errorRate: float = Field(default=0.0, ge=0, le=1, description="错误率")
    successRate: float = Field(default=1.0, ge=0, le=1, description="成功率")
    avgLatencyMs: float = Field(default=0.0, ge=0, description="平均延迟(ms)")
    p99LatencyMs: float = Field(default=0.0, ge=0, description="P99 延迟(ms)")
    totalTrafficBytes: int = Field(default=0, ge=0, description="总流量(bytes)")
    totalCost: float = Field(default=0.0, ge=0, description="总费用")
    lastCalledAt: datetime | None = Field(default=None, description="最后调用时间")

    # 按消费者聚合
    byConsumer: list["ConsumerMetrics"] = Field(default_factory=list, description="按消费者聚合")

    # 时间序列（用于图表）
    timeseries: list["MetricPoint"] = Field(default_factory=list, description="时间序列")


class ConsumerMetrics(BaseModel):
    """按消费者聚合的计量."""

    consumerTenantId: str = Field(..., description="消费者租户 ID")
    subscriptionId: str = Field(..., description="订阅 ID")
    callCount: int = Field(default=0, ge=0)
    errorCount: int = Field(default=0, ge=0)
    avgLatencyMs: float = Field(default=0.0, ge=0)
    totalCost: float = Field(default=0.0, ge=0)


class MetricPoint(BaseModel):
    """时间序列计量点."""

    timestamp: datetime = Field(..., description="时间戳")
    callCount: int = Field(default=0, ge=0)
    errorCount: int = Field(default=0, ge=0)
    avgLatencyMs: float = Field(default=0.0, ge=0)


class MetricsQuery(BaseModel):
    """计量查询参数."""

    range: str = Field(default="7d", description="时间范围: 1h/24h/7d/30d")
    consumerTenantId: str | None = Field(default=None, description="按消费者过滤")
    granularity: str = Field(default="1h", description="聚合粒度: 1m/1h/1d")


class CallResult(BaseModel):
    """API 调用结果."""

    callId: str = Field(..., description="调用 ID")
    statusCode: int = Field(..., description="HTTP 状态码")
    latencyMs: float = Field(..., ge=0, description="延迟(ms)")
    result: dict | None = Field(default=None, description="响应结果")
    error: str | None = Field(default=None, description="错误信息")
    costAmount: float = Field(default=0.0, ge=0, description="本次费用")


# 解决前向引用
APIMetrics.model_rebuild()
