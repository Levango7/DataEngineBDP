"""监控相关数据模型."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field

from llmops.models.base import utc_now


class ModelMetrics(BaseModel):
    """模型综合指标（对齐 L4.5.5 评估指标 + 运行时指标）."""

    deploymentId: str = Field(..., description="部署 ID")
    # 准确率（来自评估）
    accuracy: float = Field(default=0.0, ge=0.0, le=1.0)
    # 幻觉率
    hallucinationRate: float = Field(default=0.0, ge=0.0, le=1.0)
    # 对比基座提升（pt）
    upliftVsBase: float = Field(default=0.0)
    # QPS（每秒请求数）
    qps: float = Field(default=0.0, ge=0.0)
    # 错误率
    errorRate: float = Field(default=0.0, ge=0.0, le=1.0)
    # 采样时间窗口
    windowStart: datetime = Field(default_factory=utc_now)
    windowEnd: datetime = Field(default_factory=utc_now)
    # 采样样本数
    sampleCount: int = Field(default=0, ge=0)


class LatencyStats(BaseModel):
    """延迟统计."""

    deploymentId: str = Field(..., description="部署 ID")
    # 平均延迟（ms）
    avgMs: float = Field(default=0.0, ge=0.0)
    # P50 延迟
    p50Ms: float = Field(default=0.0, ge=0.0)
    # P95 延迟
    p95Ms: float = Field(default=0.0, ge=0.0)
    # P99 延迟
    p99Ms: float = Field(default=0.0, ge=0.0)
    # 最大延迟
    maxMs: float = Field(default=0.0, ge=0.0)
    # 最小延迟
    minMs: float = Field(default=0.0, ge=0.0)
    windowStart: datetime = Field(default_factory=utc_now)
    windowEnd: datetime = Field(default_factory=utc_now)
    sampleCount: int = Field(default=0, ge=0)


class ThroughputStats(BaseModel):
    """吞吐量统计."""

    deploymentId: str = Field(..., description="部署 ID")
    # 每秒请求数
    rps: float = Field(default=0.0, ge=0.0)
    # 每秒 token 数
    tps: float = Field(default=0.0, ge=0.0)
    # 总请求数
    totalRequests: int = Field(default=0, ge=0)
    # 总 token 数
    totalTokens: int = Field(default=0, ge=0)
    windowStart: datetime = Field(default_factory=utc_now)
    windowEnd: datetime = Field(default_factory=utc_now)


class ErrorStats(BaseModel):
    """错误统计."""

    deploymentId: str = Field(..., description="部署 ID")
    # 错误率
    errorRate: float = Field(default=0.0, ge=0.0, le=1.0)
    # 错误总数
    errorCount: int = Field(default=0, ge=0)
    # 总请求数
    totalRequests: int = Field(default=0, ge=0)
    # 按错误类型分组的计数
    errorBreakdown: dict[str, int] = Field(default_factory=dict)
    windowStart: datetime = Field(default_factory=utc_now)
    windowEnd: datetime = Field(default_factory=utc_now)
