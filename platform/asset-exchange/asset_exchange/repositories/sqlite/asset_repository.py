"""SQLite 资产仓储."""
from __future__ import annotations

import json
import uuid
from typing import Any

from asset_exchange.interfaces.asset_repository import AssetRepository
from asset_exchange.models.asset import Asset, AssetFilter, AssetPricing, AssetSchema
from asset_exchange.models.base import AssetStatus, AssetType, SecurityLevel, utc_now
from asset_exchange.repositories import (
    AssetAlreadyExistsError,
    AssetNotFoundError,
)
from asset_exchange.repositories.sqlite.connection import SQLiteConnection


class SQLiteAssetRepository(AssetRepository):
    """SQLite 资产仓储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn
        self._create_table()

    def _create_table(self) -> None:
        self._conn.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS assets (
                id              TEXT PRIMARY KEY,
                name            TEXT NOT NULL UNIQUE,
                type            TEXT NOT NULL,
                owner           TEXT NOT NULL,
                description     TEXT,
                status          TEXT NOT NULL,
                quality_score   REAL NOT NULL DEFAULT 0,
                security_level  TEXT NOT NULL,
                schema_json     TEXT NOT NULL DEFAULT '{}',
                sample_json     TEXT,
                update_frequency TEXT NOT NULL DEFAULT 'static',
                tags_json       TEXT NOT NULL DEFAULT '{}',
                pricing_json    TEXT NOT NULL DEFAULT '{}',
                source_ref      TEXT,
                subscriber_count INTEGER NOT NULL DEFAULT 0,
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            );
            """
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_assets_owner ON assets(owner);"
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_assets_status ON assets(status);"
        )
        self._conn.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_assets_type ON assets(type);"
        )

    async def save(self, asset: Asset) -> str:
        if not asset.id:
            asset.id = str(uuid.uuid4())
        now = utc_now()
        # 检查是否已存在
        cur = self._conn.conn.execute(
            "SELECT id, name FROM assets WHERE id = ?;", (asset.id,)
        )
        existing = cur.fetchone()
        # 同名校验（新增时）
        if existing is None:
            cur2 = self._conn.conn.execute(
                "SELECT id FROM assets WHERE name = ?;", (asset.name,)
            )
            if cur2.fetchone() is not None:
                raise AssetAlreadyExistsError(asset.name)
            asset.createdAt = now
        asset.updatedAt = now
        self._conn.conn.execute(
            """
            INSERT INTO assets (
                id, name, type, owner, description, status,
                quality_score, security_level, schema_json, sample_json,
                update_frequency, tags_json, pricing_json, source_ref,
                subscriber_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                type = excluded.type,
                owner = excluded.owner,
                description = excluded.description,
                status = excluded.status,
                quality_score = excluded.quality_score,
                security_level = excluded.security_level,
                schema_json = excluded.schema_json,
                sample_json = excluded.sample_json,
                update_frequency = excluded.update_frequency,
                tags_json = excluded.tags_json,
                pricing_json = excluded.pricing_json,
                source_ref = excluded.source_ref,
                subscriber_count = excluded.subscriber_count,
                updated_at = excluded.updated_at;
            """,
            (
                asset.id,
                asset.name,
                asset.type.value,
                asset.tenantId,
                asset.description,
                asset.status.value,
                asset.qualityScore,
                asset.securityLevel.value,
                asset.schema.model_dump_json(),
                json.dumps(asset.sample) if asset.sample is not None else None,
                asset.updateFrequency,
                json.dumps(asset.tags),
                asset.pricing.model_dump_json(),
                asset.sourceRef,
                asset.subscriberCount,
                asset.createdAt.isoformat(),
                asset.updatedAt.isoformat(),
            ),
        )
        return asset.id

    async def get(self, asset_id: str) -> Asset:
        cur = self._conn.conn.execute(
            "SELECT * FROM assets WHERE id = ?;", (asset_id,)
        )
        row = cur.fetchone()
        if row is None:
            raise AssetNotFoundError(asset_id)
        return self._row_to_asset(row)

    async def list(self, filter: AssetFilter) -> list[Asset]:
        clauses: list[str] = []
        params: list[Any] = []
        if filter.name:
            clauses.append("name LIKE ?")
            params.append(f"%{filter.name}%")
        if filter.type:
            clauses.append("type = ?")
            params.append(filter.type.value)
        if filter.status:
            clauses.append("status = ?")
            params.append(filter.status.value)
        if filter.securityLevel:
            clauses.append("security_level = ?")
            params.append(filter.securityLevel.value)
        if filter.tenantId:
            clauses.append("owner = ?")
            params.append(filter.tenantId)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = f"SELECT * FROM assets{where} ORDER BY created_at DESC LIMIT ? OFFSET ?;"
        params.extend([filter.limit, filter.offset])
        cur = self._conn.conn.execute(sql, params)
        return [self._row_to_asset(r) for r in cur.fetchall()]

    async def delete(self, asset_id: str) -> None:
        cur = self._conn.conn.execute(
            "DELETE FROM assets WHERE id = ?;", (asset_id,)
        )
        if cur.rowcount == 0:
            raise AssetNotFoundError(asset_id)

    async def update(self, asset_id: str, **fields: Any) -> Asset:
        a = await self.get(asset_id)
        # 名称变更需校验唯一
        if "name" in fields and fields["name"] != a.name:
            cur = self._conn.conn.execute(
                "SELECT id FROM assets WHERE name = ? AND id != ?;",
                (fields["name"], asset_id),
            )
            if cur.fetchone() is not None:
                raise AssetAlreadyExistsError(fields["name"])
        for k, v in fields.items():
            if hasattr(a, k):
                setattr(a, k, v)
        a.updatedAt = utc_now()
        await self.save(a)
        return a

    @staticmethod
    def _row_to_asset(row) -> Asset:
        return Asset(
            id=row["id"],
            name=row["name"],
            type=AssetType(row["type"]),
            # SQL 列名 owner 保留（数据库内部结构）；
            # 映射到统一的 tenantId 字段（MODEL-2）
            tenantId=row["owner"],
            description=row["description"],
            status=AssetStatus(row["status"]),
            qualityScore=row["quality_score"],
            securityLevel=SecurityLevel(row["security_level"]),
            schema=AssetSchema.model_validate_json(row["schema_json"]),
            sample=json.loads(row["sample_json"]) if row["sample_json"] else None,
            updateFrequency=row["update_frequency"],
            tags=json.loads(row["tags_json"]),
            pricing=AssetPricing.model_validate_json(row["pricing_json"]),
            sourceRef=row["source_ref"],
            subscriberCount=row["subscriber_count"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )