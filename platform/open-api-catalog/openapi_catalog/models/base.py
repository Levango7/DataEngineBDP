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


class HttpMethod(str, Enum):
    """HTTP 方法."""

    GET = "GET"
    POST = "POST"
    PUT = "PUT"
    DELETE = "DELETE"
    PATCH = "PATCH"


class APIStatus(str, Enum):
    """API 发布状态机.

    状态转换：
        DRAFT -> REVIEWING -> APPROVED -> PUBLISHED -> RUNNING -> (DEPRECATED -> ARCHIVED)
        REVIEWING -> REJECTED
        任意状态 -> OFFLINE
    """

    DRAFT = "draft"             # 草稿/刚注册
    REVIEWING = "reviewing"     # 安全审核中
    APPROVED = "approved"       # 审核通过待发布
    REJECTED = "rejected"       # 审核驳回
    PUBLISHED = "published"     # 已发布到网关
    RUNNING = "running"         # 运行中
    DEPRECATED = "deprecated"   # 已废弃宽限期
    ARCHIVED = "archived"       # 归档下线
    OFFLINE = "offline"         # 强制下线


class AuthType(str, Enum):
    """认证方式."""

    API_KEY = "api_key"         # API Key (AK/SK)
    JWT = "jwt"                 # JWT Bearer
    OAUTH2 = "oauth2"           # OAuth2 Client Credentials
    NONE = "none"               # 无认证（仅内部调用）


class SLALevel(str, Enum):
    """SLA 等级."""

    PLATINUM = "platinum"       # 铂金
    GOLD = "gold"               # 金
    SILVER = "silver"           # 银


class CostStrategy(str, Enum):
    """计费策略."""

    BY_CALL = "by_call"                 # 按次
    BY_BYTES = "by_bytes"               # 按量
    MONTHLY_PACKAGE = "monthly_package"  # 按月包


class SubscriptionStatus(str, Enum):
    """订阅状态机.

    状态转换：
        PENDING -> APPROVED -> ACTIVE -> (SUSPENDED -> ACTIVE)
        PENDING -> REJECTED
        ACTIVE -> REVOKED
    """

    PENDING = "pending"     # 待审批
    APPROVED = "approved"   # 已审批待激活
    ACTIVE = "active"       # 已激活
    SUSPENDED = "suspended"  # 已暂停
    REJECTED = "rejected"   # 已驳回
    REVOKED = "revoked"     # 已吊销


class ParamLocation(str, Enum):
    """参数位置."""

    PATH = "path"
    QUERY = "query"
    HEADER = "header"
    BODY = "body"


class ParamType(str, Enum):
    """参数类型."""

    STRING = "string"
    INTEGER = "integer"
    NUMBER = "number"
    BOOLEAN = "boolean"
    ARRAY = "array"
    OBJECT = "object"