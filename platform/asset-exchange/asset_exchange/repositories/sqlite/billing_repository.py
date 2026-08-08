"""SQLite 计费仓储."""

from __future__ import annotations

import uuid

from asset_exchange.interfaces.billing_repository import BillingRepository
from asset_exchange.models.base import BillingMode, utc_now
from asset_exchange.models.billing import BillingRecord
from asset_exchange.repositories import AssetExchangeError
from asset_exchange.repositories.sqlite.connection import SQLiteConnection


class SQLiteBillingRepository(BillingRepository):
    """SQLite 计费仓储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn
        self._create_table()

    def _create_table(self) -> None:
        self._conn.conn.execute("""
            CREATE TABLE IF NOT EXISTS billing_records (
                id                  TEXT PRIMARY KEY,
                subscription_id     TEXT NOT NULL,
                asset_id            TEXT NOT NULL,
                subscriber_id       TEXT NOT NULL,
                owner               TEXT NOT NULL,
                mode                TEXT NOT NULL,
                usage               REAL NOT NULL DEFAULT 0,
                unit                TEXT NOT NULL DEFAULT '次',
                unit_price          REAL NOT NULL DEFAULT 0,
                amount              REAL NOT NULL DEFAULT 0,
                provider_revenue    REAL NOT NULL DEFAULT 0,
                platform_revenue    REAL NOT NULL DEFAULT 0,
                period              TEXT NOT NULL,
                is_internal         INTEGER NOT NULL DEFAULT 0,
                delivery_id         TEXT,
                created_at          TEXT NOT NULL,
                updated_at          TEXT NOT NULL
            );
            """)
        self._conn.conn.execute("CREATE INDEX IF NOT EXISTS idx_billing_asset ON billing_records(asset_id);")
        self._conn.conn.execute("CREATE INDEX IF NOT EXISTS idx_billing_sub ON billing_records(subscription_id);")

    async def save(self, record: BillingRecord) -> str:
        if not record.id:
            record.id = str(uuid.uuid4())
        now = utc_now()
        cur = self._conn.conn.execute("SELECT id FROM billing_records WHERE id = ?;", (record.id,))
        existing = cur.fetchone()
        if existing is None:
            record.createdAt = now
        record.updatedAt = now
        self._conn.conn.execute(
            """
            INSERT INTO billing_records (
                id, subscription_id, asset_id, subscriber_id, owner,
                mode, usage, unit, unit_price, amount, provider_revenue,
                platform_revenue, period, is_internal, delivery_id,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                subscription_id = excluded.subscription_id,
                asset_id = excluded.asset_id,
                subscriber_id = excluded.subscriber_id,
                owner = excluded.owner,
                mode = excluded.mode,
                usage = excluded.usage,
                unit = excluded.unit,
                unit_price = excluded.unit_price,
                amount = excluded.amount,
                provider_revenue = excluded.provider_revenue,
                platform_revenue = excluded.platform_revenue,
                period = excluded.period,
                is_internal = excluded.is_internal,
                delivery_id = excluded.delivery_id,
                updated_at = excluded.updated_at;
            """,
            (
                record.id,
                record.subscriptionId,
                record.assetId,
                record.subscriberId,
                record.tenantId,
                record.mode.value,
                record.usage,
                record.unit,
                record.unitPrice,
                record.amount,
                record.providerRevenue,
                record.platformRevenue,
                record.period,
                1 if record.isInternal else 0,
                record.deliveryId,
                record.createdAt.isoformat(),
                record.updatedAt.isoformat(),
            ),
        )
        return record.id

    async def get(self, record_id: str) -> BillingRecord:
        cur = self._conn.conn.execute("SELECT * FROM billing_records WHERE id = ?;", (record_id,))
        row = cur.fetchone()
        if row is None:
            raise AssetExchangeError(f"计费记录不存在: {record_id}")
        return self._row_to_record(row)

    async def list_by_asset(self, asset_id: str) -> list[BillingRecord]:
        cur = self._conn.conn.execute(
            "SELECT * FROM billing_records WHERE asset_id = ? " "ORDER BY created_at DESC;",
            (asset_id,),
        )
        return [self._row_to_record(r) for r in cur.fetchall()]

    async def list_by_subscription(self, subscription_id: str) -> list[BillingRecord]:
        cur = self._conn.conn.execute(
            "SELECT * FROM billing_records WHERE subscription_id = ? " "ORDER BY created_at DESC;",
            (subscription_id,),
        )
        return [self._row_to_record(r) for r in cur.fetchall()]

    async def sum_by_asset(self, asset_id: str) -> dict[str, float]:
        cur = self._conn.conn.execute(
            """
            SELECT
                COALESCE(SUM(amount), 0) AS total_amount,
                COALESCE(SUM(provider_revenue), 0) AS total_provider,
                COALESCE(SUM(platform_revenue), 0) AS total_platform
            FROM billing_records WHERE asset_id = ?;
            """,
            (asset_id,),
        )
        row = cur.fetchone()
        return {
            "totalAmount": float(row["total_amount"]),
            "totalProviderRevenue": float(row["total_provider"]),
            "totalPlatformRevenue": float(row["total_platform"]),
        }

    @staticmethod
    def _row_to_record(row) -> BillingRecord:
        return BillingRecord(
            id=row["id"],
            subscriptionId=row["subscription_id"],
            assetId=row["asset_id"],
            subscriberId=row["subscriber_id"],
            # SQL 列名 owner 保留（数据库内部结构）；
            # 映射到统一的 tenantId 字段（MODEL-2）
            tenantId=row["owner"],
            mode=BillingMode(row["mode"]),
            usage=row["usage"],
            unit=row["unit"],
            unitPrice=row["unit_price"],
            amount=row["amount"],
            providerRevenue=row["provider_revenue"],
            platformRevenue=row["platform_revenue"],
            period=row["period"],
            isInternal=bool(row["is_internal"]),
            deliveryId=row["delivery_id"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )
