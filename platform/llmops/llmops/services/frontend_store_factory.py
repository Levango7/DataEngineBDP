"""前端视图存储工厂：按配置分发 memory / postgresql 后端.

独立成模块以打断循环导入：postgres_frontend_store 依赖 frontend_store（父类），
若工厂放在 frontend_store 内并 import PG 实现即成环（CodeQL py/cyclic-import）。
PG 实现保持函数内懒导入：无 psycopg 环境下 memory 模式仍可运行。
"""

from __future__ import annotations

from llmops.config.settings import Settings
from llmops.services.frontend_store import FrontendContractStore


def create_frontend_store(settings: Settings) -> FrontendContractStore:
    """按配置选择前端视图存储后端：memory（默认）/ postgresql（生产持久层）。

    postgresql 模式下 DSN 缺失已在 Settings 校验器 fail-fast；这里不再静默回退内存
    （生产持久层契约：缺配置就是缺配置，不掩盖）。
    """
    if settings.frontendStore == "postgresql":
        from llmops.services.postgres_frontend_store import PostgresFrontendContractStore

        return PostgresFrontendContractStore(settings.frontendDatabaseUrl)
    return FrontendContractStore()
