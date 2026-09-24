"""出账闭环结算路由 - 对接 finops 账单执行结算.

出账闭环第二环节（结算）：
    POST   /api/v1/settlements          基于账单 ID 触发结算（对接 finops）
    GET    /api/v1/settlements           查询结算列表
    GET    /api/v1/settlements/{id}      查询结算详情

数据流：finops-billing(出账) → asset-exchange(结算) → open-api-catalog(分账)
"""

from __future__ import annotations

from typing import Optional
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from asset_exchange.api.jwt_auth import AuthContext, getAuthContext
from asset_exchange.api.routers.deps import get_registry
from asset_exchange.config.settings import Settings
from asset_exchange.models.base import SettlementStatus, utc_now
from asset_exchange.models.settlement import (
    BillingSettlementRequest,
    BillingSettlementResponse,
    Settlement,
    SettlementFilter,
)
from asset_exchange.services.finops_client import FinOpsBillingClient, FinOpsBillingError
from asset_exchange.services.registry import ServiceRegistry

router = APIRouter(prefix="/settlements", tags=["出账闭环-结算"])

_SHARE_SUM_TOLERANCE = 1e-9


@router.post(
    "",
    response_model=BillingSettlementResponse,
    status_code=status.HTTP_201_CREATED,
    summary="基于 finops 账单触发结算",
)
async def settleByBilling(
    req: BillingSettlementRequest,
    request: Request,
    ctx: AuthContext = Depends(getAuthContext),
    registry: ServiceRegistry = Depends(get_registry),
) -> BillingSettlementResponse:
    """基于 finops 账单 ID 触发结算.

    流程：
    1. 调用 finops-billing API 获取账单详情（含总金额与账期）
    2. 按分成比例计算提供方收益与平台抽成
    3. 创建结算记录，状态置为 SETTLED
    4. 返回结算结果

    租户 ID 从 JWT claim 获取，不信任请求参数。
    """
    tenantId = ctx.tenantId
    if not tenantId:
        raise HTTPException(status_code=401, detail="缺少租户上下文")

    settings: Settings = request.app.state.settings
    finopsClient = FinOpsBillingClient(settings)

    # 透传 JWT 给 finops（租户隔离）
    authHeader = request.headers.get("Authorization", "")
    jwtToken = authHeader[len("Bearer ") :] if authHeader.startswith("Bearer ") else None

    # 1. 拉取 finops 账单
    try:
        billing = await finopsClient.getBilling(req.billingId, jwtToken)
    except FinOpsBillingError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc

    billingAmount = float(billing.get("totalAmount", 0))
    billingPeriod = billing.get("billingPeriod", "")
    billingTenant = billing.get("tenantId", "")

    # 租户隔离校验：账单租户须与当前租户一致（admin 可跨租户）
    if ctx.role != "admin" and billingTenant and billingTenant != tenantId:
        raise HTTPException(
            status_code=403,
            detail=f"账单租户({billingTenant})与当前身份({tenantId})不一致",
        )

    # 2. 分成比例
    providerShare = req.providerShare if req.providerShare is not None else settings.providerShare
    platformShare = req.platformShare if req.platformShare is not None else settings.platformShare
    if providerShare < 0 or platformShare < 0:
        raise HTTPException(
            status_code=422,
            detail=f"分成比例非法: providerShare={providerShare}, platformShare={platformShare}，均须 >= 0",
        )
    if abs(providerShare + platformShare - 1.0) > _SHARE_SUM_TOLERANCE:
        raise HTTPException(
            status_code=422,
            detail=f"分成比例非法: providerShare({providerShare}) + platformShare({platformShare}) 须等于 1",
        )

    # 3. 计算结算金额
    providerRevenue = round(billingAmount * providerShare, 2)
    platformRevenue = round(billingAmount * platformShare, 2)

    # 4. 创建结算记录
    settlement = Settlement(
        id=str(uuid.uuid4()),
        assetId=req.assetId or "",
        tenantId=tenantId,
        period=billingPeriod,
        status=SettlementStatus.SETTLED,
        totalAmount=billingAmount,
        providerRevenue=providerRevenue,
        platformRevenue=platformRevenue,
        billingRecordIds=[req.billingId],
        providerShare=providerShare,
        platformShare=platformShare,
        settledAt=utc_now(),
    )
    settlementId = await registry.settlementRepo.save(settlement)
    saved = await registry.settlementRepo.get(settlementId)

    return BillingSettlementResponse(
        settlementId=saved.id,
        billingId=req.billingId,
        tenantId=tenantId,
        billingPeriod=billingPeriod,
        billingAmount=billingAmount,
        totalAmount=saved.totalAmount,
        providerRevenue=saved.providerRevenue,
        platformRevenue=saved.platformRevenue,
        status=saved.status.value,
        settledAt=saved.settledAt,
        errorMessage=saved.errorMessage,
    )


@router.get(
    "",
    summary="查询结算列表",
)
async def listSettlements(
    assetId: Optional[str] = Query(default=None, description="按资产过滤"),
    period: Optional[str] = Query(default=None, description="按账期过滤"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    ctx: AuthContext = Depends(getAuthContext),
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """查询当前租户的结算列表."""
    tenantId = ctx.tenantId
    if not tenantId:
        raise HTTPException(status_code=401, detail="缺少租户上下文")

    filter = SettlementFilter(
        tenantId=tenantId,
        assetId=assetId,
        period=period,
        limit=limit,
        offset=offset,
    )
    settlements = await registry.settlementRepo.list(filter)
    return {
        "tenant": tenantId,
        "count": len(settlements),
        "settlements": [
            {
                "id": s.id,
                "assetId": s.assetId,
                "period": s.period,
                "status": s.status.value,
                "totalAmount": s.totalAmount,
                "providerRevenue": s.providerRevenue,
                "platformRevenue": s.platformRevenue,
                "settledAt": s.settledAt,
            }
            for s in settlements
        ],
    }


@router.get(
    "/{settlement_id}",
    summary="查询结算详情",
)
async def getSettlement(
    settlement_id: str,
    ctx: AuthContext = Depends(getAuthContext),
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """查询结算详情."""
    tenantId = ctx.tenantId
    if not tenantId:
        raise HTTPException(status_code=401, detail="缺少租户上下文")

    try:
        settlement = await registry.settlementRepo.get(settlement_id)
    except Exception as exc:
        raise HTTPException(status_code=404, detail=f"结算记录不存在: {settlement_id}") from exc

    # 租户隔离
    if ctx.role != "admin" and settlement.tenantId != tenantId:
        raise HTTPException(status_code=403, detail="无权查看此结算记录")

    return {
        "id": settlement.id,
        "assetId": settlement.assetId,
        "tenantId": settlement.tenantId,
        "period": settlement.period,
        "status": settlement.status.value,
        "totalAmount": settlement.totalAmount,
        "providerRevenue": settlement.providerRevenue,
        "platformRevenue": settlement.platformRevenue,
        "billingRecordIds": settlement.billingRecordIds,
        "providerShare": settlement.providerShare,
        "platformShare": settlement.platformShare,
        "settledAt": settlement.settledAt,
        "errorMessage": settlement.errorMessage,
    }
