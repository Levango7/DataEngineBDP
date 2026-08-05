"""知识工程引擎仓储层异常定义."""
from __future__ import annotations


class KnowledgeEngineError(Exception):
    """知识工程基础异常."""


class SpaceNotFoundError(KnowledgeEngineError):
    """图空间不存在."""

    def __init__(self, space: str):
        self.space = space
        super().__init__(f"图空间不存在: {space}")


class SpaceAlreadyExistsError(KnowledgeEngineError):
    """图空间已存在."""

    def __init__(self, space: str):
        self.space = space
        super().__init__(f"图空间已存在: {space}")


class VertexNotFoundError(KnowledgeEngineError):
    """顶点不存在."""

    def __init__(self, vid: str):
        self.vid = vid
        super().__init__(f"顶点不存在: {vid}")


class QuerySyntaxError(KnowledgeEngineError):
    """查询语法错误."""


class ValidationError(KnowledgeEngineError):
    """业务校验失败."""


class StoreUnavailableError(KnowledgeEngineError):
    """存储后端不可用（如 NebulaGraph 未配置或连接失败）."""


class ExtractorUnavailableError(KnowledgeEngineError):
    """抽取器不可用（如 LLM 网关未配置或调用失败）."""