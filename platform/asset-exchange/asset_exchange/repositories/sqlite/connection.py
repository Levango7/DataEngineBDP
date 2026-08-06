"""SQLite 连接管理.

提供共享连接单例与 schema 初始化入口。所有 SQLite 仓储共用同一连接，
确保事务一致性与引用约束。

线程安全：sqlite3 默认禁止跨线程使用，这里关闭 check_same_thread 校验，
依赖 asyncio 单线程事件循环；多线程场景请改用连接池或 aiosqlite。
"""
from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Optional

# 默认数据库文件路径（相对当前工作目录）
DEFAULT_DB_PATH = "data/asset_exchange.db"


class SQLiteConnection:
    """SQLite 连接封装.

    职责：
    - 持有 sqlite3.Connection
    - 启用 WAL、外键约束
    - 提供初始化 schema 的入口（由各仓储自行建表）
    """

    def __init__(self, db_path: str = DEFAULT_DB_PATH) -> None:
        # 确保目录存在
        path = Path(db_path)
        if path.parent and not path.parent.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
        self.dbPath = db_path
        self._conn = sqlite3.connect(
            db_path,
            check_same_thread=False,
            isolation_level=None,  # autocommit；事务用 BEGIN/COMMIT 显式控制
        )
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON;")
        self._conn.execute("PRAGMA journal_mode = WAL;")

    @property
    def conn(self) -> sqlite3.Connection:
        return self._conn

    def close(self) -> None:
        self._conn.close()

    def init_schema(self) -> None:
        """初始化全部表 schema.

        各仓储 save() 时也会 CREATE TABLE IF NOT EXISTS，
        这里集中调用一次以提前建表并验证 SQL。
        """
        from asset_exchange.repositories.sqlite.asset_repository import (
            SQLiteAssetRepository,
        )
        from asset_exchange.repositories.sqlite.billing_repository import (
            SQLiteBillingRepository,
        )
        from asset_exchange.repositories.sqlite.delivery_repository import (
            SQLiteDeliveryRepository,
        )
        from asset_exchange.repositories.sqlite.subscription_repository import (
            SQLiteSubscriptionRepository,
        )

        SQLiteAssetRepository(self)._create_table()
        SQLiteSubscriptionRepository(self)._create_table()
        SQLiteDeliveryRepository(self)._create_table()
        SQLiteBillingRepository(self)._create_table()


_default_conn: Optional[SQLiteConnection] = None


def default_connection(db_path: Optional[str] = None) -> SQLiteConnection:
    """获取默认连接单例.

    Args:
        db_path: 数据库文件路径，首次传入后忽略后续参数。
    """
    global _default_conn
    if _default_conn is None:
        _default_conn = SQLiteConnection(db_path or DEFAULT_DB_PATH)
        _default_conn.init_schema()
    return _default_conn


def reset_default_connection() -> None:
    """重置默认连接单例（测试用）."""
    global _default_conn
    if _default_conn is not None:
        _default_conn.close()
        _default_conn = None