"""数据概览模型（业务线仪表盘）.

聚合本业务线下所有项目的资源用量、作业成功率、成本趋势、TopN 项目排行。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from business_portal.models.base import utc_now


class Kpi(BaseModel):
    """KPI 指标卡片."""

    key: str = Field(..., description="指标 key，如 projectCount")
    label: str = Field(..., description="显示名，如 数据项目")
    value: float | int = Field(..., description="指标值")
    unit: str = Field(default="", description="单位，如 TB / 元 / %")
    trend: float = Field(default=0.0, description="环比变化（百分比）")
    description: str | None = None


class TrendPoint(BaseModel):
    """趋势图数据点."""

    timestamp: datetime
    value: float


class Trend(BaseModel):
    """趋势图."""

    key: str = Field(..., description="趋势 key，如 cpuTrend")
    label: str = Field(..., description="显示名")
    unit: str = Field(default="")
    points: list[TrendPoint] = Field(default_factory=list)
    # 简化版：直接给百分比高度的柱状图（前端 mini 渲染）
    bars: list[float] = Field(default_factory=list, description="0~100 的百分比高度列表")


class RealtimeMonitor(BaseModel):
    """实时监控项."""

    key: str
    label: str
    status: str = Field(default="ok", description="ok/warn/critical")
    value: float = 0.0
    unit: str = ""
    threshold: float | None = None


class TopProject(BaseModel):
    """TopN 项目排行."""

    projectId: str
    projectName: str
    cost: float = 0.0
    usageRatio: float = 0.0
    jobCount: int = 0


class Dashboard(BaseModel):
    """业务线数据概览（仪表盘）."""

    blId: str
    kpis: list[Kpi] = Field(default_factory=list)
    trends: list[Trend] = Field(default_factory=list)
    realtime: list[RealtimeMonitor] = Field(default_factory=list)
    topProjects: list[TopProject] = Field(default_factory=list)
    updatedAt: datetime = Field(default_factory=utc_now)
    extra: dict[str, Any] = Field(default_factory=dict)
