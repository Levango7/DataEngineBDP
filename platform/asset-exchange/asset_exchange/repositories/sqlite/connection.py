"""SQLite 连接管理.

提供 thread-local 连接与 schema 初始化入口。每个线程获取独立的 SQLite 连接，
确保线程安全（sqlite3 默认禁止跨线程使用）。

线程安全：使用 threading.local 为每个线程维护独立连接，
避免 check_same_thread=False 的跨线程共享风险（数据损坏 / 随机崩溃）。
"""

from __future__ import annotations

import threading
from pathlib import Path
import sqlite3
from typing import Optional

# 默认数据库文件路径（相对当前工作目录）
DEFAULT_DB_PATH = "data/asset_exchange.db"


class SQLiteConnection:
    """SQLite 连接封装（thread-local）.

    职责：
    - 为每个线程持有独立的 sqlite3.Connection（thread-local）
    - 启用 WAL、外键约束
    - 提供初始化 schema 的入口（由各仓储自行建表）

    线程安全：每个线程通过 conn 属性获取自己的连接实例，
    避免跨线程共享同一连接导致的并发损坏。
    """

    def __init__(self, db_path: str = DEFAULT_DB_PATH) -> None:
        # 确保目录存在
        path = Path(db_path)
        if path.parent and not path.parent.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
        self.dbPath = db_path
        self._local = threading.local()
        self._schema_initialized = False
        self._init_lock = threading.Lock()

    def _create_connection(self) -> sqlite3.Connection:
        """创建新的 SQLite 连接（线程内调用）."""
        conn = sqlite3.connect(
            self.dbPath,
            check_same_thread=True,  # 强制同线程使用，thread-local 保证
            isolation_level=None,    # autocommit；事务用 BEGIN/COMMIT 显式控制
        )
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON;")
        conn.execute("PRAGMA journal_mode = WAL;")
        return conn

    @property
    def conn(self) -> sqlite3.Connection:
        """获取当前线程的 SQLite 连接（thread-local，惰性创建）."""
        if not hasattr(self._local, "conn") or self._local.conn is None:
            self._local.conn = self._create_connection()
            # 首次获取连接时确保 schema 已初始化
            if not self._schema_initialized:
                with self._init_lock:
                    if not self._schema_initialized:
                        self._init_schema_on_conn(self._local.conn)
                        self._schema_initialized = True
        return self._local.conn

    def close(self) -> None:
        """关闭当前线程的连接."""
        if hasattr(self._local, "conn") and self._local.conn is not None:
            self._local.conn.close()
            self._local.conn = None

    def close_all(self) -> None:
        """关闭所有线程的连接（清理用，需在各线程中调用或进程退出时调用）.

        注意：thread-local 连接只能在所属线程中关闭，
        此方法仅关闭当前线程的连接；其他线程的连接需各自调用 close()。
        """
        self.close()

    def _init_schema_on_conn(self, conn: sqlite3.Connection) -> None:
        """在指定连接上初始化全部表 schema."""
        from asset_exchange.repositories.sqlite.allocation_repository import (
            SQLiteAllocationRepository,
        )
        from asset_exchange.repositories.sqlite.asset_repository import (
            SQLiteAssetRepository,
        )
        from asset_exchange.repositories.sqlite.audit_repository import (
            SQLiteAuditRepository,
        )
        from asset_exchange.repositories.sqlite.billing_repository import (
            SQLiteBillingRepository,
        )
        from asset_exchange.repositories.sqlite.delivery_repository import (
            SQLiteDeliveryRepository,
        )
        from asset_exchange.repositories.sqlite.settlement_repository import (
            SQLiteSettlementRepository,
        )
        from asset_exchange.repositories.sqlite.subscription_repository import (
            SQLiteSubscriptionRepository,
        )

        # 临时切换 conn 以在各仓储 _create_table 中使用指定连接
        original_conn = getattr(self._local, "conn", None)
        self._local.conn = conn
        try:
            SQLiteAssetRepository(self)._create_table()
            SQLiteSubscriptionRepository(self)._create_table()
            SQLiteDeliveryRepository(self)._create_table()
            SQLiteBillingRepository(self)._create_table()
            SQLiteAuditRepository(self)._create_table()
            SQLiteSettlementRepository(self)._create_table()
            SQLiteAllocationRepository(self)._create_table()
        finally:
            self._local.conn = original_conn

    def init_schema(self) -> None:
        """初始化全部表 schema（惰性，首次获取 conn 时执行）.

        各仓储 save() 时也会 CREATE TABLE IF NOT EXISTS，
        这里集中调用一次以提前建表并验证 SQL。
        """
        # 触发 conn 属性以惰性初始化 schema
        _ = self.conn


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
        _default_conn.close_all()
        _default_conn = None
