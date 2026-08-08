"""数据资产模型.

资产卡片字段对齐设计文档 §2：
    资产ID/名称/描述、资产类型、提供方、质量评分、安全分级、
    更新频率、样例数据、价格、订阅者数。
"""

from __future__ import annotations

from typing import Any, Optional

from pydantic import AliasChoices, BaseModel, Field

from asset_exchange.models.base import (
    AssetStatus,
    AssetType,
    BillingMode,
    SecurityLevel,
    TimestampMixin,
)


class AssetSchema(BaseModel):
    """资产 schema（字段定义）.

    用于数据集类资产展示字段列表。
    """

    fields: list[dict[str, Any]] = Field(
        default_factory=list,
        description="字段定义列表，每项 {name, type, description}",
    )


class AssetPricing(BaseModel):
    """资产定价.

    - mode:    计费方式
    - price:   单价（BY_CALL: 元/次；BY_DATA: 元/千行；BY_TIME: 元/月；ONE_TIME: 元）
    - unit:    计费单位描述
    """

    mode: BillingMode = Field(default=BillingMode.BY_CALL, description="计费方式")
    price: float = Field(default=0.0, ge=0, description="单价")
    unit: str = Field(default="次", description="计费单位")


class AssetFilter(BaseModel):
    """资产过滤条件."""

    name: Optional[str] = Field(default=None, description="名称模糊匹配")
    type: Optional[AssetType] = Field(default=None, description="按类型过滤")
    status: Optional[AssetStatus] = Field(default=None, description="按状态过滤")
    securityLevel: Optional[SecurityLevel] = Field(default=None, description="按安全分级过滤")
    # 租户标识字段统一为 tenantId（MODEL-2）；
    # 通过 validation_alias 同时接受旧字段名 owner 作为输入，保持向后兼容。
    tenantId: Optional[str] = Field(
        default=None,
        validation_alias=AliasChoices("tenantId", "owner"),
        description="按租户 ID 过滤",
    )
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)


class Asset(TimestampMixin):
    """数据资产.

    对齐设计文档资产卡片字段。
    """

    id: str = Field(default="", description="资产 ID")
    name: str = Field(..., min_length=1, max_length=128, description="资产名称")
    type: AssetType = Field(..., description="资产类型")
    # 租户标识字段统一为 tenantId（MODEL-2）；
    # 通过 validation_alias 同时接受旧字段名 owner 作为输入，保持向后兼容。
    # 序列化默认输出字段名 tenantId，达成跨组件命名一致性。
    tenantId: str = Field(
        ...,
        min_length=1,
        validation_alias=AliasChoices("tenantId", "owner"),
        description="租户 ID（资产提供方）",
    )
    description: Optional[str] = Field(default=None, description="资产描述")
    status: AssetStatus = Field(default=AssetStatus.DRAFT, description="资产状态")

    # 质量与安全
    qualityScore: float = Field(default=0.0, ge=0, le=100, description="质量评分 0-100")
    securityLevel: SecurityLevel = Field(default=SecurityLevel.INTERNAL, description="安全分级")

    # 元数据
    schema: AssetSchema = Field(default_factory=AssetSchema, description="字段定义")
    sample: Optional[list[dict[str, Any]]] = Field(default=None, description="样例数据（前 N 行）")
    updateFrequency: str = Field(default="static", description="更新频率: realtime/hourly/daily/weekly/monthly/static")
    tags: dict[str, str] = Field(default_factory=dict, description="标签")

    # 定价
    pricing: AssetPricing = Field(default_factory=AssetPricing, description="定价")

    # 来源引用（关联 L3.5 资产目录 / L5.5 API 目录 / L4.5.2 ML / L4.5.5 LLMOps）
    sourceRef: Optional[str] = Field(default=None, description="源资产引用（如 catalog:table:uuid）")

    # 运行时统计
    subscriberCount: int = Field(default=0, ge=0, description="当前订阅者数")


class AssetUsage(BaseModel):
    """资产使用统计."""

    assetId: str
    subscriberCount: int = Field(default=0, ge=0, description="订阅者数")
    totalCalls: int = Field(default=0, ge=0, description="累计调用次数")
    totalDataRows: int = Field(default=0, ge=0, description="累计交付数据行数")
    totalRevenue: float = Field(default=0.0, ge=0, description="累计收益（元）")
    activeSubscriptions: int = Field(default=0, ge=0, description="生效中订阅数")
