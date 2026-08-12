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


class BusinessLineStatus(str, Enum):
    """业务线状态.

    - active:   活跃（可用）
    - suspended: 已暂停（预算耗尽或运营停用）
    - archived:  已归档
    """

    ACTIVE = "active"
    SUSPENDED = "suspended"
    ARCHIVED = "archived"


class ReportType(str, Enum):
    """BI 报表类型.

    - table:   明细表
    - chart:   图表（折线/柱状/饼图等）
    - dashboard: 综合看板（多组件）
    - pivot:   透视表
    """

    TABLE = "table"
    CHART = "chart"
    DASHBOARD = "dashboard"
    PIVOT = "pivot"


class ReportStatus(str, Enum):
    """报表状态."""

    DRAFT = "draft"
    PUBLISHED = "published"
    ARCHIVED = "archived"


class CatalogNodeType(str, Enum):
    """数据目录节点类型.

    - database: 数据库
    - schema:   Schema
    - table:    表
    - view:     视图
    - dataset:  数据集
    - model:    模型资产
    """

    DATABASE = "database"
    SCHEMA = "schema"
    TABLE = "table"
    VIEW = "view"
    DATASET = "dataset"
    MODEL = "model"
