"""合同管理仓储层骨架.

P-04 合同交付实体 — 数据持久化抽象。

当前为内存存储骨架，生产环境应替换为 PostgreSQL/MySQL 实现。
"""
from __future__ import annotations

import logging
from typing import Any

from ..models.contract import Contract, ContractStatus

logger = logging.getLogger(__name__)


class ContractRepository:
    """合同仓储（内存骨架）.

    TODO: 替换为 PostgreSQL 持久化实现
    TODO: 添加租户隔离查询索引
    TODO: 添加合同编号唯一性约束
    TODO: 添加乐观锁（version 字段）支持并发更新
    """

    def __init__(self) -> None:
        # 内存存储：{contract_id: Contract}
        self._store: dict[str, Contract] = {}
        # 合同编号索引：{contract_no: contract_id}
        self._no_index: dict[str, str] = {}

    async def create(self, contract: Contract) -> Contract:
        """创建合同.

        TODO: 添加事务支持
        TODO: 添加合同编号唯一性校验
        """
        if contract.contractNo in self._no_index:
            raise ValueError(f"合同编号已存在: {contract.contractNo}")
        self._store[contract.id] = contract
        self._no_index[contract.contractNo] = contract.id
        logger.info("合同已创建: id=%s, no=%s, tenant=%s", contract.id, contract.contractNo, contract.tenantId)
        return contract

    async def get_by_id(self, contract_id: str) -> Contract | None:
        """按 ID 查询合同."""
        return self._store.get(contract_id)

    async def get_by_no(self, contract_no: str) -> Contract | None:
        """按合同编号查询合同."""
        cid = self._no_index.get(contract_no)
        if cid is None:
            return None
        return self._store.get(cid)

    async def list_by_tenant(
        self,
        tenant_id: str,
        status: ContractStatus | None = None,
        offset: int = 0,
        limit: int = 20,
    ) -> list[Contract]:
        """按租户查询合同列表.

        TODO: 添加分页优化（数据库层 LIMIT/OFFSET）
        TODO: 添加排序支持（按创建时间/到期时间等）
        """
        results = [
            c for c in self._store.values()
            if c.tenantId == tenant_id and (status is None or c.status == status)
        ]
        return results[offset : offset + limit]

    async def update(self, contract_id: str, updates: dict[str, Any]) -> Contract | None:
        """更新合同.

        TODO: 添加乐观锁校验
        TODO: 添加字段白名单过滤
        """
        contract = self._store.get(contract_id)
        if contract is None:
            return None
        # 部分更新
        updated = contract.model_copy(update=updates)
        from datetime import datetime, timezone
        updated.updatedAt = datetime.now(timezone.utc)
        self._store[contract_id] = updated
        logger.info("合同已更新: id=%s", contract_id)
        return updated

    async def delete(self, contract_id: str) -> bool:
        """删除合同（软删除，标记为 terminated）.

        TODO: 改为软删除标记（is_deleted 字段），而非物理删除
        """
        contract = self._store.get(contract_id)
        if contract is None:
            return False
        # 软删除：标记为终止
        await self.update(contract_id, {"status": ContractStatus.TERMINATED})
        logger.info("合同已终止: id=%s", contract_id)
        return True

    async def count_by_tenant(self, tenant_id: str) -> int:
        """统计租户合同数量."""
        return sum(1 for c in self._store.values() if c.tenantId == tenant_id)


# 全局单例（骨架阶段使用，生产环境应通过依赖注入）
contract_repo = ContractRepository()