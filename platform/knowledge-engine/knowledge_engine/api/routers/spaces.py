"""知识空间路由 - 实体/关系/抽取/构建/查询/邻居/最短路径.

端点总览：
    POST   /spaces                              创建知识空间
    GET    /spaces                              列出知识空间
    DELETE /spaces/{name}                       删除知识空间
    POST   /spaces/{name}/entities              插入实体
    POST   /spaces/{name}/edges                 插入关系
    POST   /spaces/{name}/extract               从文本抽取知识（不写入）
    POST   /spaces/{name}/build                 从文本构建知识图谱（抽取+写入）
    GET    /spaces/{name}/vertices/{vid}        查询顶点
    GET    /spaces/{name}/vertices/{vid}/neighbors  查询邻居
    POST   /spaces/{name}/query                 原生图查询
    POST   /spaces/{name}/shortest-path         最短路径查询
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field

from knowledge_engine.api.routers.deps import get_registry, status_for_error
from knowledge_engine.models.entity import Entity
from knowledge_engine.models.graph import (
    Edge,
    GraphSchema,
    QueryResult,
    Vertex,
)
from knowledge_engine.models.relation import Relation
from knowledge_engine.repositories import KnowledgeEngineError
from knowledge_engine.services.registry import ServiceRegistry

router = APIRouter(prefix="/spaces", tags=["spaces"])


# ---------- 请求/响应模型 ----------

class CreateSpaceRequest(BaseModel):
    """创建知识空间请求."""

    name: str = Field(..., min_length=1, max_length=64, description="空间名")
    schema_: GraphSchema = Field(
        default_factory=GraphSchema, alias="schema", description="图模式"
    )

    model_config = {"populate_by_name": True}


class InsertEntitiesRequest(BaseModel):
    """插入实体请求."""

    entities: list[Entity] = Field(..., min_length=1)


class InsertEdgesRequest(BaseModel):
    """插入关系请求."""

    edges: list[Relation] = Field(..., min_length=1)


class ExtractRequest(BaseModel):
    """抽取请求."""

    text: str = Field(..., min_length=1, description="输入文本")
    entityTypes: list[str] | None = Field(default=None, description="限定实体类型")


class BuildRequest(BaseModel):
    """构建请求（抽取 + 写入）."""

    text: str = Field(..., min_length=1, description="输入文本")
    entityTypes: list[str] | None = Field(default=None, description="限定实体类型")


class QueryRequest(BaseModel):
    """图查询请求."""

    nql: str = Field(..., min_length=1, description="nGQL/GQL 查询语句")


class ShortestPathRequest(BaseModel):
    """最短路径请求."""

    srcId: str = Field(..., description="起点顶点 ID")
    dstId: str = Field(..., description="终点顶点 ID")


class ExtractResponse(BaseModel):
    """抽取响应."""

    entities: list[Entity]
    relations: list[Relation]


class BuildResponse(BaseModel):
    """构建响应."""

    space: str
    insertedVertices: int
    insertedEdges: int
    entities: list[Entity]
    relations: list[Relation]


class InsertSummaryResponse(BaseModel):
    """写入计数响应."""

    inserted: int


# ---------- 路由 ----------

@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    summary="创建知识空间",
)
async def create_space(
    req: CreateSpaceRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """创建一个知识空间（图空间）."""
    try:
        await registry.knowledgeService.create_space(req.name, req.schema_)
        return {"name": req.name, "status": "created"}
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "",
    response_model=list[str],
    summary="列出知识空间",
)
async def list_spaces(
    registry: ServiceRegistry = Depends(get_registry),
) -> list[str]:
    """列出所有知识空间."""
    return await registry.knowledgeService.list_spaces()


@router.delete(
    "/{name}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="删除知识空间",
)
async def drop_space(
    name: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    """删除知识空间."""
    try:
        await registry.knowledgeService.drop_space(name)
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{name}/entities",
    response_model=InsertSummaryResponse,
    summary="插入实体",
)
async def insert_entities(
    name: str,
    req: InsertEntitiesRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> InsertSummaryResponse:
    """直接插入实体（跳过抽取）."""
    try:
        count = await registry.knowledgeService.insert_entities(name, req.entities)
        return InsertSummaryResponse(inserted=count)
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{name}/edges",
    response_model=InsertSummaryResponse,
    summary="插入关系",
)
async def insert_edges(
    name: str,
    req: InsertEdgesRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> InsertSummaryResponse:
    """直接插入关系（跳过抽取）."""
    try:
        count = await registry.knowledgeService.insert_relations(name, req.edges)
        return InsertSummaryResponse(inserted=count)
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{name}/extract",
    response_model=ExtractResponse,
    summary="从文本抽取知识",
)
async def extract(
    name: str,
    req: ExtractRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> ExtractResponse:
    """从文本抽取实体与关系（不写入图存储）."""
    try:
        result = await registry.knowledgeService.extract(
            name, req.text, req.entityTypes
        )
        return ExtractResponse(
            entities=result.entities, relations=result.relations
        )
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{name}/build",
    response_model=BuildResponse,
    summary="构建知识图谱",
)
async def build(
    name: str,
    req: BuildRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> BuildResponse:
    """从文本构建知识图谱：抽取 + 写入图存储."""
    try:
        result = await registry.knowledgeService.build(
            name, req.text, req.entityTypes
        )
        return BuildResponse(
            space=result.space,
            insertedVertices=result.insertedVertices,
            insertedEdges=result.insertedEdges,
            entities=result.entities,
            relations=result.relations,
        )
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{name}/vertices/{vid}",
    response_model=Vertex,
    summary="查询顶点",
)
async def get_vertex(
    name: str,
    vid: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> Vertex:
    """根据 ID 查询顶点."""
    try:
        return await registry.queryService.get_vertex(name, vid)
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{name}/vertices/{vid}/neighbors",
    response_model=list[Vertex],
    summary="查询邻居",
)
async def get_neighbors(
    name: str,
    vid: str,
    edgeType: list[str] | None = Query(
        default=None, description="限定边类型（可多次传）"
    ),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Vertex]:
    """查询顶点的邻居."""
    try:
        return await registry.queryService.get_neighbors(name, vid, edgeType)
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{name}/query",
    response_model=QueryResult,
    summary="图查询",
)
async def query(
    name: str,
    req: QueryRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> QueryResult:
    """执行原生图查询（nGQL/GQL）."""
    try:
        return await registry.queryService.query(name, req.nql)
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{name}/shortest-path",
    response_model=list[Vertex],
    summary="最短路径查询",
)
async def shortest_path(
    name: str,
    req: ShortestPathRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Vertex]:
    """最短路径查询（BFS）."""
    try:
        return await registry.queryService.shortest_path(name, req.srcId, req.dstId)
    except KnowledgeEngineError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))