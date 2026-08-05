"""通用依赖与错误处理."""
from __future__ import annotations

from fastapi import Request

from ml_platform.repositories import (
    EntityNotFoundError,
    ExperimentAlreadyExistsError,
    ExperimentNotFoundError,
    FeatureGroupAlreadyExistsError,
    FeatureGroupNotFoundError,
    MlPlatformError,
    ModelAlreadyExistsError,
    ModelNotFoundError,
    TrainingJobNotFoundError,
    ValidationError,
)
from ml_platform.services.registry import ServiceRegistry


def getRegistry(request: Request) -> ServiceRegistry:
    """从 app.state 获取服务注册表."""
    return request.app.state.registry


# HTTP 状态码映射
_ERROR_STATUS: dict[type[MlPlatformError], int] = {
    ModelNotFoundError: 404,
    ExperimentNotFoundError: 404,
    FeatureGroupNotFoundError: 404,
    EntityNotFoundError: 404,
    TrainingJobNotFoundError: 404,
    ModelAlreadyExistsError: 409,
    ExperimentAlreadyExistsError: 409,
    FeatureGroupAlreadyExistsError: 409,
    ValidationError: 422,
}


def statusForError(exc: MlPlatformError) -> int:
    """根据异常类型返回 HTTP 状态码."""
    return _ERROR_STATUS.get(type(exc), 400)