"""异常定义."""
from __future__ import annotations


class TemplateError(Exception):
    """模板平台基础异常."""

    def __init__(self, message: str, code: str = "TEMPLATE_ERROR") -> None:
        super().__init__(message)
        self.message = message
        self.code = code


class TemplateNotFoundError(TemplateError):
    """模板不存在."""

    def __init__(self, templateId: str) -> None:
        super().__init__(
            f"模板不存在: {templateId}",
            code="TEMPLATE_NOT_FOUND",
        )
        self.templateId = templateId


class TemplateNotDeployableError(TemplateError):
    """模板不可部署（非 catalog 态）."""

    def __init__(self, templateId: str, status: str) -> None:
        super().__init__(
            f"模板 {templateId} 当前状态 {status} 不可部署，仅 catalog 态可部署",
            code="TEMPLATE_NOT_DEPLOYABLE",
        )
        self.templateId = templateId
        self.status = status


class ParameterValidationError(TemplateError):
    """参数校验失败."""

    def __init__(self, message: str, missing: list[str] | None = None) -> None:
        super().__init__(message, code="PARAMETER_VALIDATION_ERROR")
        self.missing = missing or []


class DeploymentNotFoundError(TemplateError):
    """部署记录不存在."""

    def __init__(self, deploymentId: str) -> None:
        super().__init__(
            f"部署记录不存在: {deploymentId}",
            code="DEPLOYMENT_NOT_FOUND",
        )
        self.deploymentId = deploymentId


class RenderError(TemplateError):
    """参数注入/渲染失败."""

    def __init__(self, message: str) -> None:
        super().__init__(message, code="RENDER_ERROR")