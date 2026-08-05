"""通用依赖与错误处理."""
from __future__ import annotations

from fastapi import Request

from knowledge_engine.repositories import (
    KnowledgeEngineError,
    QuerySyntaxError,
    SpaceAlreadyExistsError,
    SpaceNotFoundError,
    StoreUnavailableError,
    ExtractorUnavailableError,
    ValidationError,
    VertexNotFoundError,
)
from knowledge_engine.services.registry import ServiceRegistry


def get_registry(request: Request) -> ServiceRegistry:
    """从 app.state 获取服务注册表."""
    return request.app.state.registry


# HTTP 状态码映射
_ERROR_STATUS: dict[type[KnowledgeEngineError], int] = {
    SpaceNotFoundError: 404,
    VertexNotFoundError: 404,
    SpaceAlreadyExistsError: 409,
    ValidationError: 422,
    QuerySyntaxError: 400,
    StoreUnavailableError: 503,
    ExtractorUnavailableError: 503,
}


def status_for_error(exc: KnowledgeEngineError) -> int:
    """根据异常类型返回 HTTP 状态码."""
    return _ERROR_STATUS.get(type(exc), 400)