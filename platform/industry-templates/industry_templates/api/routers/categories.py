"""模板分类路由.

端点：
    GET /templates/categories  模板分类
"""
from __future__ import annotations

from fastapi import APIRouter, Depends

from industry_templates.api.routers.deps import get_registry
from industry_templates.services.registry import ServiceRegistry

router = APIRouter(prefix="/templates", tags=["categories"])


@router.get("/categories", summary="模板分类")
async def list_categories(
    registry: ServiceRegistry = Depends(get_registry),
) -> list[dict]:
    """列出模板分类（按行业聚合）.

    Returns:
        [{"industry": "finance", "name": "金融", "count": 1, "templates": [...]}]
    """
    return registry.engine.list_categories()