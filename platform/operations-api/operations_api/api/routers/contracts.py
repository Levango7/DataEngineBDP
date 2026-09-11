"""合同管理 CRUD API 路由.

P-04 合同交付实体 — 合同 CRUD API 骨架。

端点清单：
    POST   /api/v1/contracts              创建合同
    GET    /api/v1/contracts               查询合同列表（按租户）
    GET    /api/v1/contracts/{id}          查询合同详情
    PUT    /api/v1/contracts/{id}          更新合同
    DELETE /api/v1/contracts/{id}          终止合同
    POST   /api/v1/contracts/{id}/sign     签署合同
    POST   /api/v1/contracts/{id}/terminate 终止合同

TODO: 添加 Bearer Token 鉴权（复用 operations main.py 的 verify_admin_token）
TODO: 添加租户权限校验
TODO: 添加请求限流
TODO: 添加操作审计日志
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ...models.contract import (
    ContractCreateRequest,
    ContractResponse,
    ContractStatus,
    ContractUpdateRequest,
)
from ...services import contract_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/contracts", tags=["合同管理"])


# TODO: 添加鉴权依赖（复用 operations main.py 的 verify_admin_token）
# async def verify_admin_token(...): ...


@router.post("", response_model=ContractResponse, status_code=status.HTTP_201_CREATED)
async def create_contract(req: ContractCreateRequest):
    """创建合同.

    TODO: 添加鉴权
    TODO: 添加租户存在性校验
    """
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
    tenantId: str = Query(..., description="租户 ID"),
    contractStatus: ContractStatus | None = Query(default=None, description="合同状态"),
    offset: int = Query(default=0, ge=0, description="分页偏移"),
    limit: int = Query(default=20, ge=1, le=100, description="每页数量"),
):
    """查询合同列表.

    TODO: 添加鉴权
    TODO: 添加排序参数
    """
    contracts = await contract_service.list_contracts(
        tenant_id=tenantId,
        status=contractStatus,
        offset=offset,
        limit=limit,
    )
    return contracts


@router.get("/{contract_id}", response_model=ContractResponse)
async def get_contract(contract_id: str):
    """查询合同详情.

    TODO: 添加鉴权
    TODO: 添加租户隔离校验
    """
    contract = await contract_service.get_contract(contract_id)
    if contract is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"合同不存在: {contract_id}",
        )
    return ContractResponse(data=contract)


@router.put("/{contract_id}", response_model=ContractResponse)
async def update_contract(contract_id: str, req: ContractUpdateRequest):
    """更新合同.

    TODO: 添加鉴权
    TODO: 添加状态机校验
    """
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
async def terminate_contract(contract_id: str):
    """终止合同.

    TODO: 添加鉴权
    TODO: 添加终止审批流程
    """
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
async def sign_contract(contract_id: str, approvedBy: str = Query(..., description="审批人")):
    """签署合同（草稿 → 生效）.

    TODO: 添加鉴权
    TODO: 添加电子签章集成
    """
    try:
        contract = await contract_service.sign_contract(contract_id, approvedBy)
        if contract is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"合同不存在: {contract_id}",
            )
        return ContractResponse(data=contract)
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))


@router.post("/{contract_id}/terminate", response_model=ContractResponse)
async def terminate_contract_explicit(contract_id: str):
    """显式终止合同.

    TODO: 添加鉴权
    TODO: 添加终止原因记录
    """
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