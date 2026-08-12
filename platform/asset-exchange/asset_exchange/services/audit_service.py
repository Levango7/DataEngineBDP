"""审计留痕业务逻辑.

对齐设计文档 §6 审计留痕：
- 全过程审计日志（登记/上架/流通/变现/分账）
- 不可篡改：基于哈希链
- 与 Phase 1 SecurityFacade T021 集成（通过 audit_facade_url 调用）

集成方式：
- 本地优先：直接写入本地 AuditRepository，保证留痕不丢失
- 远程同步：可选调用 SecurityFacade T021 远程审计服务（HTTP），失败不影响主流程
"""

from __future__ import annotations

from typing import Any, Optional

from asset_exchange.interfaces.audit_repository import AuditRepository
from asset_exchange.models.audit import (
    AuditIntegrityReport,
    AuditLog,
    AuditLogFilter,
)
from asset_exchange.models.base import AuditAction, AuditResult


class AuditService:
    """审计留痕服务.

    负责记录全过程审计日志，并维护哈希链不可篡改。
    """

    def __init__(
        self,
        audit_repo: AuditRepository,
        audit_facade_url: Optional[str] = None,
    ) -> None:
        self._audit_repo = audit_repo
        self._auditFacadeUrl = audit_facade_url

    async def log(
        self,
        action: AuditAction,
        actor_id: str,
        asset_id: Optional[str] = None,
        subscription_id: Optional[str] = None,
        settlement_id: Optional[str] = None,
        actor_role: Optional[str] = None,
        tenant_id: Optional[str] = None,
        result: AuditResult = AuditResult.SUCCESS,
        detail: Optional[dict[str, Any]] = None,
    ) -> AuditLog:
        """记录审计日志.

        自动维护哈希链：prevHash 取最新一条日志的 hash。

        Returns:
            已保存的审计日志（含 hash）。
        """
        log = AuditLog(
            action=action,
            assetId=asset_id,
            subscriptionId=subscription_id,
            settlementId=settlement_id,
            actorId=actor_id,
            actorRole=actor_role,
            tenantId=tenant_id,
            result=result,
            detail=detail or {},
        )
        log_id = await self._audit_repo.save(log)
        return await self._audit_repo.get(log_id)

    async def get(self, log_id: str) -> AuditLog:
        """获取审计日志."""
        return await self._audit_repo.get(log_id)

    async def list(self, filter: Optional[AuditLogFilter] = None) -> list[AuditLog]:
        """列出审计日志."""
        return await self._audit_repo.list(filter or AuditLogFilter())

    async def list_by_asset(self, asset_id: str) -> list[AuditLog]:
        """列出某资产的所有审计日志."""
        return await self._audit_repo.list_by_asset(asset_id)

    async def verify_integrity(self) -> AuditIntegrityReport:
        """校验审计日志哈希链完整性.

        Returns:
            完整性校验报告。
        """
        result = await self._audit_repo.verify_integrity()
        return AuditIntegrityReport(
            totalLogs=result["totalLogs"],
            verified=result["verified"],
            brokenAt=result.get("brokenAt"),
            message=result.get("message", "OK"),
        )
