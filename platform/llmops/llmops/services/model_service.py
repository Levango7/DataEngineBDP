"""模型管理业务逻辑."""

from __future__ import annotations

from typing import Optional

from llmops.interfaces.store import ModelStore
from llmops.models.base import ModelStatus
from llmops.models.model import ModelFilter, ModelInfo, ModelVersion


class ModelService:
    """模型管理服务（编排 ModelStore）."""

    def __init__(self, store: ModelStore) -> None:
        self._store = store

    async def register_model(self, model_info: ModelInfo) -> ModelInfo:
        """注册模型，返回带 id 的完整信息."""
        model_id = await self._store.register_model(model_info)
        return await self._store.get_model(model_id)

    async def get_model(self, model_id: str) -> ModelInfo:
        return await self._store.get_model(model_id)

    async def list_models(self, filter: Optional[ModelFilter] = None) -> list[ModelInfo]:
        return await self._store.list_models(filter or ModelFilter())

    async def delete_model(self, model_id: str) -> None:
        # 业务校验：已部署的模型不允许删除
        m = await self._store.get_model(model_id)
        if m.status == ModelStatus.DEPLOYED:
            raise ValueError(f"模型 {model_id} 已部署，请先卸载再删除")
        await self._store.delete_model(model_id)

    async def get_model_versions(self, model_id: str) -> list[ModelVersion]:
        return await self._store.get_model_versions(model_id)

    async def set_production_version(self, model_id: str, version: int) -> ModelInfo:
        return await self._store.set_production_version(model_id, version)

    async def update_model(self, model_id: str, **fields) -> ModelInfo:
        return await self._store.update_model(model_id, **fields)
