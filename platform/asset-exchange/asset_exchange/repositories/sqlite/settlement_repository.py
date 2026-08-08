"""SQLite 结算仓储."""

from __future__ import annotations

import json
from typing import Any, Optional
import uuid

from asset_exchange.interfaces.settlement_repository import (
    SettlementRepository,
)
from asset_exchange.models.base import SettlementStatus, utc_now
from asset_exchange.models.settlement import Settlement, SettlementFilter
from asset_exchange.repositories import AssetExchangeError
from asset_exchange.repositories.sqlite.connection import SQLiteConnection


class SQLiteSettlementRepository(SettlementRepository):
    """SQLite 结算仓储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn
        self._create_table()

    def _create_table(self) -> None:
        self._conn.conn.execute("""
            CREATE TABLE IF NOT EXISTS settlements (
                id                  TEXT PRIMARY KEY,
                asset_id            TEXT NOT NULL,
                tenant_id           TEXT NOT NULL,
                period              TEXT NOT NULL,
                status              TEXT NOT NULL,
                total_amount        REAL NOT NULL DEFAULT 0,
                provider_revenue    REAL NOT NULL DEFAULT 0,
                platform_revenue    REAL NOT NULL DEFAULT 0,
                billing_record_ids_json TEXT NOT NULL DEFAULT '[]',
                provider_share      REAL NOT NULL DEFAULT 0.8,
                platform_share      REAL NOT NULL DEFAULT 0.2,
                settled_at          TEXT,
                error_message       TEXT,
                created_at          TEXT NOT NULL,
                updated_at          TEXT NOT NULL
            );
            """)
        self._conn.conn.execute("CREATE INDEX IF NOT EXISTS idx_settle_asset ON settlements(asset_id);")
        self._conn.conn.execute("CREATE INDEX IF NOT EXISTS idx_settle_period ON settlements(period);")
        self._conn.conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_settle_asset_period " "ON settlements(asset_id, period);"
        )

    async def save(self, settlement: Settlement) -> str:
        if not settlement.id:
            settlement.id = str(uuid.uuid4())
        now = utc_now()
        cur = self._conn.conn.execute("SELECT id FROM settlements WHERE id = ?;", (settlement.id,))
        existing = cur.fetchone()
        if existing is None:
            settlement.createdAt = now
        settlement.updatedAt = now
        self._conn.conn.execute(
            """
            INSERT INTO settlements (
                id, asset_id, tenant_id, period, status,
                total_amount, provider_revenue, platform_revenue,
                billing_record_ids_json, provider_share, platform_share,
                settled_at, error_message, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                asset_id = excluded.asset_id,
                tenant_id = excluded.tenant_id,
                period = excluded.period,
                status = excluded.status,
                total_amount = excluded.total_amount,
                provider_revenue = excluded.provider_revenue,
                platform_revenue = excluded.platform_revenue,
                billing_record_ids_json = excluded.billing_record_ids_json,
                provider_share = excluded.provider_share,
                platform_share = excluded.platform_share,
                settled_at = excluded.settled_at,
                error_message = excluded.error_message,
                updated_at = excluded.updated_at;
            """,
            (
                settlement.id,
                settlement.assetId,
                settlement.tenantId,
                settlement.period,
                settlement.status.value,
                settlement.totalAmount,
                settlement.providerRevenue,
                settlement.platformRevenue,
                json.dumps(settlement.billingRecordIds),
                settlement.providerShare,
                settlement.platformShare,
                settlement.settledAt.isoformat() if settlement.settledAt else None,
                settlement.errorMessage,
                settlement.createdAt.isoformat(),
                settlement.updatedAt.isoformat(),
            ),
        )
        return settlement.id

    async def get(self, settlement_id: str) -> Settlement:
        cur = self._conn.conn.execute("SELECT * FROM settlements WHERE id = ?;", (settlement_id,))
        row = cur.fetchone()
        if row is None:
            raise AssetExchangeError(f"结算记录不存在: {settlement_id}")
        return self._row_to_settlement(row)

    async def list(self, filter: SettlementFilter) -> list[Settlement]:
        clauses: list[str] = []
        params: list[Any] = []
        if filter.assetId:
            clauses.append("asset_id = ?")
            params.append(filter.assetId)
        if filter.tenantId:
            clauses.append("tenant_id = ?")
            params.append(filter.tenantId)
        if filter.period:
            clauses.append("period = ?")
            params.append(filter.period)
        if filter.status:
            clauses.append("status = ?")
            params.append(filter.status.value)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = f"SELECT * FROM settlements{where} ORDER BY created_at DESC LIMIT ? OFFSET ?;"
        params.extend([filter.limit, filter.offset])
        cur = self._conn.conn.execute(sql, params)
        return [self._row_to_settlement(r) for r in cur.fetchall()]

    async def list_by_asset(self, asset_id: str) -> list[Settlement]:
        cur = self._conn.conn.execute(
            "SELECT * FROM settlements WHERE asset_id = ? " "ORDER BY created_at DESC;",
            (asset_id,),
        )
        return [self._row_to_settlement(r) for r in cur.fetchall()]

    async def find_by_asset_period(self, asset_id: str, period: str) -> Optional[Settlement]:
        cur = self._conn.conn.execute(
            "SELECT * FROM settlements WHERE asset_id = ? AND period = ? LIMIT 1;",
            (asset_id, period),
        )
        row = cur.fetchone()
        return self._row_to_settlement(row) if row else None

    async def update(self, settlement_id: str, **fields: Any) -> Settlement:
        s = await self.get(settlement_id)
        for k, v in fields.items():
            if hasattr(s, k):
                setattr(s, k, v)
        s.updatedAt = utc_now()
        await self.save(s)
        return s

    @staticmethod
    def _row_to_settlement(row) -> Settlement:
        return Settlement(
            id=row["id"],
            assetId=row["asset_id"],
            tenantId=row["tenant_id"],
            period=row["period"],
            status=SettlementStatus(row["status"]),
            totalAmount=row["total_amount"],
            providerRevenue=row["provider_revenue"],
            platformRevenue=row["platform_revenue"],
            billingRecordIds=json.loads(row["billing_record_ids_json"]),
            providerShare=row["provider_share"],
            platformShare=row["platform_share"],
            settledAt=row["settled_at"],
            errorMessage=row["error_message"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )
