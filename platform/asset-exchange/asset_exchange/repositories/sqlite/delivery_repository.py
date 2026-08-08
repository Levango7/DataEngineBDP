"""SQLite 交付仓储."""

from __future__ import annotations

import json
from typing import Any, Optional
import uuid

from asset_exchange.interfaces.delivery_repository import DeliveryRepository
from asset_exchange.models.base import DeliveryMethod, DeliveryStatus, utc_now
from asset_exchange.models.delivery import Delivery
from asset_exchange.repositories import DeliveryNotFoundError
from asset_exchange.repositories.sqlite.connection import SQLiteConnection


class SQLiteDeliveryRepository(DeliveryRepository):
    """SQLite 交付仓储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn
        self._create_table()

    def _create_table(self) -> None:
        self._conn.conn.execute("""
            CREATE TABLE IF NOT EXISTS deliveries (
                id              TEXT PRIMARY KEY,
                subscription_id TEXT NOT NULL,
                method          TEXT NOT NULL,
                config_json     TEXT NOT NULL DEFAULT '{}',
                status          TEXT NOT NULL,
                artifact_url    TEXT,
                artifact_meta_json TEXT NOT NULL DEFAULT '{}',
                data_rows       INTEGER NOT NULL DEFAULT 0,
                data_bytes      INTEGER NOT NULL DEFAULT 0,
                started_at      TEXT,
                finished_at     TEXT,
                error_message   TEXT,
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            );
            """)
        self._conn.conn.execute("CREATE INDEX IF NOT EXISTS idx_deliveries_sub ON deliveries(subscription_id);")
        self._conn.conn.execute("CREATE INDEX IF NOT EXISTS idx_deliveries_status ON deliveries(status);")

    async def save(self, delivery: Delivery) -> str:
        if not delivery.id:
            delivery.id = str(uuid.uuid4())
        now = utc_now()
        cur = self._conn.conn.execute("SELECT id FROM deliveries WHERE id = ?;", (delivery.id,))
        existing = cur.fetchone()
        if existing is None:
            delivery.createdAt = now
        delivery.updatedAt = now
        self._conn.conn.execute(
            """
            INSERT INTO deliveries (
                id, subscription_id, method, config_json, status,
                artifact_url, artifact_meta_json, data_rows, data_bytes,
                started_at, finished_at, error_message, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                subscription_id = excluded.subscription_id,
                method = excluded.method,
                config_json = excluded.config_json,
                status = excluded.status,
                artifact_url = excluded.artifact_url,
                artifact_meta_json = excluded.artifact_meta_json,
                data_rows = excluded.data_rows,
                data_bytes = excluded.data_bytes,
                started_at = excluded.started_at,
                finished_at = excluded.finished_at,
                error_message = excluded.error_message,
                updated_at = excluded.updated_at;
            """,
            (
                delivery.id,
                delivery.subscriptionId,
                delivery.method.value,
                json.dumps(delivery.config),
                delivery.status.value,
                delivery.artifactUrl,
                json.dumps(delivery.artifactMeta),
                delivery.dataRows,
                delivery.dataBytes,
                delivery.startedAt.isoformat() if delivery.startedAt else None,
                delivery.finishedAt.isoformat() if delivery.finishedAt else None,
                delivery.errorMessage,
                delivery.createdAt.isoformat(),
                delivery.updatedAt.isoformat(),
            ),
        )
        return delivery.id

    async def get(self, delivery_id: str) -> Delivery:
        cur = self._conn.conn.execute("SELECT * FROM deliveries WHERE id = ?;", (delivery_id,))
        row = cur.fetchone()
        if row is None:
            raise DeliveryNotFoundError(delivery_id)
        return self._row_to_delivery(row)

    async def get_by_subscription(self, subscription_id: str) -> Optional[Delivery]:
        cur = self._conn.conn.execute(
            "SELECT * FROM deliveries WHERE subscription_id = ? " "ORDER BY created_at DESC LIMIT 1;",
            (subscription_id,),
        )
        row = cur.fetchone()
        return self._row_to_delivery(row) if row else None

    async def update(self, delivery_id: str, **fields: Any) -> Delivery:
        d = await self.get(delivery_id)
        for k, v in fields.items():
            if hasattr(d, k):
                setattr(d, k, v)
        d.updatedAt = utc_now()
        await self.save(d)
        return d

    async def list_by_subscription(self, subscription_id: str) -> list[Delivery]:
        cur = self._conn.conn.execute(
            "SELECT * FROM deliveries WHERE subscription_id = ? " "ORDER BY created_at DESC;",
            (subscription_id,),
        )
        return [self._row_to_delivery(r) for r in cur.fetchall()]

    @staticmethod
    def _row_to_delivery(row) -> Delivery:
        return Delivery(
            id=row["id"],
            subscriptionId=row["subscription_id"],
            method=DeliveryMethod(row["method"]),
            config=json.loads(row["config_json"]),
            status=DeliveryStatus(row["status"]),
            artifactUrl=row["artifact_url"],
            artifactMeta=json.loads(row["artifact_meta_json"]),
            dataRows=row["data_rows"],
            dataBytes=row["data_bytes"],
            startedAt=row["started_at"],
            finishedAt=row["finished_at"],
            errorMessage=row["error_message"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )
