"""SQLite 仓储实现 - 基于标准库 sqlite3.

提供 Open API Catalog 的 SQLite 实现，用于开发与本地持久化。
生产环境可替换为 PostgreSQL 实现（接口契约一致）。

设计要点：
- 自动建表（CREATE TABLE IF NOT EXISTS）
- 复杂字段（嵌套对象 / dict / list）以 JSON 文本列存储
- 时间戳以 ISO 8601 字符串存储
- 调用计量保留最近 N 条（默认 10000，由调用方控制清理策略）
"""
from __future__ import annotations

from openapi_catalog.repositories.sqlite.store import (
    SQLiteCatalogStore,
    SQLiteConnection,
    generate_ak_sk,
)

__all__ = ["SQLiteCatalogStore", "SQLiteConnection", "generate_ak_sk"]
