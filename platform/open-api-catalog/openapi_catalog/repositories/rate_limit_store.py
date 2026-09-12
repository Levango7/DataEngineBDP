"""订阅级限流配置持久化存储.

将原 billing.py 内的内存字典 `_rate_limit_configs` 改为 SQLite 持久化，
保证多实例部署下限流配置一致。存储路径复用 Settings.dbPath
（环境变量 OPENAPI_CATALOG_DB_PATH 驱动），与主业务库同库不同表，
WAL 模式 + busy_timeout 处理跨进程/跨实例并发读写（等价文件锁）。

storeType=mock 时回退到进程内内存字典，保持单测隔离与零文件 IO。
"""

from __future__ import annotations

import sqlite3
import threading
from dataclasses import dataclass
from typing import Optional

from openapi_catalog.config.settings import Settings


@dataclass(frozen=True)
class RateLimitRow:
    """限流配置行（与 billing.RateLimitConfig 字段对齐）."""

    qps: int
    concurrent: int
    burst: int


class _MemoryRateLimitStore:
    """进程内内存实现（mock 模式 / 测试隔离用）."""

    def __init__(self) -> None:
        self._data: dict[str, RateLimitRow] = {}
        self._lock = threading.Lock()

    def save(self, subscription_id: str, row: RateLimitRow) -> None:
        with self._lock:
            self._data[subscription_id] = row

    def get(self, subscription_id: str) -> Optional[RateLimitRow]:
        with self._lock:
            return self._data.get(subscription_id)


class _SQLiteRateLimitStore:
    """SQLite 持久化实现（多实例共享同一 dbPath 文件）."""

    def __init__(self, db_path: str) -> None:
        # check_same_thread=False：FastAPI 依赖注入可能跨线程调用；
        # busy_timeout=5000ms 等价文件锁，串行化并发写。
        self._conn = sqlite3.connect(
            db_path,
            check_same_thread=False,
            isolation_level=None,
        )
        self._conn.execute("PRAGMA journal_mode = WAL;")
        self._conn.execute("PRAGMA busy_timeout = 5000;")
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS rate_limit_configs (
                subscription_id TEXT PRIMARY KEY,
                qps             INTEGER NOT NULL,
                concurrent      INTEGER NOT NULL,
                burst           INTEGER NOT NULL,
                updated_at      TEXT NOT NULL
            );
            """
        )

    def save(self, subscription_id: str, row: RateLimitRow) -> None:
        from openapi_catalog.models.base import utc_now

        self._conn.execute(
            """
            INSERT INTO rate_limit_configs (subscription_id, qps, concurrent, burst, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(subscription_id) DO UPDATE SET
                qps = excluded.qps,
                concurrent = excluded.concurrent,
                burst = excluded.burst,
                updated_at = excluded.updated_at;
            """,
            (subscription_id, row.qps, row.concurrent, row.burst, utc_now().isoformat()),
        )

    def get(self, subscription_id: str) -> Optional[RateLimitRow]:
        cur = self._conn.execute(
            "SELECT qps, concurrent, burst FROM rate_limit_configs WHERE subscription_id = ?;",
            (subscription_id,),
        )
        r = cur.fetchone()
        if r is None:
            return None
        return RateLimitRow(qps=r[0], concurrent=r[1], burst=r[2])


# 模块级单例缓存：按 db_path 复用 SQLite 连接，避免每请求新建连接。
_memory_singleton: Optional[_MemoryRateLimitStore] = None
_sqlite_singletons: dict[str, _SQLiteRateLimitStore] = {}
_singleton_lock = threading.Lock()


def get_rate_limit_store(settings: Settings):
    """根据 settings.storeType 返回限流配置存储单例.

    - mock：进程内内存字典（测试隔离）
    - sqlite：共享 dbPath 文件的 SQLite 表（多实例一致）
    """
    global _memory_singleton
    if settings.isMock:
        with _singleton_lock:
            if _memory_singleton is None:
                _memory_singleton = _MemoryRateLimitStore()
            return _memory_singleton
    with _singleton_lock:
        store = _sqlite_singletons.get(settings.dbPath)
        if store is None:
            store = _SQLiteRateLimitStore(settings.dbPath)
            _sqlite_singletons[settings.dbPath] = store
        return store


def reset_rate_limit_store_cache() -> None:
    """清空单例缓存（测试用：隔离用例间的 SQLite 文件状态）."""
    global _memory_singleton
    with _singleton_lock:
        for s in _sqlite_singletons.values():
            try:
                s._conn.close()
            except sqlite3.Error:
                pass
        _sqlite_singletons.clear()
        _memory_singleton = None