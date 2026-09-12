"""通用依赖与错误处理."""

from __future__ import annotations

import os

from fastapi import Depends, HTTPException, Request, status

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


def _is_test_env() -> bool:
    """判断是否运行在测试环境（允许缺少租户上下文）.

    R13 安全修复：fail-closed — 非测试环境强制要求 tenant_id 非空。
    测试环境通过 PYTEST_CURRENT_TEST 环境变量或 ENVIRONMENT=test 标识。
    """
    return (
        "PYTEST_CURRENT_TEST" in os.environ
        or os.environ.get("ENVIRONMENT", "").strip().lower() == "test"
        or os.environ.get("TESTING", "").strip().lower() == "true"
    )


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

    AUTH_MODE=none（本地/测试）：从 X-Tenant-Id 头读取
    AUTH_MODE=jwt（生产）：从 JWT tenantId 声明读取

    R13 安全修复：fail-closed — 非测试环境强制要求 tenant_id 非空，
    缺失租户上下文时返回 403 而非 None，防止未认证请求绕过租户隔离。
    """
    if _is_anon_mode():
        tenant_id = request.headers.get("X-Tenant-Id")
        # R13 安全修复：fail-closed — 非测试环境缺少租户上下文时拒绝访问
        if not tenant_id and not _is_test_env():
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="缺少租户上下文（X-Tenant-Id）",
            )
        return tenant_id
    # jwt 模式：fail-closed — 缺少 tenantId 声明时拒绝访问
    tenant_id = ctx.tenantId or None
    if not tenant_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="缺少租户上下文（JWT tenantId）",
        )
    return tenant_id


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
