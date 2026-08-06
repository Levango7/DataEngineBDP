"""SQLite 订阅仓储."""
from __future__ import annotations

import json
import uuid
from typing import Any

from asset_exchange.interfaces.subscription_repository import (
    SubscriptionRepository,
)
from asset_exchange.models.base import SubscriptionStatus, utc_now
from asset_exchange.models.subscription import Subscription, SubscriptionFilter
from asset_exchange.repositories import SubscriptionNotFoundError
from asset_exchange.repositories.sqlite.connection import SQLiteConnection


class SQLiteSubscriptionRepository(SubscriptionRepository):
    """SQLite 订阅仓储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn
        self._create_table()

    def _create_table(self) -> None:
        self._conn.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS subscriptions (
                id              TEXT PRIMARY KEY,
                asset_id        TEXT NOT NULL,
                subscriber_id   TEXT NOT NULL,
                status          TEXT NOT NULL,
                start_time      TEXT,
                end_time        TEXT,
                period          TEXT,
                pull_config_json TEXT NOT NULL DEFAULT '{}',
                approver_id     TEXT,
                approved_at     TEXT,
                reject_reason   TEXT,
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            );
            """
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_subs_asset ON subscriptions(asset_id);"
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_subs_subscriber ON subscriptions(subscriber_id);"
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_subs_status ON subscriptions(status);"
        )

    async def save(self, subscription: Subscription) -> str:
        if not subscription.id:
            subscription.id = str(uuid.uuid4())
        now = utc_now()
        cur = self._conn.conn.execute(
            "SELECT id FROM subscriptions WHERE id = ?;", (subscription.id,)
        )
        existing = cur.fetchone()
        if existing is None:
            subscription.createdAt = now
        subscription.updatedAt = now
        self._conn.conn.execute(
            """
            INSERT INTO subscriptions (
                id, asset_id, subscriber_id, status, start_time, end_time,
                period, pull_config_json, approver_id, approved_at, reject_reason,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                asset_id = excluded.asset_id,
                subscriber_id = excluded.subscriber_id,
                status = excluded.status,
                start_time = excluded.start_time,
                end_time = excluded.end_time,
                period = excluded.period,
                pull_config_json = excluded.pull_config_json,
                approver_id = excluded.approver_id,
                approved_at = excluded.approved_at,
                reject_reason = excluded.reject_reason,
                updated_at = excluded.updated_at;
            """,
            (
                subscription.id,
                subscription.assetId,
                subscription.subscriberId,
                subscription.status.value,
                subscription.startTime.isoformat() if subscription.startTime else None,
                subscription.endTime.isoformat() if subscription.endTime else None,
                subscription.period,
                json.dumps(subscription.pullConfig),
                subscription.approverId,
                subscription.approvedAt.isoformat() if subscription.approvedAt else None,
                subscription.rejectReason,
                subscription.createdAt.isoformat(),
                subscription.updatedAt.isoformat(),
            ),
        )
        return subscription.id

    async def get(self, subscription_id: str) -> Subscription:
        cur = self._conn.conn.execute(
            "SELECT * FROM subscriptions WHERE id = ?;", (subscription_id,)
        )
        row = cur.fetchone()
        if row is None:
            raise SubscriptionNotFoundError(subscription_id)
        return self._row_to_sub(row)

    async def list(self, filter: SubscriptionFilter) -> list[Subscription]:
        clauses: list[str] = []
        params: list[Any] = []
        if filter.assetId:
            clauses.append("asset_id = ?")
            params.append(filter.assetId)
        if filter.subscriberId:
            clauses.append("subscriber_id = ?")
            params.append(filter.subscriberId)
        if filter.status:
            clauses.append("status = ?")
            params.append(filter.status.value)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = f"SELECT * FROM subscriptions{where} ORDER BY created_at DESC LIMIT ? OFFSET ?;"
        params.extend([filter.limit, filter.offset])
        cur = self._conn.conn.execute(sql, params)
        return [self._row_to_sub(r) for r in cur.fetchall()]

    async def update(self, subscription_id: str, **fields: Any) -> Subscription:
        s = await self.get(subscription_id)
        for k, v in fields.items():
            if hasattr(s, k):
                setattr(s, k, v)
        s.updatedAt = utc_now()
        await self.save(s)
        return s

    async def list_by_asset(self, asset_id: str) -> list[Subscription]:
        cur = self._conn.conn.execute(
            "SELECT * FROM subscriptions WHERE asset_id = ? ORDER BY created_at DESC;",
            (asset_id,),
        )
        return [self._row_to_sub(r) for r in cur.fetchall()]

    @staticmethod
    def _row_to_sub(row) -> Subscription:
        return Subscription(
            id=row["id"],
            assetId=row["asset_id"],
            subscriberId=row["subscriber_id"],
            status=SubscriptionStatus(row["status"]),
            startTime=row["start_time"],
            endTime=row["end_time"],
            period=row["period"],
            pullConfig=json.loads(row["pull_config_json"]),
            approverId=row["approver_id"],
            approvedAt=row["approved_at"],
            rejectReason=row["reject_reason"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )