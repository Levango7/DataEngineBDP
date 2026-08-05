"""通用依赖与错误处理."""
from __future__ import annotations

from fastapi import Request

from llmops.repositories import (
    DeploymentNotFoundError,
    DeploymentNotUndeployableError,
    LlmopsError,
    ModelAlreadyExistsError,
    ModelNotFoundError,
    TrainingJobNotFoundError,
    TrainingJobNotCancellableError,
    TrainingJobNotFinishedError,
    ValidationError,
    VersionNotFoundError,
)
from llmops.services.registry import ServiceRegistry


def get_registry(request: Request) -> ServiceRegistry:
    """从 app.state 获取服务注册表."""
    return request.app.state.registry


# HTTP 状态码映射
_ERROR_STATUS: dict[type[LlmopsError], int] = {
    ModelNotFoundError: 404,
    VersionNotFoundError: 404,
    TrainingJobNotFoundError: 404,
    DeploymentNotFoundError: 404,
    ModelAlreadyExistsError: 409,
    TrainingJobNotCancellableError: 409,
    TrainingJobNotFinishedError: 409,
    DeploymentNotUndeployableError: 409,
    ValidationError: 422,
}


def status_for_error(exc: LlmopsError) -> int:
    """根据异常类型返回 HTTP 状态码."""
    return _ERROR_STATUS.get(type(exc), 400)