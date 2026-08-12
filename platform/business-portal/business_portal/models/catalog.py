"""数据目录模型（树形结构）.

每个业务线有独立的数据目录，跨业务线默认不可见（数据隔离）。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from business_portal.models.base import CatalogNodeType, utc_now


class CatalogNode(BaseModel):
    """数据目录节点.

    - id:       节点 ID
    - blId:     所属业务线 ID（隔离边界）
    - parentId: 父节点 ID（根节点为 None）
    - name:     节点名称
    - type:     节点类型（database/schema/table/view/dataset/model）
    - children: 子节点 ID 列表
    - assetCount: 直接挂载的资产数
    - description: 描述
    - tags:     标签
    """

    id: str
    blId: str = Field(..., description="所属业务线 ID（隔离边界）")
    parentId: str | None = Field(default=None, description="父节点 ID")
    name: str = Field(..., min_length=1, max_length=256)
    type: CatalogNodeType = Field(..., description="节点类型")
    children: list[str] = Field(default_factory=list, description="子节点 ID 列表")
    assetCount: int = Field(default=0, ge=0, description="资产数")
    description: str | None = None
    tags: dict[str, str] = Field(default_factory=dict)
    createdAt: datetime = Field(default_factory=utc_now)
    updatedAt: datetime = Field(default_factory=utc_now)
    extra: dict[str, Any] = Field(default_factory=dict)


class CatalogTree(BaseModel):
    """数据目录树（业务线隔离）."""

    blId: str
    nodes: list[CatalogNode] = Field(default_factory=list)
    rootIds: list[str] = Field(default_factory=list, description="根节点 ID 列表")
    updatedAt: datetime = Field(default_factory=utc_now)
