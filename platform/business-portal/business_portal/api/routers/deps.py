"""通用依赖与错误处理."""

from __future__ import annotations

import os

from fastapi import Depends, Request

from business_portal.api.jwt_auth import AuthContext, getAuthContext
from business_portal.repositories import (
    BusinessLineAlreadyExistsError,
    BusinessLineNotFoundError,
    CatalogNodeNotFoundError,
    PermissionDeniedError,
    PortalError,
    ReportNotFoundError,
    ValidationError,
)
from business_portal.services.registry import ServiceRegistry


def get_registry(request: Request) -> ServiceRegistry:
    """从 app.state 获取服务注册表."""
    return request.app.state.registry


def _is_anon_mode() -> bool:
    """AUTH_MODE=none 时本地/测试环境从请求头读取身份."""
    return os.environ.get("AUTH_MODE", "none").strip().lower() == "none"


def get_current_user(
    request: Request,
    ctx: AuthContext = Depends(getAuthContext),
) -> str | None:
    """当前用户 ID.

    AUTH_MODE=none（本地/测试）：从 X-User-Id 头读取（None 表示匿名/管理员，跳过权限检查）
    AUTH_MODE=jwt（生产）：从 JWT sub 声明读取
    """
    if _is_anon_mode():
        return request.headers.get("X-User-Id")
    return ctx.userId


def get_current_tenant(
    request: Request,
    ctx: AuthContext = Depends(getAuthContext),
) -> str | None:
    """当前租户 ID.

    AUTH_MODE=none（本地/测试）：从 X-Tenant-Id 头读取（None 表示未认证）
    AUTH_MODE=jwt（生产）：从 JWT tenantId 声明读取
    """
    if _is_anon_mode():
        return request.headers.get("X-Tenant-Id")
    return ctx.tenantId or None


# HTTP 状态码映射
_ERROR_STATUS: dict[type[PortalError], int] = {
    BusinessLineNotFoundError: 404,
    ReportNotFoundError: 404,
    CatalogNodeNotFoundError: 404,
    BusinessLineAlreadyExistsError: 409,
    PermissionDeniedError: 403,
    ValidationError: 422,
}


def status_for_error(exc: PortalError) -> int:
    """根据异常类型返回 HTTP 状态码."""
    return _ERROR_STATUS.get(type(exc), 400)
