"""通用依赖与错误处理."""

from __future__ import annotations

from fastapi import Header, Request

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


def get_current_user(
    x_user_id: str | None = Header(default=None, alias="X-User-Id"),
) -> str | None:
    """从请求头 X-User-Id 获取当前用户 ID（无则 None，表示匿名/管理员）."""
    return x_user_id


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
