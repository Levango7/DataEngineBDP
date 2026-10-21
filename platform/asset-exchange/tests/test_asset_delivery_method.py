"""deliveryMethod 从"装饰件"变成真字段（台账 #81 第二项，主理人裁「让他变得有用」）。

改前的形态（现查）：前端 `AssetMarket.vue:367-372` 的下拉已经在发这个键，
而 asset-exchange 后端 `grep deliveryMethod` **0 命中**——模型里根本没有这个字段，
请求 DTO 又不禁止未知键（pydantic 默认 `extra='ignore'`），
于是"选了 database_direct"被静默丢掉：201 照给、响应键集里没这个键、库里也没这一列。

本文件钉四件事：
1. 请求体里的值真的进模型、真的回在响应键集里（HTTP 面）；
2. 非法值不能悄悄落成默认值，必须 422；
3. 值真的落到 SQLite 列上——用**重新打开的另一个连接**读回来，证明不是内存对象的错觉；
4. 已落盘的老库（没有这一列）能被幂等 ALTER 补上，且老行读出合法默认值。

第 4 条是本轮顺出来的更大问题：本模块此前只有 `CREATE TABLE IF NOT EXISTS`，
**没有任何 ALTER/迁移路径**——新建库拿得到全部列，老库永远不会长出新列。
"""

from __future__ import annotations

import asyncio
import sqlite3

from asset_exchange.models.asset import Asset, AssetFilter, AssetPricing, AssetSchema
from asset_exchange.models.base import AssetType, DeliveryMethod, SecurityLevel
from asset_exchange.repositories.sqlite.asset_repository import SQLiteAssetRepository
from asset_exchange.repositories.sqlite.connection import SQLiteConnection

TENANT = "tenant-delivery"

# 改前的建表语句（少 delivery_method 一列），用来造"已经落盘的老库"。
LEGACY_DDL = """
CREATE TABLE assets (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    type            TEXT NOT NULL,
    owner           TEXT NOT NULL,
    description     TEXT,
    status          TEXT NOT NULL,
    quality_score   REAL NOT NULL DEFAULT 0,
    security_level  TEXT NOT NULL DEFAULT 'internal',
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


def _payload(name: str, **extra) -> dict:
    body = {
        "name": name,
        "type": "table",
        "tenantId": TENANT,
        "securityLevel": "internal",
        "qualityScore": 80.0,
        "pricing": {"mode": "by_call", "price": 1.0, "unit": "call"},
    }
    body.update(extra)
    return body


def _asset(name: str, method: DeliveryMethod) -> Asset:
    return Asset(
        id="",
        name=name,
        type=AssetType.TABLE,
        tenantId=TENANT,
        securityLevel=SecurityLevel.INTERNAL,
        qualityScore=80.0,
        schema=AssetSchema(),
        pricing=AssetPricing(),
        deliveryMethod=method,
    )


def _column_of(db: str, table: str, column: str):
    """列存在则返回其声明类型，不存在返回 None（用 sqlite 权威解释器，不靠正则）。"""
    conn = sqlite3.connect(db)
    try:
        for r in conn.execute("PRAGMA table_info(%s);" % table):
            if r[1] == column:
                return r[2]
    finally:
        conn.close()
    return None


# ---------- 1 & 2：HTTP 面 ----------


def test_register_round_trips_delivery_method(client):
    resp = client.post(
        "/api/v1/assets/register",
        json=_payload("dm-register", deliveryMethod="database_direct"),
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert (
        body.get("deliveryMethod") == "database_direct"
    ), "deliveryMethod 没回在响应里（改前正是被 extra='ignore' 静默丢弃）：%s" % sorted(body)


def test_register_defaults_to_api_when_omitted(client):
    body = client.post("/api/v1/assets/register", json=_payload("dm-default")).json()
    assert body["deliveryMethod"] == "api", body


def test_register_rejects_unknown_delivery_method(client):
    resp = client.post("/api/v1/assets/register", json=_payload("dm-bad", deliveryMethod="carrier-pigeon"))
    assert resp.status_code == 422, "非法交付方式必须 422，不能悄悄落成默认值：%s %s" % (
        resp.status_code,
        resp.text[:200],
    )


def test_compat_list_endpoint_accepts_delivery_method(client):
    """`POST /assets` 那条兼容上架路径（前端 AssetMarket.vue 走的就是它）也要真收这个键。"""
    resp = client.post("/api/v1/assets", json=_payload("dm-compat", deliveryMethod="file"))
    assert resp.status_code == 201, resp.text
    assert resp.json().get("deliveryMethod") == "file", resp.json()


# ---------- 3：真的落到列上 ----------


def test_sqlite_persists_delivery_method(tmp_path):
    db = str(tmp_path / "dm.db")
    repo = SQLiteAssetRepository(SQLiteConnection(db))
    asset_id = asyncio.run(repo.save(_asset("dm-persist", DeliveryMethod.FILE)))

    # 换一个连接对象重开同一个文件：读得到才说明值在列里，不在内存对象里。
    reopened = SQLiteAssetRepository(SQLiteConnection(db))
    got = asyncio.run(reopened.get(asset_id))
    assert got.deliveryMethod is DeliveryMethod.FILE, got.deliveryMethod
    assert _column_of(db, "assets", "delivery_method") == "TEXT", "新列没建出来"


def test_sqlite_update_changes_delivery_method(tmp_path):
    db = str(tmp_path / "dm-upd.db")
    repo = SQLiteAssetRepository(SQLiteConnection(db))
    asset_id = asyncio.run(repo.save(_asset("dm-update", DeliveryMethod.API)))
    asyncio.run(repo.update(asset_id, deliveryMethod=DeliveryMethod.DATABASE_DIRECT))
    got = asyncio.run(SQLiteAssetRepository(SQLiteConnection(db)).get(asset_id))
    assert got.deliveryMethod is DeliveryMethod.DATABASE_DIRECT, got.deliveryMethod


# ---------- 4：老库幂等 ALTER ----------


def test_legacy_database_gets_the_column_backfilled(tmp_path):
    db = str(tmp_path / "legacy.db")
    seed = sqlite3.connect(db)
    seed.execute(LEGACY_DDL)
    seed.execute(
        "INSERT INTO assets (id, name, type, owner, status, quality_score, security_level,"
        " schema_json, sample_json, update_frequency, tags_json, pricing_json, source_ref,"
        " subscriber_count, created_at, updated_at)"
        " VALUES ('legacy-1', 'legacy-asset', 'table', 't-1', 'draft', 0, 'internal',"
        " '{}', NULL, 'static', '{}', '{}', NULL, 0, '2026-01-01T00:00:00',"
        " '2026-01-01T00:00:00');"
    )
    seed.commit()
    seed.close()
    assert _column_of(db, "assets", "delivery_method") is None, "夹具本就不该带新列"

    SQLiteAssetRepository(SQLiteConnection(db))  # 构造即补列
    assert _column_of(db, "assets", "delivery_method") == "TEXT", "幂等 ALTER 没生效"

    got = asyncio.run(SQLiteAssetRepository(SQLiteConnection(db)).get("legacy-1"))
    assert got.deliveryMethod is DeliveryMethod.API, got.deliveryMethod

    # 再开一次不能重复加列（sqlite 对已存在列 ALTER 会报错 ⇒ 只要没抛异常就是幂等的）
    SQLiteAssetRepository(SQLiteConnection(db))
    assert asyncio.run(SQLiteAssetRepository(SQLiteConnection(db)).list(AssetFilter()))
