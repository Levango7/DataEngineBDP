"""API 一键生成服务 - SQL / 模型 / 函数 三种来源一键生成 RESTful API.

对应 T040 任务要求：
    1. SQL 一键生成：指定 SQL 查询生成 RESTful API（路由到 Trino/Doris）
    2. 模型一键生成：指定模型 ID 生成推理 API（路由到 LLM 推理服务）
    3. 函数一键生成：指定 Serverless 函数生成 API（路由到函数计算）

设计原则：
    - 三种来源统一产出 APIDefinition，复用 APIRegistryService.register_api
    - 自动推断参数（SQL 解析 :param 占位符 / 模型输入 schema / 函数签名）
    - 自动配置上游（upstream.type + url）
    - 自动配置计费策略默认值（按次 0.01 元）
    - 自动配置 SLA 默认值（SILVER）
    - 自动配置认证方式默认值（API_KEY）
"""
from __future__ import annotations

import re
import uuid
from typing import Any

from openapi_catalog.models import (
    APIDefinition,
    APIParam,
    APIResponse,
    APIStatus,
    APIUpstream,
    AuthType,
    CostStrategy,
    HttpMethod,
    ParamLocation,
    ParamType,
    SLALevel,
)
from openapi_catalog.repositories import CatalogError, ValidationError
from openapi_catalog.repositories.mock import MockCatalogStore
from openapi_catalog.services.api_registry import APIRegistryService


# ---------- 请求模型 ----------

class SqlGenerateRequest:
    """SQL 一键生成请求（Pydantic 模型在 routers 中定义）."""

    def __init__(
        self,
        name: str,
        sql: str,
        datasource: str,
        providerTenantId: str,
        description: str | None = None,
        category: str = "sql",
        tags: list[str] | None = None,
        costStrategy: str = "by_call",
        costUnitPrice: float = 0.01,
        sla: str = "silver",
    ):
        self.name = name
        self.sql = sql
        self.datasource = datasource
        self.providerTenantId = providerTenantId
        self.description = description
        self.category = category
        self.tags = tags or []
        self.costStrategy = costStrategy
        self.costUnitPrice = costUnitPrice
        self.sla = sla


class ModelGenerateRequest:
    """模型一键生成请求."""

    def __init__(
        self,
        name: str,
        modelId: str,
        modelType: str,
        providerTenantId: str,
        description: str | None = None,
        category: str = "model",
        tags: list[str] | None = None,
        costStrategy: str = "by_call",
        costUnitPrice: float = 0.10,
        sla: str = "gold",
        inputSchema: dict | None = None,
    ):
        self.name = name
        self.modelId = modelId
        self.modelType = modelType  # llm / embedding / rerank / classification
        self.providerTenantId = providerTenantId
        self.description = description
        self.category = category
        self.tags = tags or []
        self.costStrategy = costStrategy
        self.costUnitPrice = costUnitPrice
        self.sla = sla
        self.inputSchema = inputSchema


class FunctionGenerateRequest:
    """函数一键生成请求."""

    def __init__(
        self,
        name: str,
        functionName: str,
        runtime: str,
        providerTenantId: str,
        description: str | None = None,
        category: str = "function",
        tags: list[str] | None = None,
        costStrategy: str = "by_call",
        costUnitPrice: float = 0.001,
        sla: str = "silver",
        timeout: int = 30000,
        memoryMB: int = 512,
    ):
        self.name = name
        self.functionName = functionName
        self.runtime = runtime  # python / nodejs / java
        self.providerTenantId = providerTenantId
        self.description = description
        self.category = category
        self.tags = tags or []
        self.costStrategy = costStrategy
        self.costUnitPrice = costUnitPrice
        self.sla = sla
        self.timeout = timeout
        self.memoryMB = memoryMB


# ---------- SQL 参数解析 ----------

# 匹配 :param 或 ${param} 或 @param 形式的参数占位符
_SQL_PARAM_PATTERNS = [
    re.compile(r":(\w+)"),
    re.compile(r"\$\{(\w+)\}"),
    re.compile(r"@(\w+)"),
]


def parse_sql_params(sql: str) -> list[APIParam]:
    """从 SQL 中解析参数占位符.

    支持 :param、${param}、@param 三种形式。
    自动推断类型：以 _id 结尾视为 integer，否则视为 string。

    Args:
        sql: SQL 查询语句.

    Returns:
        参数列表.
    """
    seen: set[str] = set()
    params: list[APIParam] = []
    for pattern in _SQL_PARAM_PATTERNS:
        for match in pattern.finditer(sql):
            name = match.group(1)
            if name in seen:
                continue
            seen.add(name)
            # 类型推断
            if name.lower().endswith("_id") or name.lower() == "id":
                ptype = ParamType.INTEGER
            elif name.lower().startswith("is_") or name.lower().startswith("has_"):
                ptype = ParamType.BOOLEAN
            else:
                ptype = ParamType.STRING
            params.append(
                APIParam(
                    name=name,
                    location=ParamLocation.QUERY,
                    type=ptype,
                    required=False,
                    description=f"SQL 参数 {name}",
                )
            )
    return params


# ---------- 数据源到上游映射 ----------

_DATASOURCE_UPSTREAM_MAP = {
    "trino": {
        "type": "trino",
        "url": "http://trino:8080/v1/statement",
        "method": HttpMethod.POST,
    },
    "doris": {
        "type": "doris",
        "url": "http://doris-fe:9030/api/query",
        "method": HttpMethod.POST,
    },
    "hive": {
        "type": "trino",
        "url": "http://trino:8080/v1/statement",
        "method": HttpMethod.POST,
    },
    "mysql": {
        "type": "mysql",
        "url": "http://mysql-proxy:3306/query",
        "method": HttpMethod.POST,
    },
    "postgresql": {
        "type": "postgresql",
        "url": "http://pg-proxy:5432/query",
        "method": HttpMethod.POST,
    },
}


# ---------- 模型类型到上游映射 ----------

_MODEL_TYPE_UPSTREAM_MAP = {
    "llm": {
        "type": "llm",
        "url": "http://llm-gateway:8000/v1/chat/completions",
        "method": HttpMethod.POST,
    },
    "embedding": {
        "type": "llm",
        "url": "http://llm-gateway:8000/v1/embeddings",
        "method": HttpMethod.POST,
    },
    "rerank": {
        "type": "llm",
        "url": "http://llm-gateway:8000/v1/rerank",
        "method": HttpMethod.POST,
    },
    "classification": {
        "type": "llm",
        "url": "http://llm-gateway:8000/v1/classifications",
        "method": HttpMethod.POST,
    },
    "image": {
        "type": "llm",
        "url": "http://llm-gateway:8000/v1/images/generations",
        "method": HttpMethod.POST,
    },
}


# ---------- 函数运行时到上游映射 ----------

_FUNCTION_RUNTIME_UPSTREAM_MAP = {
    "python": {
        "type": "function",
        "url": "http://function-python:9000/invoke",
        "method": HttpMethod.POST,
    },
    "nodejs": {
        "type": "function",
        "url": "http://function-nodejs:9000/invoke",
        "method": HttpMethod.POST,
    },
    "java": {
        "type": "function",
        "url": "http://function-java:9000/invoke",
        "method": HttpMethod.POST,
    },
    "go": {
        "type": "function",
        "url": "http://function-go:9000/invoke",
        "method": HttpMethod.POST,
    },
}


def _safe_sla(value: str) -> SLALevel:
    """安全转换 SLA 等级."""
    try:
        return SLALevel(value)
    except ValueError:
        return SLALevel.SILVER


def _safe_cost_strategy(value: str) -> CostStrategy:
    """安全转换计费策略."""
    try:
        return CostStrategy(value)
    except ValueError:
        return CostStrategy.BY_CALL


# ---------- 生成服务 ----------

class APIGeneratorService:
    """API 一键生成服务.

    支持三种来源：
        1. SQL 一键生成：指定 SQL + 数据源 → RESTful API
        2. 模型一键生成：指定模型 ID + 模型类型 → 推理 API
        3. 函数一键生成：指定函数名 + 运行时 → 函数 API
    """

    def __init__(
        self,
        store: MockCatalogStore,
        api_registry: APIRegistryService,
    ) -> None:
        self.store = store
        self.apiRegistryService = api_registry

    async def generate_from_sql(self, req: SqlGenerateRequest) -> APIDefinition:
        """从 SQL 查询一键生成 RESTful API.

        Args:
            req: SQL 生成请求.

        Returns:
            注册后的 API 定义.

        Raises:
            ValidationError: SQL 为空或数据源不支持.
        """
        # 校验
        if not req.sql or not req.sql.strip():
            raise ValidationError("SQL 不能为空")
        sql_stripped = req.sql.strip()
        # 简单 SQL 安全校验：禁止 DDL/DML 操作
        forbidden = ("drop ", "delete ", "update ", "insert ", "alter ", "truncate ", "create ")
        sql_lower = sql_stripped.lower()
        for kw in forbidden:
            if kw in sql_lower:
                raise ValidationError(f"SQL 包含禁止的操作: {kw.strip()}")

        if req.datasource not in _DATASOURCE_UPSTREAM_MAP:
            raise ValidationError(
                f"不支持的数据源: {req.datasource}，"
                f"支持: {list(_DATASOURCE_UPSTREAM_MAP.keys())}"
            )

        # 解析 SQL 参数
        params = parse_sql_params(sql_stripped)

        # 构建上游
        upstream_cfg = _DATASOURCE_UPSTREAM_MAP[req.datasource]
        upstream = APIUpstream(
            type=upstream_cfg["type"],
            url=upstream_cfg["url"],
            method=upstream_cfg["method"],
            timeout=30000,
            retries=2,
        )

        # 构建路径
        path = f"/sql/{req.name.lower().replace('_', '-')}"

        # 构建 API 定义
        api = APIDefinition(
            id="",
            name=req.name,
            version="1.0.0",
            description=req.description or f"由 SQL 一键生成: {sql_stripped[:80]}",
            category=req.category,
            tags=req.tags + ["sql-generated", f"datasource:{req.datasource}"],
            method=HttpMethod.POST,
            path=path,
            params=params,
            responses=[
                APIResponse(
                    statusCode=200,
                    description="查询成功",
                    schema={"type": "array", "items": {"type": "object"}},
                    example={"code": 0, "data": [], "total": 0},
                ),
                APIResponse(
                    statusCode=400,
                    description="参数错误",
                ),
            ],
            authType=AuthType.API_KEY,
            upstream=upstream,
            sla=_safe_sla(req.sla),
            costStrategy=_safe_cost_strategy(req.costStrategy),
            costUnitPrice=req.costUnitPrice,
            status=APIStatus.DRAFT,
            providerTenantId=req.providerTenantId,
        )

        # 注入 SQL 元数据到 tags（实际场景可存到独立字段或扩展属性）
        # 这里通过 description 保留 SQL 摘要，便于审计
        return await self.apiRegistryService.register_api(api)

    async def generate_from_model(self, req: ModelGenerateRequest) -> APIDefinition:
        """从模型 ID 一键生成推理 API.

        Args:
            req: 模型生成请求.

        Returns:
            注册后的 API 定义.

        Raises:
            ValidationError: 模型 ID 为空或类型不支持.
        """
        if not req.modelId or not req.modelId.strip():
            raise ValidationError("模型 ID 不能为空")
        if req.modelType not in _MODEL_TYPE_UPSTREAM_MAP:
            raise ValidationError(
                f"不支持的模型类型: {req.modelType}，"
                f"支持: {list(_MODEL_TYPE_UPSTREAM_MAP.keys())}"
            )

        # 构建上游
        upstream_cfg = _MODEL_TYPE_UPSTREAM_MAP[req.modelType]
        upstream = APIUpstream(
            type=upstream_cfg["type"],
            url=upstream_cfg["url"],
            method=upstream_cfg["method"],
            timeout=60000,  # 模型推理超时较长
            retries=1,
        )

        # 构建路径
        path = f"/model/{req.name.lower().replace('_', '-')}"

        # 构建参数（模型输入参数）
        params: list[APIParam] = []
        if req.inputSchema and isinstance(req.inputSchema, dict):
            properties = req.inputSchema.get("properties", {})
            required_names = set(req.inputSchema.get("required", []))
            for prop_name, prop_def in properties.items():
                prop_type = prop_def.get("type", "string")
                type_map = {
                    "string": ParamType.STRING,
                    "integer": ParamType.INTEGER,
                    "number": ParamType.NUMBER,
                    "boolean": ParamType.BOOLEAN,
                    "array": ParamType.ARRAY,
                    "object": ParamType.OBJECT,
                }
                params.append(
                    APIParam(
                        name=prop_name,
                        location=ParamLocation.BODY,
                        type=type_map.get(prop_type, ParamType.STRING),
                        required=prop_name in required_names,
                        description=prop_def.get("description", f"输入参数 {prop_name}"),
                    )
                )
        else:
            # 默认参数：根据模型类型推断
            if req.modelType == "llm":
                params = [
                    APIParam(
                        name="messages",
                        location=ParamLocation.BODY,
                        type=ParamType.ARRAY,
                        required=True,
                        description="对话消息列表",
                    ),
                    APIParam(
                        name="temperature",
                        location=ParamLocation.BODY,
                        type=ParamType.NUMBER,
                        required=False,
                        description="采样温度",
                        default=0.7,
                    ),
                    APIParam(
                        name="max_tokens",
                        location=ParamLocation.BODY,
                        type=ParamType.INTEGER,
                        required=False,
                        description="最大生成 token 数",
                        default=1024,
                    ),
                ]
            elif req.modelType == "embedding":
                params = [
                    APIParam(
                        name="input",
                        location=ParamLocation.BODY,
                        type=ParamType.STRING,
                        required=True,
                        description="待向量化的文本",
                    ),
                ]
            elif req.modelType == "rerank":
                params = [
                    APIParam(
                        name="query",
                        location=ParamLocation.BODY,
                        type=ParamType.STRING,
                        required=True,
                        description="查询文本",
                    ),
                    APIParam(
                        name="documents",
                        location=ParamLocation.BODY,
                        type=ParamType.ARRAY,
                        required=True,
                        description="候选文档列表",
                    ),
                ]

        # 构建 API 定义
        api = APIDefinition(
            id="",
            name=req.name,
            version="1.0.0",
            description=req.description or f"模型推理 API: {req.modelId} ({req.modelType})",
            category=req.category,
            tags=req.tags + ["model-generated", f"model:{req.modelId}", f"model-type:{req.modelType}"],
            method=HttpMethod.POST,
            path=path,
            params=params,
            responses=[
                APIResponse(
                    statusCode=200,
                    description="推理成功",
                    schema={"type": "object"},
                    example={"code": 0, "data": {}, "model": req.modelId},
                ),
                APIResponse(
                    statusCode=500,
                    description="模型推理失败",
                ),
            ],
            authType=AuthType.API_KEY,
            upstream=upstream,
            sla=_safe_sla(req.sla),
            costStrategy=_safe_cost_strategy(req.costStrategy),
            costUnitPrice=req.costUnitPrice,
            status=APIStatus.DRAFT,
            providerTenantId=req.providerTenantId,
        )

        return await self.apiRegistryService.register_api(api)

    async def generate_from_function(self, req: FunctionGenerateRequest) -> APIDefinition:
        """从 Serverless 函数一键生成 API.

        Args:
            req: 函数生成请求.

        Returns:
            注册后的 API 定义.

        Raises:
            ValidationError: 函数名为空或运行时不支持.
        """
        if not req.functionName or not req.functionName.strip():
            raise ValidationError("函数名不能为空")
        if req.runtime not in _FUNCTION_RUNTIME_UPSTREAM_MAP:
            raise ValidationError(
                f"不支持的运行时: {req.runtime}，"
                f"支持: {list(_FUNCTION_RUNTIME_UPSTREAM_MAP.keys())}"
            )
        if req.timeout < 1000 or req.timeout > 900000:
            raise ValidationError("超时必须在 1000ms ~ 900000ms 之间")
        if req.memoryMB < 128 or req.memoryMB > 32768:
            raise ValidationError("内存必须在 128MB ~ 32768MB 之间")

        # 构建上游
        upstream_cfg = _FUNCTION_RUNTIME_UPSTREAM_MAP[req.runtime]
        upstream = APIUpstream(
            type=upstream_cfg["type"],
            url=upstream_cfg["url"],
            method=upstream_cfg["method"],
            timeout=req.timeout,
            retries=0,  # 函数计算不重试
        )

        # 构建路径
        path = f"/function/{req.name.lower().replace('_', '-')}"

        # 构建 API 定义
        api = APIDefinition(
            id="",
            name=req.name,
            version="1.0.0",
            description=req.description or f"Serverless 函数 API: {req.functionName} ({req.runtime})",
            category=req.category,
            tags=req.tags + [
                "function-generated",
                f"function:{req.functionName}",
                f"runtime:{req.runtime}",
                f"memory:{req.memoryMB}MB",
            ],
            method=HttpMethod.POST,
            path=path,
            params=[
                APIParam(
                    name="payload",
                    location=ParamLocation.BODY,
                    type=ParamType.OBJECT,
                    required=False,
                    description="函数输入参数",
                ),
            ],
            responses=[
                APIResponse(
                    statusCode=200,
                    description="函数执行成功",
                    schema={"type": "object"},
                    example={"code": 0, "data": {}},
                ),
                APIResponse(
                    statusCode=502,
                    description="函数执行失败",
                ),
                APIResponse(
                    statusCode=504,
                    description="函数执行超时",
                ),
            ],
            authType=AuthType.API_KEY,
            upstream=upstream,
            sla=_safe_sla(req.sla),
            costStrategy=_safe_cost_strategy(req.costStrategy),
            costUnitPrice=req.costUnitPrice,
            status=APIStatus.DRAFT,
            providerTenantId=req.providerTenantId,
        )

        return await self.apiRegistryService.register_api(api)

    def list_supported_datasources(self) -> list[str]:
        """列出支持的数据源."""
        return list(_DATASOURCE_UPSTREAM_MAP.keys())

    def list_supported_model_types(self) -> list[str]:
        """列出支持的模型类型."""
        return list(_MODEL_TYPE_UPSTREAM_MAP.keys())

    def list_supported_runtimes(self) -> list[str]:
        """列出支持的函数运行时."""
        return list(_FUNCTION_RUNTIME_UPSTREAM_MAP.keys())