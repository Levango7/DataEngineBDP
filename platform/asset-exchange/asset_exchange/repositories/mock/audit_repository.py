"""Mock 审计仓储 - 内存字典实现，含哈希链."""
from __future__ import annotations

import hashlib
import json
import uuid
from typing import Any, Optional

from asset_exchange.interfaces.audit_repository import AuditRepository
from asset_exchange.models.audit import AuditLog, AuditLogFilter
from asset_exchange.models.base import utc_now


def _compute_hash(log: AuditLog) -> str:
    """计算审计日志哈希.

    hash = SHA256(prevHash + action + assetId + actorId + createdAt + detail_json)
    """
    payload = "|".join(
        [
            log.prevHash,
            log.action.value,
            log.assetId or "",
            log.subscriptionId or "",
            log.settlementId or "",
            log.actorId,
            log.actorRole or "",
            log.tenantId or "",
            log.result.value,
            log.createdAt.isoformat() if log.createdAt else "",
            json.dumps(log.detail, sort_keys=True, ensure_ascii=False),
        ]
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


class MockAuditRepository(AuditRepository):
    """内存字典审计仓储.

    维护哈希链：每条日志的 prevHash 取上一条日志的 hash，
    自身 hash 由 _compute_hash 计算。任何一条被篡改，
    verify_integrity 都能检测到。
    """

    def __init__(self) -> None:
        self._logs: dict[str, AuditLog] = {}
        # 维护插入顺序的 ID 列表（Python 3.7+ dict 已保序，但显式维护更清晰）
        self._order: list[str] = []

    async def save(self, log: AuditLog) -> str:
        if not log.id:
            log.id = str(uuid.uuid4())
        now = utc_now()
        if log.id not in self._logs:
            log.createdAt = now
        log.updatedAt = now
        # 维护哈希链
        if not log.prevHash:
            log.prevHash = await self.get_last_hash()
        log.hash = _compute_hash(log)
        self._logs[log.id] = log
        if log.id not in self._order:
            self._order.append(log.id)
        return log.id

    async def get(self, log_id: str) -> AuditLog:
        if log_id not in self._logs:
            raise KeyError(f"审计日志不存在: {log_id}")
        return self._logs[log_id]

    async def list(self, filter: AuditLogFilter) -> list[AuditLog]:
        result: list[AuditLog] = []
        for log_id in self._order:
            log = self._logs[log_id]
            if filter.assetId and log.assetId != filter.assetId:
                continue
            if filter.action and log.action != filter.action:
                continue
            if filter.actorId and log.actorId != filter.actorId:
                continue
            if filter.tenantId and log.tenantId != filter.tenantId:
                continue
            if filter.startTime and log.createdAt < filter.startTime:
                continue
            if filter.endTime and log.createdAt > filter.endTime:
                continue
            result.append(log)
        # 升序（按 createdAt）
        result.sort(key=lambda x: x.createdAt)
        return result[filter.offset : filter.offset + filter.limit]

    async def list_by_asset(self, asset_id: str) -> list[AuditLog]:
        result = [
            self._logs[log_id]
            for log_id in self._order
            if self._logs[log_id].assetId == asset_id
        ]
        result.sort(key=lambda x: x.createdAt)
        return result

    async def get_last_hash(self) -> str:
        if not self._order:
            return ""
        return self._logs[self._order[-1]].hash

    async def verify_integrity(self) -> dict[str, Any]:
        total = len(self._order)
        prev_hash = ""
        for log_id in self._order:
            log = self._logs[log_id]
            # 校验 prevHash 链
            if log.prevHash != prev_hash:
                return {
                    "totalLogs": total,
                    "verified": False,
                    "brokenAt": log_id,
                    "message": f"prevHash 不匹配：期望 {prev_hash[:16]}...，实际 {log.prevHash[:16]}...",
                }
            # 校验自身 hash
            expected = _compute_hash(log)
            if log.hash != expected:
                return {
                    "totalLogs": total,
                    "verified": False,
                    "brokenAt": log_id,
                    "message": f"hash 不匹配：期望 {expected[:16]}...，实际 {log.hash[:16]}...",
                }
            prev_hash = log.hash
        return {
            "totalLogs": total,
            "verified": True,
            "brokenAt": None,
            "message": "OK",
        }

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._logs.clear()
        self._order.clear()

    def __len__(self) -> int:
        return len(self._logs)