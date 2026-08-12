"""SQLite 仓储实现 - 基于标准库 sqlite3.

提供 AssetExchange 全套 SQLite 实现，用于开发与本地持久化。
生产环境可替换为 PostgreSQL 实现（接口契约一致）。

设计要点：
- 自动建表（CREATE TABLE IF NOT EXISTS）
- 复杂字段（嵌套对象 / dict / list）以 JSON 文本列存储
- 时间戳以 ISO 8601 字符串存储
- 单连接 + check_same_thread=False，配合 asyncio 单线程事件循环使用
"""

from __future__ import annotations

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
from asset_exchange.repositories.sqlite.connection import (
    SQLiteConnection,
    default_connection,
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

__all__ = [
    "SQLiteConnection",
    "default_connection",
    "SQLiteAssetRepository",
    "SQLiteSubscriptionRepository",
    "SQLiteDeliveryRepository",
    "SQLiteBillingRepository",
    "SQLiteAuditRepository",
    "SQLiteSettlementRepository",
    "SQLiteAllocationRepository",
]
