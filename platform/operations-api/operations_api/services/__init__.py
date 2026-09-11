"""合同管理服务层骨架.

P-04 合同交付实体 — 业务逻辑骨架。

本模块定义合同管理的业务逻辑接口，当前为骨架实现，
完整逻辑待后续 Sprint 补充。
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timezone
from typing import Any
from uuid import uuid4

from ..models.contract import (
    Contract,
    ContractCreateRequest,
    ContractStatus,
    ContractType,
    ContractUpdateRequest,
)
from ..repositories import contract_repo

logger = logging.getLogger(__name__)


class ContractService:
    """合同管理服务.

    TODO: 添加合同审批流程
    TODO: 添加合同到期自动提醒
    TODO: 添加合同与租户/套餐联动逻辑
    TODO: 添加合同签署电子签章集成
    TODO: 添加合同金额与账单关联
    """

    async def create_contract(self, req: ContractCreateRequest) -> Contract:
        """创建合同.

        TODO: 校验租户是否存在
        TODO: 校验套餐是否有效
        TODO: 校验日期合法性（startDate < endDate）
        TODO: 生成合同编号（如未提供）
        """
        # 日期合法性校验
        if req.startDate >= req.endDate:
            raise ValueError("合同生效日期必须早于到期日期")

        contract = Contract(
            id=str(uuid4()),
            tenantId=req.tenantId,
            contractNo=req.contractNo,
            partyA=req.partyA,
            partyB=req.partyB,
            startDate=req.startDate,
            endDate=req.endDate,
            status=ContractStatus.DRAFT,
            amount=req.amount,
            type=req.type,
            package=req.package,
            terms=req.terms,
            attachments=req.attachments,
        )
        return await contract_repo.create(contract)

    async def get_contract(self, contract_id: str) -> Contract | None:
        """查询合同详情."""
        return await contract_repo.get_by_id(contract_id)

    async def list_contracts(
        self,
        tenant_id: str,
        status: ContractStatus | None = None,
        offset: int = 0,
        limit: int = 20,
    ) -> list[Contract]:
        """查询合同列表."""
        return await contract_repo.list_by_tenant(tenant_id, status, offset, limit)

    async def update_contract(
        self, contract_id: str, req: ContractUpdateRequest
    ) -> Contract | None:
        """更新合同.

        TODO: 添加状态机校验（如生效合同不可改金额）
        TODO: 添加审批权限校验
        """
        # 过滤 None 值，仅更新提供的字段
        updates: dict[str, Any] = {
            k: v for k, v in req.model_dump().items() if v is not None
        }
        if not updates:
            return await contract_repo.get_by_id(contract_id)

        # 日期合法性校验
        contract = await contract_repo.get_by_id(contract_id)
        if contract is None:
            return None
        new_start = updates.get("startDate", contract.startDate)
        new_end = updates.get("endDate", contract.endDate)
        if new_start >= new_end:
            raise ValueError("合同生效日期必须早于到期日期")

        return await contract_repo.update(contract_id, updates)

    async def sign_contract(
        self, contract_id: str, approved_by: str
    ) -> Contract | None:
        """签署合同（草稿 → 生效）.

        TODO: 添加电子签章集成
        TODO: 添加签署前审批流程校验
        TODO: 签署后触发租户套餐开通
        """
        contract = await contract_repo.get_by_id(contract_id)
        if contract is None:
            return None
        if contract.status != ContractStatus.DRAFT:
            raise ValueError(f"合同状态非草稿，无法签署: 当前状态={contract.status}")

        return await contract_repo.update(
            contract_id,
            {
                "status": ContractStatus.ACTIVE,
                "signedAt": datetime.now(timezone.utc),
                "approvedBy": approved_by,
            },
        )

    async def terminate_contract(self, contract_id: str) -> Contract | None:
        """终止合同.

        TODO: 添加终止原因记录
        TODO: 添加终止后资源回收逻辑
        TODO: 添加终止审批流程
        """
        contract = await contract_repo.get_by_id(contract_id)
        if contract is None:
            return None
        if contract.status not in (ContractStatus.ACTIVE, ContractStatus.SUSPENDED):
            raise ValueError(f"合同状态不可终止: 当前状态={contract.status}")

        return await contract_repo.update(
            contract_id, {"status": ContractStatus.TERMINATED}
        )

    async def check_expired(self) -> int:
        """检查到期合同并更新状态.

        TODO: 定时任务调用（每日执行）
        TODO: 到期前 N 天发送提醒
        TODO: 到期后触发续约流程

        Returns:
            更新为到期状态的合同数量
        """
        today = date.today()
        count = 0
        # TODO: 遍历所有生效合同，检查是否到期
        logger.info("合同到期检查完成，更新 %d 份合同", count)
        return count


# 全局单例
contract_service = ContractService()