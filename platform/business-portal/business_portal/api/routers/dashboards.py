"""BI 看板路由（/api/v1/dashboards，对齐前端 frontend/src/api/analyze.ts 契约）.

提供看板 CRUD 与实时指标查询。此前前端 analyze.ts 六个函数指向的
`/dashboards` 端点不存在（全平台无此路由），Analyze.vue 只能以 CSS 假图
撑门面——本路由补齐该缺口，看板数据默认内存存储（BP_BI_DASHBOARD_STORE
可切 sqlite），不与业务线概览 dashboardService 混淆。
"""

from __future__ import annotations

from typing import Optional
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field

from business_portal.api.routers.deps import get_registry
from business_portal.services.bi_dashboard_store import (
    BiDashboard,
    BiDashboardStore,
    DashboardNotFoundError,
)

router = APIRouter(prefix="/dashboards", tags=["bi-dashboards"])


class PanelModel(BaseModel):
    """看板组件."""

    id: str
    title: str
    type: str = Field(description="line | pie | bar | metric | funnel | table")
    config: dict = Field(default_factory=dict)
    data: Optional[dict] = None


class CreateDashboardRequest(BaseModel):
    """创建看板请求."""

    name: str = Field(min_length=1, max_length=256)
    description: Optional[str] = None
    panels: list[PanelModel] = Field(default_factory=list)


class UpdateDashboardRequest(BaseModel):
    """更新看板请求."""

    name: Optional[str] = Field(default=None, min_length=1, max_length=256)
    description: Optional[str] = None
    panels: Optional[list[PanelModel]] = None


class DashboardListResponse(BaseModel):
    """分页列表（PagedResult 契约：list/total/page/pageSize）."""

    list: list[BiDashboard]
    total: int
    page: int
    pageSize: int


def _store(registry) -> BiDashboardStore:
    """从 app.state 获取 BI 看板仓储（应用工厂挂载）."""
    store: Optional[BiDashboardStore] = getattr(registry, "biDashboardStore", None)
    if store is None:
        raise HTTPException(status_code=503, detail="BI 看板存储未初始化")
    return store


@router.get("", response_model=DashboardListResponse, summary="看板列表（分页）")
async def list_dashboards(
    page: int = Query(default=1, ge=1),
    pageSize: int = Query(default=20, ge=1, le=100),
    keyword: Optional[str] = Query(default=None),
    registry=Depends(get_registry),
) -> DashboardListResponse:
    """查询看板列表（keyword 对名称模糊过滤）."""
    items, total = await _store(registry).list(page=page, page_size=pageSize, keyword=keyword)
    return DashboardListResponse(list=items, total=total, page=page, pageSize=pageSize)


@router.get("/realtime", summary="实时指标（代理查询）")
async def realtime_metrics(registry=Depends(get_registry)) -> list[dict]:
    """查询实时指标（复用 mlflow 指标源，缺省返回空列表）."""
    provider = getattr(registry.settings, "metricsProvider", None)
    if provider is None:
        return []
    try:
        metrics = await provider.get_realtime_metrics()
    except Exception:  # noqa: BLE001 - 指标源不可用时降级为空
        return []
    return [
        {
            "key": m.key,
            "label": m.label,
            "value": m.value,
            "unit": m.unit,
            "latencySec": m.latencySec,
        }
        for m in metrics
    ]


@router.get("/{dashboard_id}", response_model=BiDashboard, summary="看板详情")
async def get_dashboard(
    dashboard_id: str,
    registry=Depends(get_registry),
) -> BiDashboard:
    try:
        return await _store(registry).get(dashboard_id)
    except DashboardNotFoundError:
        raise HTTPException(status_code=404, detail=f"看板不存在: {dashboard_id}")


@router.post("", response_model=BiDashboard, status_code=status.HTTP_201_CREATED, summary="创建看板")
async def create_dashboard(
    req: CreateDashboardRequest,
    registry=Depends(get_registry),
) -> BiDashboard:
    now = uuid.uuid4().hex[:8]  # 仅用于展示去重说明；真实时间戳在 store 层生成
    _ = now
    return await _store(registry).create(req.model_dump())


@router.put("/{dashboard_id}", response_model=BiDashboard, summary="更新看板")
async def update_dashboard(
    dashboard_id: str,
    req: UpdateDashboardRequest,
    registry=Depends(get_registry),
) -> BiDashboard:
    try:
        return await _store(registry).update(dashboard_id, req.model_dump(exclude_none=True))
    except DashboardNotFoundError:
        raise HTTPException(status_code=404, detail=f"看板不存在: {dashboard_id}")


@router.delete("/{dashboard_id}", status_code=status.HTTP_204_NO_CONTENT, summary="删除看板")
async def delete_dashboard(
    dashboard_id: str,
    registry=Depends(get_registry),
) -> None:
    try:
        await _store(registry).delete(dashboard_id)
    except DashboardNotFoundError:
        raise HTTPException(status_code=404, detail=f"看板不存在: {dashboard_id}")
