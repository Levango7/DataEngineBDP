"""模板相关路由.

端点：
    GET    /templates                列出所有模板（支持过滤）
    GET    /templates/{id}           模板详情
    POST   /templates/{id}/deploy    部署模板
    GET    /templates/{id}/preview   预览模板架构
"""

from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from industry_templates.api.routers.deps import get_registry, status_for_error
from industry_templates.models import DeploymentRequest
from industry_templates.services.exceptions import TemplateError
from industry_templates.services.registry import ServiceRegistry

router = APIRouter(prefix="/templates", tags=["templates"])


# ---------- 列表 ----------


@router.get("", summary="列出所有模板")
async def list_templates(
    industry: Optional[str] = Query(None, description="行业过滤"),
    keyword: Optional[str] = Query(None, description="关键字过滤"),
    status: Optional[str] = Query(None, description="状态过滤"),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[dict]:
    """列出所有模板，支持按行业/关键字/状态过滤.

    Returns:
        模板元信息列表
    """
    templates = registry.engine.list_templates(industry=industry, keyword=keyword, status=status)
    return [t.meta.model_dump() for t in templates]


# ---------- 详情 ----------


@router.get("/{template_id}", summary="模板详情")
async def get_template(
    template_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """获取模板详情（含数据流/计算逻辑/可视化）."""
    try:
        template = registry.engine.get_template(template_id)
    except TemplateError as e:
        raise HTTPException(status_code=status_for_error(e), detail=e.message)
    return template.model_dump()


# ---------- 部署 ----------


@router.post("/{template_id}/deploy", summary="部署模板", status_code=201)
async def deploy_template(
    template_id: str,
    request: DeploymentRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """部署模板：参数注入 + 一键部署.

    请求体：
        - tenantId: 租户 ID
        - releaseName: Helm release 名称
        - namespace: K8s namespace（可选）
        - values: 参数取值
        - datasourceBindings: 数据源绑定
    """
    try:
        record = registry.engine.deploy(template_id, request)
    except TemplateError as e:
        raise HTTPException(status_code=status_for_error(e), detail=e.message)
    return record.model_dump()


# ---------- 预览 ----------


@router.get("/{template_id}/preview", summary="预览模板架构")
async def preview_template(
    template_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """预览模板架构（不部署）.

    Returns:
        架构图 + 参数摘要 + 统计
    """
    try:
        preview = registry.engine.preview_template(template_id)
    except TemplateError as e:
        raise HTTPException(status_code=status_for_error(e), detail=e.message)
    return preview.model_dump()


# ---------- 部署记录 ----------


@router.get("/{template_id}/deployments", summary="列出模板的部署记录")
async def list_deployments(
    template_id: str,
    tenantId: Optional[str] = Query(None, description="租户 ID 过滤"),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[dict]:
    """列出指定模板的部署记录."""
    records = registry.engine.list_deployments(tenantId=tenantId)
    return [r.model_dump() for r in records if r.templateId == template_id]
