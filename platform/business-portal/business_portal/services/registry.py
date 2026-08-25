"""服务注册表 - 根据配置构建 Mock 或 SQLite 实现并注入服务层.

设计模式：依赖注入 + 工厂。
配置开关：
    BP_STORE_TYPE=mock / sqlite
    BP_MLFLOW_ENABLED=true / false
        true 时构建 MLflowMetricsProvider 并注入 Mock/SqLite Store，
        jobCount/accuracy 从真实 MLflow 拉取，替换硬编码 120 / 0.875。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from business_portal.config.settings import Settings, get_settings
from business_portal.interfaces.store import (
    BusinessLineStore,
    CatalogStore,
    DashboardStore,
    ReportStore,
    WorkbenchStore,
)
from business_portal.services.business_line_service import BusinessLineService
from business_portal.services.catalog_service import CatalogService
from business_portal.services.dashboard_service import DashboardService
from business_portal.services.report_service import ReportService
from business_portal.services.workbench_service import WorkbenchService


@dataclass
class ServiceRegistry:
    """服务注册表，聚合所有仓储与服务."""

    settings: Settings
    blStore: BusinessLineStore
    dashboardStore: DashboardStore
    workbenchStore: WorkbenchStore
    catalogStore: CatalogStore
    reportStore: ReportStore
    businessLineService: BusinessLineService
    dashboardService: DashboardService
    workbenchService: WorkbenchService
    catalogService: CatalogService
    reportService: ReportService


def build_services(settings: Optional[Settings] = None) -> ServiceRegistry:
    """根据配置构建服务注册表.

    Args:
        settings: 配置，不传则使用全局单例。

    Returns:
        ServiceRegistry 实例。
    """
    if settings is None:
        settings = get_settings()

    # 可选构建 MLflow 指标提供者
    mlflowProvider = _build_mlflow_provider(settings)

    if settings.isMock:
        bl_store, dashboard_store, workbench_store, catalog_store, report_store = _build_mock(mlflowProvider)
    elif settings.isSqlite:
        bl_store, dashboard_store, workbench_store, catalog_store, report_store = _build_sqlite(
            settings.dbPath, mlflowProvider
        )
    else:
        # 兜底：未知类型回退 Mock
        bl_store, dashboard_store, workbench_store, catalog_store, report_store = _build_mock(mlflowProvider)

    bl_service = BusinessLineService(bl_store)
    dashboard_service = DashboardService(bl_store, dashboard_store)
    workbench_service = WorkbenchService(bl_store, workbench_store)
    catalog_service = CatalogService(bl_store, catalog_store)
    report_service = ReportService(bl_store, report_store)

    return ServiceRegistry(
        settings=settings,
        blStore=bl_store,
        dashboardStore=dashboard_store,
        workbenchStore=workbench_store,
        catalogStore=catalog_store,
        reportStore=report_store,
        businessLineService=bl_service,
        dashboardService=dashboard_service,
        workbenchService=workbench_service,
        catalogService=catalog_service,
        reportService=report_service,
    )


def _build_mlflow_provider(settings: Settings):
    """根据配置构建 MLflowMetricsProvider；未启用返回 None."""
    if not settings.mlflowEnabled:
        return None
    from business_portal.repositories.mlflow import MLflowMetricsProvider

    return MLflowMetricsProvider(trackingUri=settings.mlflowUri)


def _build_mock(
    mlflowProvider=None,
) -> tuple[
    BusinessLineStore,
    DashboardStore,
    WorkbenchStore,
    CatalogStore,
    ReportStore,
]:
    from business_portal.repositories.mock import (
        MockBusinessLineStore,
        MockCatalogStore,
        MockDashboardStore,
        MockReportStore,
        MockWorkbenchStore,
    )

    bl_store = MockBusinessLineStore(mlflowProvider=mlflowProvider)
    dashboard_store = MockDashboardStore(bl_store, mlflowProvider=mlflowProvider)
    workbench_store = MockWorkbenchStore()
    catalog_store = MockCatalogStore()
    report_store = MockReportStore()
    return (
        bl_store,
        dashboard_store,
        workbench_store,
        catalog_store,
        report_store,
    )


def _build_sqlite(
    db_path: str,
    mlflowProvider=None,
) -> tuple[
    BusinessLineStore,
    DashboardStore,
    WorkbenchStore,
    CatalogStore,
    ReportStore,
]:
    from business_portal.repositories.sqlite import (
        SQLiteBusinessLineStore,
        SQLiteCatalogStore,
        SQLiteConnection,
        SQLiteDashboardStore,
        SQLiteReportStore,
        SQLiteWorkbenchStore,
    )

    conn = SQLiteConnection(db_path)
    conn.init_schema()
    bl_store = SQLiteBusinessLineStore(conn)
    dashboard_store = SQLiteDashboardStore(bl_store)
    workbench_store = SQLiteWorkbenchStore(conn)
    catalog_store = SQLiteCatalogStore(conn)
    report_store = SQLiteReportStore(conn)
    return (
        bl_store,
        dashboard_store,
        workbench_store,
        catalog_store,
        report_store,
    )
