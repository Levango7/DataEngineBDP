"""SQLite 分账仓储."""
from __future__ import annotations

import uuid
from typing import Any, Optional

from asset_exchange.interfaces.allocation_repository import (
    AllocationRepository,
)
from asset_exchange.models.base import AllocationStatus, utc_now
from asset_exchange.models.settlement import Allocation, AllocationFilter
from asset_exchange.repositories import AssetExchangeError
from asset_exchange.repositories.sqlite.connection import SQLiteConnection


class SQLiteAllocationRepository(AllocationRepository):
    """SQLite 分账仓储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn
        self._create_table()

    def _create_table(self) -> None:
        self._conn.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS allocations (
                id                  TEXT PRIMARY KEY,
                settlement_id       TEXT NOT NULL,
                asset_id            TEXT NOT NULL,
                status              TEXT NOT NULL,
                provider_amount     REAL NOT NULL DEFAULT 0,
                platform_amount     REAL NOT NULL DEFAULT 0,
                provider_account_id TEXT,
                platform_account_id TEXT,
                allocated_at        TEXT,
                error_message       TEXT,
                created_at          TEXT NOT NULL,
                updated_at          TEXT NOT NULL
            );
            """
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_alloc_asset ON allocations(asset_id);"
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_alloc_settle ON allocations(settlement_id);"
        )

    async def save(self, allocation: Allocation) -> str:
        if not allocation.id:
            allocation.id = str(uuid.uuid4())
        now = utc_now()
        cur = self._conn.conn.execute(
            "SELECT id FROM allocations WHERE id = ?;", (allocation.id,)
        )
        existing = cur.fetchone()
        if existing is None:
            allocation.createdAt = now
        allocation.updatedAt = now
        self._conn.conn.execute(
            """
            INSERT INTO allocations (
                id, settlement_id, asset_id, status,
                provider_amount, platform_amount,
                provider_account_id, platform_account_id,
                allocated_at, error_message, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                settlement_id = excluded.settlement_id,
                asset_id = excluded.asset_id,
                status = excluded.status,
                provider_amount = excluded.provider_amount,
                platform_amount = excluded.platform_amount,
                provider_account_id = excluded.provider_account_id,
                platform_account_id = excluded.platform_account_id,
                allocated_at = excluded.allocated_at,
                error_message = excluded.error_message,
                updated_at = excluded.updated_at;
            """,
            (
                allocation.id,
                allocation.settlementId,
                allocation.assetId,
                allocation.status.value,
                allocation.providerAmount,
                allocation.platformAmount,
                allocation.providerAccountId,
                allocation.platformAccountId,
                allocation.allocatedAt.isoformat()
                if allocation.allocatedAt
                else None,
                allocation.errorMessage,
                allocation.createdAt.isoformat(),
                allocation.updatedAt.isoformat(),
            ),
        )
        return allocation.id

    async def get(self, allocation_id: str) -> Allocation:
        cur = self._conn.conn.execute(
            "SELECT * FROM allocations WHERE id = ?;", (allocation_id,)
        )
        row = cur.fetchone()
        if row is None:
            raise AssetExchangeError(f"分账记录不存在: {allocation_id}")
        return self._row_to_allocation(row)

    async def list(self, filter: AllocationFilter) -> list[Allocation]:
        clauses: list[str] = []
        params: list[Any] = []
        if filter.assetId:
            clauses.append("asset_id = ?")
            params.append(filter.assetId)
        if filter.settlementId:
            clauses.append("settlement_id = ?")
            params.append(filter.settlementId)
        if filter.status:
            clauses.append("status = ?")
            params.append(filter.status.value)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = f"SELECT * FROM allocations{where} ORDER BY created_at DESC LIMIT ? OFFSET ?;"
        params.extend([filter.limit, filter.offset])
        cur = self._conn.conn.execute(sql, params)
        return [self._row_to_allocation(r) for r in cur.fetchall()]

    async def list_by_asset(self, asset_id: str) -> list[Allocation]:
        cur = self._conn.conn.execute(
            "SELECT * FROM allocations WHERE asset_id = ? "
            "ORDER BY created_at DESC;",
            (asset_id,),
        )
        return [self._row_to_allocation(r) for r in cur.fetchall()]

    async def list_by_settlement(
        self, settlement_id: str
    ) -> list[Allocation]:
        cur = self._conn.conn.execute(
            "SELECT * FROM allocations WHERE settlement_id = ? "
            "ORDER BY created_at DESC;",
            (settlement_id,),
        )
        return [self._row_to_allocation(r) for r in cur.fetchall()]

    async def update(self, allocation_id: str, **fields: Any) -> Allocation:
        a = await self.get(allocation_id)
        for k, v in fields.items():
            if hasattr(a, k):
                setattr(a, k, v)
        a.updatedAt = utc_now()
        await self.save(a)
        return a

    @staticmethod
    def _row_to_allocation(row) -> Allocation:
        return Allocation(
            id=row["id"],
            settlementId=row["settlement_id"],
            assetId=row["asset_id"],
            status=AllocationStatus(row["status"]),
            providerAmount=row["provider_amount"],
            platformAmount=row["platform_amount"],
            providerAccountId=row["provider_account_id"],
            platformAccountId=row["platform_account_id"],
            allocatedAt=row["allocated_at"],
            errorMessage=row["error_message"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )