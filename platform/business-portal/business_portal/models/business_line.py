"""业务线数据模型.

业务线为顶层组织维度（如"风控线""增长线"），由 L5.2 运营后台创建并分配预算额度。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field, model_validator

from business_portal.models.base import (
    BusinessLineStatus,
    TimestampMixin,
    utc_now,
)


class Budget(BaseModel):
    """业务线预算（部门预算软约束）.

    - total:    预算总额（元，内部结算口径）
    - used:     已用额度（元）
    - cycle:    预算周期（monthly/quarterly/yearly）
    - softLimit: 是否软限制（true=超限告警不阻断，false=超限阻断）
    """

    total: float = Field(default=0.0, ge=0.0, description="预算总额")
    used: float = Field(default=0.0, ge=0.0, description="已用额度")
    cycle: str = Field(default="monthly", description="预算周期")
    softLimit: bool = Field(default=True, description="是否软限制")

    @property
    def remaining(self) -> float:
        """剩余预算."""
        return max(0.0, self.total - self.used)

    @property
    def usageRatio(self) -> float:
        """使用率（0~1）."""
        if self.total <= 0:
            return 0.0
        return min(1.0, self.used / self.total)

    @property
    def isExceeded(self) -> bool:
        """是否已超预算."""
        return self.used > self.total


class BusinessLineConfig(BaseModel):
    """业务线配置.

    - dataIsolation: 数据隔离级别（strict/relaxed）
      strict:  跨项目默认完全隔离
      relaxed: 同业务线内可申请共享
    - permissionScope: 权限范围（bl/team/project）
    - features: 启用的功能集（dashboard/workbench/catalog/bi/share/cost）
    - tags: 自由标签
    """

    dataIsolation: str = Field(default="strict", description="数据隔离级别: strict/relaxed")
    permissionScope: str = Field(default="bl", description="权限范围: bl/team/project")
    features: dict[str, bool] = Field(
        default_factory=lambda: {
            "dashboard": True,
            "workbench": True,
            "catalog": True,
            "bi": True,
            "share": True,
            "cost": True,
        }
    )
    tags: dict[str, str] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _validate_isolation(self) -> "BusinessLineConfig":
        if self.dataIsolation not in {"strict", "relaxed"}:
            raise ValueError(f"dataIsolation 必须为 strict/relaxed，得到 {self.dataIsolation}")
        if self.permissionScope not in {"bl", "team", "project"}:
            raise ValueError(f"permissionScope 必须为 bl/team/project，得到 {self.permissionScope}")
        return self


class BusinessLine(TimestampMixin):
    """业务线实体.

    - id:          业务线 ID（UUID）
    - name:        业务线名称（同租户下唯一）
    - tenantId:    所属租户 ID
    - description: 描述
    - status:      状态（active/suspended/archived）
    - budget:      预算
    - config:      业务线配置
    - ownerIds:    业务线管理员用户 ID 列表
    - teamIds:     下属团队 ID 列表
    - memberIds:   全部成员用户 ID 列表（沿树继承）
    """

    id: str = Field(..., min_length=1, description="业务线 ID")
    name: str = Field(..., min_length=1, max_length=128, description="业务线名称")
    tenantId: str = Field(..., min_length=1, description="所属租户 ID")
    description: str | None = Field(default=None, description="描述")
    status: BusinessLineStatus = Field(default=BusinessLineStatus.ACTIVE, description="状态")
    budget: Budget = Field(default_factory=Budget, description="预算")
    config: BusinessLineConfig = Field(default_factory=BusinessLineConfig, description="配置")
    ownerIds: list[str] = Field(default_factory=list, description="业务线管理员")
    teamIds: list[str] = Field(default_factory=list, description="下属团队")
    memberIds: list[str] = Field(default_factory=list, description="全部成员")


class BusinessLineFilter(BaseModel):
    """业务线过滤条件."""

    tenantId: str | None = None
    status: BusinessLineStatus | None = None
    name: str | None = Field(default=None, description="名称模糊匹配")
    memberId: str | None = Field(default=None, description="按成员过滤（仅返回该成员可见的业务线）")
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)


class BusinessLineUsage(BaseModel):
    """业务线用量概览（聚合视图）."""

    blId: str
    projectCount: int = 0
    teamCount: int = 0
    memberCount: int = 0
    jobCount: int = 0
    jobSuccessToday: int = 0
    jobFailToday: int = 0
    storageUsed: float = 0.0  # TB
    costToday: float = 0.0  # 元（内部结算口径）
    costMonth: float = 0.0  # 元
    updatedAt: datetime = Field(default_factory=utc_now)
    extra: dict[str, Any] = Field(default_factory=dict)
