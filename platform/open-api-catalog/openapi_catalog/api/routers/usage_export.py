"""出账闭环计量汇入路由 - 导出 API 调用量到 finops.

出账闭环第三环节（分账/计量汇入）：
    POST /api/v1/billing/export-usage   导出 API 调用量到 finops

数据流：open-api-catalog(计量汇入) → finops-billing(出账) → asset-exchange(结算)

将 AK/SK 计量数据（API 调用量、调用费用）按租户聚合后汇入 finops，
使 API 调用成本纳入出账闭环账单。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from openapi_catalog.api.jwt_auth import AuthContext, getAuthContext
from openapi_catalog.api.routers.deps import get_registry
from openapi_catalog.config.settings import Settings
from openapi_catalog.models import APIFilter
from openapi_catalog.services.finops_exporter import FinOpsExportError, FinOpsUsageExporter
from openapi_catalog.services.registry import ServiceRegistry

router = APIRouter(prefix="/billing", tags=["出账闭环-计量汇入"])


class ExportUsageRequest(BaseModel):
    """导出 API 调用量请求."""

    period: Optional[str] = Field(
        default=None,
        description="账期（如 2026-08），不传则取当前年月",
    )
    range: str = Field(
        default="30d",
        description="聚合时间范围（如 7d/30d/1h），用于查询 API 调用计量",
    )


class UsageItem(BaseModel):
    """用量明细项."""

    apiId: str = Field(..., description="API ID")
    apiName: str = Field(default="", description="API 名称")
    callCount: int = Field(..., description="调用次数")
    totalCost: float = Field(..., description="总费用（元）")
    totalTrafficBytes: int = Field(..., description="总流量（bytes）")


class ExportUsageResponse(BaseModel):
    """导出 API 调用量响应."""

    tenantId: str = Field(..., description="租户 ID")
    period: str = Field(..., description="账期")
    exportedAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    totalApis: int = Field(..., description="涉及 API 数")
    totalCalls: int = Field(..., description="总调用次数")
    totalCost: float = Field(..., description="总费用（元）")
    items: list[UsageItem] = Field(default_factory=list, description="按 API 聚合的用量明细")
    finopsBillingId: Optional[str] = Field(
        default=None,
        description="finops 账单 ID（导出成功后回填）",
    )
    finopsTotalAmount: Optional[float] = Field(
        default=None,
        description="finops 账单总金额（导出成功后回填）",
    )
    status: str = Field(..., description="导出状态：SUCCESS / FAILED")


@router.post(
    "/export-usage",
    response_model=ExportUsageResponse,
    status_code=status.HTTP_200_OK,
    summary="导出 API 调用量到 finops（计量汇入）",
)
async def exportUsage(
    req: ExportUsageRequest,
    request: Request,
    ctx: AuthContext = Depends(getAuthContext),
    registry: ServiceRegistry = Depends(get_registry),
) -> ExportUsageResponse:
    """导出 API 调用量计量数据到 finops.

    流程：
    1. 列出当前租户的全部 API
    2. 对每个 API 聚合指定时间范围内的调用计量（次数/费用/流量）
    3. 汇总租户级 API 调用成本
    4. 调用 finops billing 服务触发账单生成
    5. 返回导出结果（含 finops 账单 ID）

    租户 ID 从 JWT claim 获取，不信任请求参数。
    """
    tenantId = ctx.tenantId
    if not tenantId:
        raise HTTPException(status_code=401, detail="缺少租户上下文")

    settings: Settings = request.app.state.settings

    # 1. 确定账期
    period = req.period or datetime.now(timezone.utc).strftime("%Y-%m")

    # 2. 列出当前租户的 API
    apis = await registry.apiRegistryService.list_apis(APIFilter(providerTenantId=tenantId))

    # 3. 聚合每个 API 的计量数据
    items: list[UsageItem] = []
    totalCalls = 0
    totalCost = 0.0

    for api in apis:
        try:
            metrics = await registry.meteringService.get_metrics(api.id, range_str=req.range, consumer_tenant_id=None)
        except Exception:
            # 单个 API 计量查询失败不影响整体导出
            metrics = None

        if metrics and metrics.callCount > 0:
            items.append(
                UsageItem(
                    apiId=api.id,
                    apiName=api.name,
                    callCount=metrics.callCount,
                    totalCost=metrics.totalCost,
                    totalTrafficBytes=metrics.totalTrafficBytes,
                )
            )
            totalCalls += metrics.callCount
            totalCost += metrics.totalCost

    # 4. 导出到 finops（触发账单生成）
    finopsBillingId = None
    finopsTotalAmount = None
    exportStatus = "SUCCESS"

    if totalCalls > 0:
        exporter = FinOpsUsageExporter(settings)
        authHeader = request.headers.get("Authorization", "")
        jwtToken = authHeader[len("Bearer ") :] if authHeader.startswith("Bearer ") else None

        try:
            result = await exporter.exportUsage(
                tenantId=tenantId,
                period=period,
                usageData=[item.model_dump() for item in items],
                jwtToken=jwtToken,
            )
            finopsBillingId = result.get("id")
            finopsTotalAmount = result.get("totalAmount")
        except FinOpsExportError as exc:
            # finops 导出失败不阻断计量聚合结果返回，标记状态为 FAILED
            exportStatus = "FAILED"
    else:
        # 无调用计量，跳过导出
        exportStatus = "SUCCESS"

    return ExportUsageResponse(
        tenantId=tenantId,
        period=period,
        totalApis=len(items),
        totalCalls=totalCalls,
        totalCost=round(totalCost, 4),
        items=items,
        finopsBillingId=finopsBillingId,
        finopsTotalAmount=finopsTotalAmount,
        status=exportStatus,
    )
