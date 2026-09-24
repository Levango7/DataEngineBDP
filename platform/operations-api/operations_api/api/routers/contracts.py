"""合同管理 CRUD API 路由.

P-04 合同交付实体 — 合同 CRUD API 骨架。

端点清单：
    POST   /api/v1/contracts              创建合同（admin）
    GET    /api/v1/contracts               查询合同列表（按 JWT 租户）
    GET    /api/v1/contracts/{id}          查询合同详情
    PUT    /api/v1/contracts/{id}          更新合同（admin）
    DELETE /api/v1/contracts/{id}          终止合同（admin）
    POST   /api/v1/contracts/{id}/sign     签署合同（admin）
    POST   /api/v1/contracts/{id}/terminate 终止合同（admin）

鉴权：所有端点要求 Bearer JWT；写操作（创建/更新/签署/终止）要求 admin 角色。
租户隔离：list_contracts 的 tenantId 从 JWT claims 提取，admin 可通过 query param 覆盖。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ...jwt_auth import AuthContext, effectiveTenant, getAuthContext, requireAdmin
from ...models.contract import (
    ContractCreateRequest,
    ContractResponse,
    ContractStatus,
    ContractUpdateRequest,
)
from ...services import contract_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/contracts", tags=["合同管理"])


@router.post("", response_model=ContractResponse, status_code=status.HTTP_201_CREATED)
async def create_contract(
    req: ContractCreateRequest,
    ctx: AuthContext = Depends(getAuthContext),
):
    """创建合同（要求 admin 角色）."""
    requireAdmin(ctx)
    try:
        contract = await contract_service.create_contract(req)
        return ContractResponse(data=contract)
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        logger.error("创建合同失败: %s", e, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="创建合同失败",
        )


@router.get("", response_model=list)
async def list_contracts(
    ctx: AuthContext = Depends(getAuthContext),
    tenantId: str | None = Query(default=None, description="租户 ID（仅 admin 可指定，普通用户强制取 JWT）"),
    contractStatus: ContractStatus | None = Query(default=None, description="合同状态"),
    offset: int = Query(default=0, ge=0, description="分页偏移"),
    limit: int = Query(default=20, ge=1, le=100, description="每页数量"),
):
    """查询合同列表.

    tenantId 来源裁决：
      - 普通用户：强制使用 JWT claims 中的 tenantId（忽略 query param）
      - admin：可指定任意 tenantId query param，缺省回退到 JWT claims
    """
    effective_tenant_id = effectiveTenant(ctx, tenantId)
    if not effective_tenant_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="无法确定租户 ID：JWT claims 中未声明 tenantId 且未提供 query param",
        )
    contracts = await contract_service.list_contracts(
        tenant_id=effective_tenant_id,
        status=contractStatus,
        offset=offset,
        limit=limit,
    )
    return contracts


@router.get("/{contract_id}", response_model=ContractResponse)
async def get_contract(
    contract_id: str,
    ctx: AuthContext = Depends(getAuthContext),
):
    """查询合同详情."""
    contract = await contract_service.get_contract(contract_id)
    if contract is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"合同不存在: {contract_id}",
        )
    # 租户隔离校验：非 admin 只能查本租户合同
    if ctx.role != "admin" and contract.tenantId != ctx.tenantId:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"合同不存在: {contract_id}",
        )
    return ContractResponse(data=contract)


@router.put("/{contract_id}", response_model=ContractResponse)
async def update_contract(
    contract_id: str,
    req: ContractUpdateRequest,
    ctx: AuthContext = Depends(getAuthContext),
):
    """更新合同（要求 admin 角色）."""
    requireAdmin(ctx)
    try:
        contract = await contract_service.update_contract(contract_id, req)
        if contract is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"合同不存在: {contract_id}",
            )
        return ContractResponse(data=contract)
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))


@router.delete("/{contract_id}", response_model=ContractResponse)
async def terminate_contract(
    contract_id: str,
    ctx: AuthContext = Depends(getAuthContext),
):
    """终止合同（要求 admin 角色）."""
    requireAdmin(ctx)
    try:
        contract = await contract_service.terminate_contract(contract_id)
        if contract is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"合同不存在: {contract_id}",
            )
        return ContractResponse(data=contract)
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))


@router.post("/{contract_id}/sign", response_model=ContractResponse)
async def sign_contract(
    contract_id: str,
    ctx: AuthContext = Depends(getAuthContext),
    approvedBy: str | None = Query(default=None, description="审批人（缺省取 JWT subject）"),
):
    """签署合同（草稿 → 生效，要求 admin 角色）."""
    requireAdmin(ctx)
    approver = approvedBy or ctx.userId
    try:
        contract = await contract_service.sign_contract(contract_id, approver)
        if contract is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"合同不存在: {contract_id}",
            )
        return ContractResponse(data=contract)
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))


@router.post("/{contract_id}/terminate", response_model=ContractResponse)
async def terminate_contract_explicit(
    contract_id: str,
    ctx: AuthContext = Depends(getAuthContext),
):
    """显式终止合同（要求 admin 角色）."""
    requireAdmin(ctx)
    try:
        contract = await contract_service.terminate_contract(contract_id)
        if contract is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"合同不存在: {contract_id}",
            )
        return ContractResponse(data=contract)
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
