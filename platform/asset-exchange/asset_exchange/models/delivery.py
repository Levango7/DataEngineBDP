"""数据交付模型.

对齐设计文档 §3 流通模式：
- API 交付：消费方通过 API 拉取（订阅模式）
- 文件交付：生成数据文件供下载（交易模式）
- 数据库直连交付：授权访问源库（高价值数据集）
"""
from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field

from asset_exchange.models.base import (
    DeliveryMethod,
    DeliveryStatus,
    TimestampMixin,
    utc_now,
)


class DeliveryConfig(BaseModel):
    """交付配置.

    根据交付方式不同，配置字段不同：
    - API:             { endpoint, apiKey, headers }
    - FILE:            { format, filePath, expireAt }
    - DATABASE_DIRECT: { jdbcUrl, username, password, tableName, expireAt }
    """

    method: DeliveryMethod = Field(..., description="交付方式")
    params: dict[str, Any] = Field(
        default_factory=dict, description="交付参数"
    )


class Delivery(TimestampMixin):
    """交付记录."""

    id: str = Field(default="", description="交付 ID")
    subscriptionId: str = Field(..., description="订阅 ID")
    method: DeliveryMethod = Field(..., description="交付方式")
    config: dict[str, Any] = Field(
        default_factory=dict, description="交付配置参数"
    )
    status: DeliveryStatus = Field(
        default=DeliveryStatus.PENDING, description="交付状态"
    )

    # 交付产物
    artifactUrl: Optional[str] = Field(
        default=None, description="交付产物 URL（文件/API 端点）"
    )
    artifactMeta: dict[str, Any] = Field(
        default_factory=dict, description="交付产物元数据"
    )

    # 量统计
    dataRows: int = Field(default=0, ge=0, description="交付数据行数")
    dataBytes: int = Field(default=0, ge=0, description="交付数据字节数")

    # 时间
    startedAt: Optional[datetime] = Field(default=None, description="开始交付时间")
    finishedAt: Optional[datetime] = Field(default=None, description="完成交付时间")
    errorMessage: Optional[str] = Field(default=None, description="失败原因")


class DeliveryRequest(BaseModel):
    """交付请求."""

    method: DeliveryMethod = Field(..., description="交付方式")
    config: dict[str, Any] = Field(
        default_factory=dict, description="交付配置参数"
    )


class DeliveryStatusResponse(BaseModel):
    """交付状态响应."""

    deliveryId: str
    subscriptionId: str
    method: DeliveryMethod
    status: DeliveryStatus
    dataRows: int = Field(default=0, ge=0)
    dataBytes: int = Field(default=0, ge=0)
    artifactUrl: Optional[str] = None
    errorMessage: Optional[str] = None
    startedAt: Optional[datetime] = None
    finishedAt: Optional[datetime] = None