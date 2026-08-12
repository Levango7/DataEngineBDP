"""业务线数据目录路由."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from business_portal.api.routers.deps import get_registry, status_for_error
from business_portal.models.base import CatalogNodeType
from business_portal.models.catalog import CatalogNode, CatalogTree
from business_portal.repositories import PortalError
from business_portal.services.registry import ServiceRegistry

router = APIRouter(prefix="/business-lines", tags=["catalog"])


class AddCatalogNodeRequest(BaseModel):
    """添加目录节点请求."""

    id: str | None = Field(default=None, description="节点 ID（不传则自动生成）")
    parentId: str | None = Field(default=None, description="父节点 ID")
    name: str = Field(..., min_length=1, max_length=256)
    type: CatalogNodeType
    description: str | None = None
    tags: dict[str, str] = Field(default_factory=dict)


@router.get(
    "/{bl_id}/catalog",
    response_model=CatalogTree,
    summary="业务线数据目录",
)
async def get_catalog(
    bl_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> CatalogTree:
    """获取业务线数据目录树（业务线隔离）."""
    try:
        return await registry.catalogService.get_tree(bl_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{bl_id}/catalog",
    response_model=CatalogNode,
    status_code=status.HTTP_201_CREATED,
    summary="添加数据目录节点",
)
async def add_catalog_node(
    bl_id: str,
    req: AddCatalogNodeRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> CatalogNode:
    """添加数据目录节点（强制隔离：节点 blId 取路径 bl_id）."""
    import uuid

    node = CatalogNode(
        id=req.id or str(uuid.uuid4()),
        blId=bl_id,
        parentId=req.parentId,
        name=req.name,
        type=req.type,
        description=req.description,
        tags=req.tags,
    )
    try:
        return await registry.catalogService.add_node(node)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/{bl_id}/catalog/{node_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="删除数据目录节点",
)
async def delete_catalog_node(
    bl_id: str,
    node_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    """删除数据目录节点（递归删除子节点）."""
    try:
        await registry.catalogService.remove_node(bl_id, node_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
