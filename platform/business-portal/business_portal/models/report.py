"""BI 报表模型."""
from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from business_portal.models.base import ReportStatus, ReportType, TimestampMixin


class DataSourceRef(BaseModel):
    """报表数据源引用."""

    type: str = Field(..., description="数据源类型: catalog/external/sql")
    refId: str = Field(..., description="数据源 ID 或 SQL 文本")
    description: str | None = None


class ReportConfig(BaseModel):
    """报表配置."""

    type: ReportType = Field(default=ReportType.CHART, description="报表类型")
    chartType: str = Field(default="line", description="图表子类型: line/bar/pie/area")
    dimensions: list[str] = Field(default_factory=list, description="维度字段")
    measures: list[str] = Field(default_factory=list, description="度量字段")
    filters: dict[str, Any] = Field(default_factory=dict, description="过滤条件")
    refreshInterval: int = Field(
        default=0, ge=0, description="刷新间隔（秒），0=不自动刷新"
    )
    extra: dict[str, Any] = Field(default_factory=dict)


class Report(TimestampMixin):
    """BI 报表实体.

    - id:          报表 ID
    - blId:        所属业务线 ID（隔离边界）
    - name:        报表名称
    - description: 描述
    - status:      状态（draft/published/archived）
    - config:      报表配置
    - dataSource:  数据源引用
    - creatorId:   创建人
    - tags:        标签
    """

    id: str = Field(..., min_length=1)
    blId: str = Field(..., description="所属业务线 ID（隔离边界）")
    name: str = Field(..., min_length=1, max_length=256)
    description: str | None = None
    status: ReportStatus = Field(default=ReportStatus.DRAFT)
    config: ReportConfig = Field(default_factory=ReportConfig)
    dataSource: DataSourceRef | None = None
    creatorId: str = Field(default="", description="创建人")
    tags: dict[str, str] = Field(default_factory=dict)


class ReportFilter(BaseModel):
    """报表过滤条件."""

    blId: str = Field(..., description="业务线 ID（隔离边界）")
    status: ReportStatus | None = None
    type: ReportType | None = None
    name: str | None = Field(default=None, description="名称模糊匹配")
    creatorId: str | None = None
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)