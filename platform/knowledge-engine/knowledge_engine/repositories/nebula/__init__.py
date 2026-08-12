"""NebulaGraph 仓储实现.

通过 nebula3-python SDK 调用 NebulaGraph GraphD。
仅在 KE_STORE_TYPE=nebula 时加载；未安装 SDK 时抛 StoreUnavailableError。
"""

from __future__ import annotations

from knowledge_engine.repositories.nebula.graph_store import NebulaGraphStore

__all__ = ["NebulaGraphStore"]
