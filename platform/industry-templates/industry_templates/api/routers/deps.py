"""通用依赖与错误处理."""

from __future__ import annotations

from fastapi import Depends, Request

from industry_templates.api.jwt_auth import AuthContext, getAuthContext, requireAdmin
from industry_templates.services.exceptions import (
    DeploymentNotFoundError,
    NamespaceValidationError,
    ParameterValidationError,
    RenderError,
    TemplateError,
    TemplateNotDeployableError,
    TemplateNotFoundError,
)
from industry_templates.services.registry import ServiceRegistry


def get_registry(request: Request) -> ServiceRegistry:
    """从 app.state 获取服务注册表."""
    return request.app.state.registry


def require_admin(ctx: AuthContext = Depends(getAuthContext)) -> AuthContext:
    """写操作角色门禁：非 admin 抛 403（部署模板属高危写操作）。

    说明：本模块**不修改** MIRRORED 共享文件 jwt_auth.py，仅在其之上组合出
    「先认证（401）再授权（403）」的本地依赖，避免 13 处镜像副本同步风险。
    """
    requireAdmin(ctx)
    return ctx


# HTTP 状态码映射
_ERROR_STATUS: dict[type[TemplateError], int] = {
    TemplateNotFoundError: 404,
    DeploymentNotFoundError: 404,
    TemplateNotDeployableError: 409,
    ParameterValidationError: 422,
    RenderError: 422,
    NamespaceValidationError: 400,
}


def status_for_error(exc: TemplateError) -> int:
    """根据异常类型返回 HTTP 状态码."""
    return _ERROR_STATUS.get(type(exc), 400)
