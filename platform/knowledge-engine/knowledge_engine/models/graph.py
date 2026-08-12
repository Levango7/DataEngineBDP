"""图模型：顶点、边、Schema、查询结果."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class Vertex(BaseModel):
    """图顶点.

    Attributes:
        id: 顶点 ID（VID）。
        label: 顶点标签（即实体类型）。
        properties: 顶点属性。
    """

    id: str = Field(..., description="顶点 ID")
    label: str = Field(..., description="顶点标签")
    properties: dict[str, Any] = Field(default_factory=dict, description="顶点属性")


class Edge(BaseModel):
    """图边（有向）.

    Attributes:
        srcId: 起点顶点 ID。
        dstId: 终点顶点 ID。
        type: 边类型（关系类型）。
        properties: 边属性。
    """

    srcId: str = Field(..., description="起点顶点 ID")
    dstId: str = Field(..., description="终点顶点 ID")
    type: str = Field(..., description="边类型")
    properties: dict[str, Any] = Field(default_factory=dict, description="边属性")


class VertexLabelDefinition(BaseModel):
    """顶点标签定义（Schema 元数据）.

    Attributes:
        name: 标签名（如 "Person"）。
        properties: 属性名 -> 类型字符串。
    """

    name: str = Field(..., description="标签名")
    properties: dict[str, str] = Field(default_factory=dict, description="属性名->类型")


class EdgeTypeDefinition(BaseModel):
    """边类型定义（Schema 元数据）.

    Attributes:
        name: 边类型名（如 "works_for"）。
        srcLabel: 起点标签。
        dstLabel: 终点标签。
        properties: 属性名 -> 类型字符串。
    """

    name: str = Field(..., description="边类型名")
    srcLabel: str = Field(..., description="起点标签")
    dstLabel: str = Field(..., description="终点标签")
    properties: dict[str, str] = Field(default_factory=dict, description="属性名->类型")


class GraphSchema(BaseModel):
    """图模式（知识空间 Schema）.

    Attributes:
        vertexLabels: 顶点标签定义列表。
        edgeTypes: 边类型定义列表。
    """

    vertexLabels: list[VertexLabelDefinition] = Field(default_factory=list)
    edgeTypes: list[EdgeTypeDefinition] = Field(default_factory=list)


class QueryResult(BaseModel):
    """图查询结果（nGQL/GQL 通用返回包装）.

    Attributes:
        columns: 列名列表。
        rows: 行数据，每行为 dict[col -> value]。
        latencyMs: 查询耗时（毫秒）。
    """

    columns: list[str] = Field(default_factory=list)
    rows: list[dict[str, Any]] = Field(default_factory=list)
    latencyMs: float = Field(default=0.0, ge=0.0, description="查询耗时(ms)")
