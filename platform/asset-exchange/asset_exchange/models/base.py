"""基础模型与枚举.

对齐设计文档 L5.6 数据资产流通：
- 资产类型：TABLE / API / MODEL / DASHBOARD / STREAM
- 资产状态：DRAFT / LISTED / OFFLINE / REJECTED
- 订阅状态：PENDING / APPROVED / ACTIVE / EXPIRED / REJECTED
- 交付方式：API / FILE / DATABASE_DIRECT
- 交付状态：PENDING / RUNNING / SUCCEEDED / FAILED
- 计费方式：BY_CALL / BY_DATA / BY_TIME / ONE_TIME
- 安全分级：PUBLIC / INTERNAL / SENSITIVE
"""
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


class AssetType(str, Enum):
    """资产类型.

    - TABLE:     数据集/表（来自 L3.5 资产目录）
    - API:       数据服务/API（来自 L5.5 开放API服务目录）
    - MODEL:     ML 模型（来自 L4.5.2 机器学习）
    - DASHBOARD: 仪表盘/报表
    - STREAM:    实时数据流
    """

    TABLE = "table"
    API = "api"
    MODEL = "model"
    DASHBOARD = "dashboard"
    STREAM = "stream"


class AssetStatus(str, Enum):
    """资产状态机.

    状态转换：
        DRAFT -> PENDING_AUDIT (提交审核)
        PENDING_AUDIT -> LISTED (审核通过)
        PENDING_AUDIT -> REJECTED (审核驳回)
        DRAFT -> LISTED (直接上架，兼容旧流程)
        DRAFT -> REJECTED (审核驳回)
        LISTED -> OFFLINE (下架)
        OFFLINE -> LISTED (重新上架)
    """

    DRAFT = "draft"             # 草稿/已登记
    PENDING_AUDIT = "pending_audit"  # 待审核
    LISTED = "listed"           # 已上架/可流通
    OFFLINE = "offline"         # 已下架
    REJECTED = "rejected"       # 审核驳回


class SecurityLevel(str, Enum):
    """安全分级（对接 X2 安全合规）.

    - PUBLIC:   公开，可直接流通
    - INTERNAL: 内部，需平台审批后流通
    - SENSITIVE: 敏感，必须脱敏后流通
    """

    PUBLIC = "public"
    INTERNAL = "internal"
    SENSITIVE = "sensitive"


class SubscriptionStatus(str, Enum):
    """订阅状态机.

    状态转换：
        PENDING -> APPROVED (审批通过)
        PENDING -> REJECTED (审批驳回)
        APPROVED -> ACTIVE (开始生效)
        ACTIVE -> EXPIRED (到期)
    """

    PENDING = "pending"     # 待审批
    APPROVED = "approved"   # 已批准
    ACTIVE = "active"       # 生效中
    EXPIRED = "expired"     # 已到期
    REJECTED = "rejected"   # 已驳回


class DeliveryMethod(str, Enum):
    """数据交付方式.

    - API:             API 交付（消费方通过 API 拉取）
    - FILE:            文件交付（生成数据文件供下载）
    - DATABASE_DIRECT: 数据库直连交付（授权访问源库）
    """

    API = "api"
    FILE = "file"
    DATABASE_DIRECT = "database_direct"


class DeliveryStatus(str, Enum):
    """交付状态机.

    状态转换：
        PENDING -> RUNNING -> SUCCEEDED
        PENDING/RUNNING -> FAILED
    """

    PENDING = "pending"     # 待交付
    RUNNING = "running"     # 交付中
    SUCCEEDED = "succeeded" # 交付成功
    FAILED = "failed"       # 交付失败


class BillingMode(str, Enum):
    """计费方式.

    - BY_CALL:       按调用量计费（按次）
    - BY_DATA:       按数据量计费（按量，行数/字节数）
    - BY_TIME:       按时间计费（月/年）
    - SUBSCRIPTION:  订阅计费（周期订阅，按订阅期固定费用）
    - ONE_TIME:      一次性买断
    """

    BY_CALL = "by_call"
    BY_DATA = "by_data"
    BY_TIME = "by_time"
    SUBSCRIPTION = "subscription"
    ONE_TIME = "one_time"


class AuditAction(str, Enum):
    """审计动作类型（全过程留痕）.

    - REGISTER:  资产登记
    - AUDIT:     上架审核
    - PUBLISH:   上架
    - SUBSCRIBE: 订阅
    - DOWNLOAD:  下载
    - INVOKE:    API 调用
    - DELIVER:   交付
    - SETTLE:    结算
    - ALLOCATE:  分账
    - OFFLINE:   下架
    """

    REGISTER = "register"
    AUDIT = "audit"
    PUBLISH = "publish"
    SUBSCRIBE = "subscribe"
    DOWNLOAD = "download"
    INVOKE = "invoke"
    DELIVER = "deliver"
    SETTLE = "settle"
    ALLOCATE = "allocate"
    OFFLINE = "offline"


class AuditResult(str, Enum):
    """审计结果."""

    SUCCESS = "success"
    FAILURE = "failure"
    PENDING = "pending"


class SettlementStatus(str, Enum):
    """结算状态机.

    状态转换：
        PENDING -> SETTLED (结算完成)
        PENDING -> FAILED (结算失败)
    """

    PENDING = "pending"
    SETTLED = "settled"
    FAILED = "failed"


class AllocationStatus(str, Enum):
    """分账状态机.

    状态转换：
        PENDING -> ALLOCATED (分账完成)
        PENDING -> FAILED (分账失败)
    """

    PENDING = "pending"
    ALLOCATED = "allocated"
    FAILED = "failed"


class AssetAuditResult(str, Enum):
    """资产审核结果.

    - APPROVED: 审核通过（合规/质量/分级检查通过）
    - REJECTED: 审核驳回
    """

    APPROVED = "approved"
    REJECTED = "rejected"


class ApprovalAction(str, Enum):
    """审批动作."""

    APPROVE = "approve"
    REJECT = "reject"