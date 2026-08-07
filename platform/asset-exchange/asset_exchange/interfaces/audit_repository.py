"""审计日志仓储抽象接口."""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Optional

from asset_exchange.models.audit import AuditLog, AuditLogFilter


class AuditRepository(ABC):
    """审计日志仓储抽象接口.

    职责：审计日志的持久化、查询、哈希链维护、完整性校验。
    """

    @abstractmethod
    async def save(self, log: AuditLog) -> str:
        """保存审计日志，返回 log_id.

        实现应维护哈希链：自动计算 prevHash 与 hash。
        """
        ...

    @abstractmethod
    async def get(self, log_id: str) -> AuditLog:
        """根据 ID 获取审计日志."""
        ...

    @abstractmethod
    async def list(self, filter: AuditLogFilter) -> list[AuditLog]:
        """按条件列出审计日志（按时间升序）."""
        ...

    @abstractmethod
    async def list_by_asset(self, asset_id: str) -> list[AuditLog]:
        """列出某资产的所有审计日志（按时间升序）."""
        ...

    @abstractmethod
    async def get_last_hash(self) -> str:
        """获取最新一条日志的哈希（用于哈希链延续）.

        Returns:
            最新日志的 hash，若无日志则返回空字符串。
        """
        ...

    @abstractmethod
    async def verify_integrity(self) -> dict[str, Any]:
        """校验审计日志哈希链完整性.

        Returns:
            {"totalLogs": int, "verified": bool, "brokenAt": Optional[str], "message": str}
        """
        ...