"""通用依赖与错误处理."""

from __future__ import annotations

from fastapi import Request

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
