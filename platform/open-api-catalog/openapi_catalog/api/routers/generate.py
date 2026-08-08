"""API 一键生成路由 - SQL / 模型 / 函数 三种来源.

对应 T040 任务要求：
    POST /api/v1/apis/generate/sql      指定 SQL 查询生成 RESTful API
    POST /api/v1/apis/generate/model    指定模型 ID 生成推理 API
    POST /api/v1/apis/generate/function 指定 Serverless 函数生成 API
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from openapi_catalog.api.routers.deps import get_registry, status_for_error
from openapi_catalog.models import APIDefinition
from openapi_catalog.repositories import CatalogError
from openapi_catalog.services.api_generator import (
    APIGeneratorService,
    FunctionGenerateRequest,
    ModelGenerateRequest,
    SqlGenerateRequest,
)
from openapi_catalog.services.registry import ServiceRegistry
from pydantic import BaseModel, Field

router = APIRouter(prefix="/apis/generate", tags=["generate"])


# ---------- 请求模型 ----------


class SqlGenerateBody(BaseModel):
    """SQL 一键生成请求体."""

    name: str = Field(..., min_length=1, max_length=128, description="API 名称")
    sql: str = Field(..., min_length=1, description="SQL 查询语句")
    datasource: str = Field(..., description="数据源: trino/doris/hive/mysql/postgresql")
    providerTenantId: str = Field(..., description="提供方租户 ID")
    description: str | None = Field(default=None, description="API 描述")
    category: str = Field(default="sql", description="分类")
    tags: list[str] = Field(default_factory=list, description="标签")
    costStrategy: str = Field(default="by_call", description="计费策略")
    costUnitPrice: float = Field(default=0.01, ge=0, description="单价")
    sla: str = Field(default="silver", description="SLA 等级")


class ModelGenerateBody(BaseModel):
    """模型一键生成请求体."""

    name: str = Field(..., min_length=1, max_length=128, description="API 名称")
    modelId: str = Field(..., min_length=1, description="模型 ID")
    modelType: str = Field(..., description="模型类型: llm/embedding/rerank/classification/image")
    providerTenantId: str = Field(..., description="提供方租户 ID")
    description: str | None = Field(default=None, description="API 描述")
    category: str = Field(default="model", description="分类")
    tags: list[str] = Field(default_factory=list, description="标签")
    costStrategy: str = Field(default="by_call", description="计费策略")
    costUnitPrice: float = Field(default=0.10, ge=0, description="单价")
    sla: str = Field(default="gold", description="SLA 等级")
    inputSchema: dict | None = Field(default=None, description="输入 Schema")


class FunctionGenerateBody(BaseModel):
    """函数一键生成请求体."""

    name: str = Field(..., min_length=1, max_length=128, description="API 名称")
    functionName: str = Field(..., min_length=1, description="函数名")
    runtime: str = Field(..., description="运行时: python/nodejs/java/go")
    providerTenantId: str = Field(..., description="提供方租户 ID")
    description: str | None = Field(default=None, description="API 描述")
    category: str = Field(default="function", description="分类")
    tags: list[str] = Field(default_factory=list, description="标签")
    costStrategy: str = Field(default="by_call", description="计费策略")
    costUnitPrice: float = Field(default=0.001, ge=0, description="单价")
    sla: str = Field(default="silver", description="SLA 等级")
    timeout: int = Field(default=30000, ge=1000, le=900000, description="超时(ms)")
    memoryMB: int = Field(default=512, ge=128, le=32768, description="内存(MB)")


class GenerateOptionsResponse(BaseModel):
    """生成选项响应."""

    datasources: list[str] = Field(..., description="支持的数据源")
    modelTypes: list[str] = Field(..., description="支持的模型类型")
    runtimes: list[str] = Field(..., description="支持的函数运行时")


# ---------- 服务获取依赖 ----------


def _get_generator(registry: ServiceRegistry) -> APIGeneratorService:
    """从 registry 获取或构建 APIGeneratorService."""
    # 缓存到 registry 上避免重复构建
    if not hasattr(registry, "_apiGeneratorService") or registry._apiGeneratorService is None:
        registry._apiGeneratorService = APIGeneratorService(registry.store, registry.apiRegistryService)
    return registry._apiGeneratorService


# ---------- 路由 ----------


@router.post(
    "/sql",
    response_model=APIDefinition,
    status_code=status.HTTP_201_CREATED,
    summary="SQL 一键生成 API",
)
async def generate_from_sql(
    body: SqlGenerateBody,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """从 SQL 查询一键生成 RESTful API.

    指定 SQL + 数据源，自动生成 API 定义：
    - 自动解析 SQL 参数占位符（:param / ${param} / @param）
    - 自动配置上游（Trino/Doris/MySQL/PostgreSQL）
    - 自动配置计费策略默认值
    - 自动配置 SLA 默认值

    安全限制：仅允许 SELECT 查询，禁止 DDL/DML 操作。
    """
    try:
        generator = _get_generator(registry)
        req = SqlGenerateRequest(
            name=body.name,
            sql=body.sql,
            datasource=body.datasource,
            providerTenantId=body.providerTenantId,
            description=body.description,
            category=body.category,
            tags=body.tags,
            costStrategy=body.costStrategy,
            costUnitPrice=body.costUnitPrice,
            sla=body.sla,
        )
        return await generator.generate_from_sql(req)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/model",
    response_model=APIDefinition,
    status_code=status.HTTP_201_CREATED,
    summary="模型一键生成 API",
)
async def generate_from_model(
    body: ModelGenerateBody,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """从模型 ID 一键生成推理 API.

    指定模型 ID + 模型类型，自动生成推理 API：
    - llm → /v1/chat/completions（对话推理）
    - embedding → /v1/embeddings（向量化）
    - rerank → /v1/rerank（重排）
    - classification → /v1/classifications（分类）
    - image → /v1/images/generations（图像生成）

    自动推断输入参数 schema，默认 POST 方法。
    """
    try:
        generator = _get_generator(registry)
        req = ModelGenerateRequest(
            name=body.name,
            modelId=body.modelId,
            modelType=body.modelType,
            providerTenantId=body.providerTenantId,
            description=body.description,
            category=body.category,
            tags=body.tags,
            costStrategy=body.costStrategy,
            costUnitPrice=body.costUnitPrice,
            sla=body.sla,
            inputSchema=body.inputSchema,
        )
        return await generator.generate_from_model(req)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/function",
    response_model=APIDefinition,
    status_code=status.HTTP_201_CREATED,
    summary="函数一键生成 API",
)
async def generate_from_function(
    body: FunctionGenerateBody,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIDefinition:
    """从 Serverless 函数一键生成 API.

    指定函数名 + 运行时，自动生成函数 API：
    - python → 函数计算 Python 运行时
    - nodejs → 函数计算 Node.js 运行时
    - java → 函数计算 Java 运行时
    - go → 函数计算 Go 运行时

    支持配置超时（1s ~ 900s）和内存（128MB ~ 32GB）。
    """
    try:
        generator = _get_generator(registry)
        req = FunctionGenerateRequest(
            name=body.name,
            functionName=body.functionName,
            runtime=body.runtime,
            providerTenantId=body.providerTenantId,
            description=body.description,
            category=body.category,
            tags=body.tags,
            costStrategy=body.costStrategy,
            costUnitPrice=body.costUnitPrice,
            sla=body.sla,
            timeout=body.timeout,
            memoryMB=body.memoryMB,
        )
        return await generator.generate_from_function(req)
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/options",
    response_model=GenerateOptionsResponse,
    summary="生成选项",
)
async def get_generate_options(
    registry: ServiceRegistry = Depends(get_registry),
) -> GenerateOptionsResponse:
    """获取一键生成支持的选项（数据源/模型类型/运行时）."""
    generator = _get_generator(registry)
    return GenerateOptionsResponse(
        datasources=generator.list_supported_datasources(),
        modelTypes=generator.list_supported_model_types(),
        runtimes=generator.list_supported_runtimes(),
    )
