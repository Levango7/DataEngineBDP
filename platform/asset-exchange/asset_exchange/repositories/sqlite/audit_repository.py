"""SQLite 审计仓储 - 含哈希链不可篡改实现."""
from __future__ import annotations

import hashlib
import json
import uuid
from typing import Any, Optional

from asset_exchange.interfaces.audit_repository import AuditRepository
from asset_exchange.models.audit import AuditLog, AuditLogFilter
from asset_exchange.models.base import AuditAction, AuditResult, utc_now
from asset_exchange.repositories import AssetExchangeError
from asset_exchange.repositories.sqlite.connection import SQLiteConnection


def _compute_hash(log: AuditLog) -> str:
    """计算审计日志哈希.

    hash =6 = SHA256(prevHash + action + assetId + actorId + createdAt + detail_json)
    与 Mock 实现保持一致，便于跨存储校验。
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


class SQLiteAuditRepository(AuditRepository):
    """SQLite 审计仓储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn
        self._create_table()

    def _create_table(self) -> None:
        self._conn.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS audit_logs (
                id              TEXT PRIMARY KEY,
                action          TEXT NOT NULL,
                asset_id        TEXT,
                subscription_id TEXT,
                settlement_id   TEXT,
                actor_id        TEXT NOT NULL,
                actor_role      TEXT,
                tenant_id       TEXT,
                result          TEXT NOT NULL,
                detail_json     TEXT NOT NULL DEFAULT '{}',
                prev_hash       TEXT NOT NULL DEFAULT '',
                hash            TEXT NOT NULL DEFAULT '',
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            );
            """
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_audit_asset ON audit_logs(asset_id);"
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action);"
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_logs(actor_id);"
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_audit_tenant ON audit_logs(tenant_id);"
        )

    async def save(self, log: AuditLog) -> str:
        if not log.id:
            log.id = str(uuid.uuid4())
        now = utc_now()
        cur = self._conn.conn.execute(
            "SELECT id FROM audit_logs WHERE id = ?;", (log.id,)
        )
        existing = cur.fetchone()
        if existing is None:
            log.createdAt = now
        log.updatedAt = now
        # 维护哈希链
        if not log.prevHash:
            log.prevHash = await self.get_last_hash()
        log.hash = _compute_hash(log)
        self._conn.conn.execute(
            """
            INSERT INTO audit_logs (
                id, action, asset_id, subscription_id, settlement_id,
                actor_id, actor_role, tenant_id, result, detail_json,
                prev_hash, hash, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                action = excluded.action,
                asset_id = excluded.asset_id,
                subscription_id = excluded.subscription_id,
                settlement_id = excluded.settlement_id,
                actor_id = excluded.actor_id,
                actor_role = excluded.actor_role,
                tenant_id = excluded.tenant_id,
                result = excluded.result,
                detail_json = excluded.detail_json,
                prev_hash = excluded.prev_hash,
                hash = excluded.hash,
                updated_at = excluded.updated_at;
            """,
            (
                log.id,
                log.action.value,
                log.assetId,
                log.subscriptionId,
                log.settlementId,
                log.actorId,
                log.actorRole,
                log.tenantId,
                log.result.value,
                json.dumps(log.detail, ensure_ascii=False),
                log.prevHash,
                log.hash,
                log.createdAt.isoformat(),
                log.updatedAt.isoformat(),
            ),
        )
        return log.id

    async def get(self, log_id: str) -> AuditLog:
        cur = self._conn.conn.execute(
            "SELECT * FROM audit_logs WHERE id = ?;", (log_id,)
        )
        row = cur.fetchone()
        if row is None:
            raise AssetExchangeError(f"审计日志不存在: {log_id}")
        return self._row_to_log(row)

    async def list(self, filter: AuditLogFilter) -> list[AuditLog]:
        clauses: list[str] = []
        params: list[Any] = []
        if filter.assetId:
            clauses.append("asset_id = ?")
            params.append(filter.assetId)
        if filter.action:
            clauses.append("action = ?")
            params.append(filter.action.value)
        if filter.actorId:
            clauses.append("actor_id = ?")
            params.append(filter.actorId)
        if filter.tenantId:
            clauses.append("tenant_id = ?")
            params.append(filter.tenantId)
        if filter.startTime:
            clauses.append("created_at >= ?")
            params.append(filter.startTime.isoformat())
        if filter.endTime:
            clauses.append("created_at <= ?")
            params.append(filter.endTime.isoformat())
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = (
            f"SELECT * FROM audit_logs{where} ORDER BY created_at ASC "
            f"LIMIT ? OFFSET ?;"
        )
        params.extend([filter.limit, filter.offset])
        cur = self._conn.conn.execute(sql, params)
        return [self._row_to_log(r) for r in cur.fetchall()]

    async def list_by_asset(self, asset_id: str) -> list[AuditLog]:
        cur = self._conn.conn.execute(
            "SELECT * FROM audit_logs WHERE asset_id = ? "
            "ORDER BY created_at ASC;",
            (asset_id,),
        )
        return [self._row_to_log(r) for r in cur.fetchall()]

    async def get_last_hash(self) -> str:
        cur = self._conn.conn.execute(
            "SELECT hash FROM audit_logs ORDER BY created_at DESC LIMIT 1;"
        )
        row = cur.fetchone()
        return row["hash"] if row else ""

    async def verify_integrity(self) -> dict[str, Any]:
        cur = self._conn.conn.execute(
            "SELECT * FROM audit_logs ORDER BY created_at ASC;"
        )
        rows = cur.fetchall()
        total = len(rows)
        prev_hash = ""
        for row in rows:
            log = self._row_to_log(row)
            if log.prevHash != prev_hash:
                return {
                    "totalLogs": total,
                    "verified": False,
                    "brokenAt": log.id,
                    "message": "prevHash 不匹配",
                }
            expected = _compute_hash(log)
            if log.hash != expected:
                return {
                    "totalLogs": total,
                    "verified": False,
                    "brokenAt": log.id,
                    "message": "hash 不匹配",
                }
            prev_hash = log.hash
        return {
            "totalLogs": total,
            "verified": True,
            "brokenAt": None,
            "message": "OK",
        }

    @staticmethod
    def _row_to_log(row) -> AuditLog:
        return AuditLog(
            id=row["id"],
            action=AuditAction(row["action"]),
            assetId=row["asset_id"],
            subscriptionId=row["subscription_id"],
            settlementId=row["settlement_id"],
            actorId=row["actor_id"],
            actorRole=row["actor_role"],
            tenantId=row["tenant_id"],
            result=AuditResult(row["result"]),
            detail=json.loads(row["detail_json"]),
            prevHash=row["prev_hash"],
            hash=row["hash"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )