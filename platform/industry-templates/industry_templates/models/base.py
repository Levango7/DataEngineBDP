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


class Industry(str, Enum):
    """行业枚举."""

    FINANCE = "finance"  # 金融
    RETAIL = "retail"  # 零售
    MANUFACTURING = "manufacturing"  # 制造
    GOVERNMENT = "government"  # 政务
    IOT = "iot"  # 物联网
    MEDICAL = "medical"  # 医疗
    TRANSPORTATION = "transportation"  # 交通
    EDUCATION = "education"  # 教育
    AGRICULTURE = "agriculture"  # 农牧


class TemplateStatus(str, Enum):
    """模板生命周期状态.

    状态转换：
        dev → review → catalog → (install → instantiate → running) → upgrade → running
        running → uninstall
    """

    DEV = "dev"  # 开发中
    REVIEW = "review"  # 审核中
    CATALOG = "catalog"  # 已上架
    DEPRECATED = "deprecated"  # 已下架


class DeploymentStatus(str, Enum):
    """模板部署状态机.

    状态转换：
        PENDING → INSTALLING → INSTANTIATING → RUNNING
        任意态 → FAILED
        RUNNING → STOPPED
    """

    PENDING = "pending"
    INSTALLING = "installing"
    INSTANTIATING = "instantiating"
    RUNNING = "running"
    FAILED = "failed"
    STOPPED = "stopped"


class ParameterType(str, Enum):
    """参数类型枚举."""

    STRING = "string"
    INTEGER = "integer"
    FLOAT = "float"
    BOOLEAN = "boolean"
    ENUM = "enum"
    DATASOURCE = "datasource"  # 数据源占位
