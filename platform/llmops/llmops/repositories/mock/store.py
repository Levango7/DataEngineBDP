"""Mock 模型存储 - 内存字典实现."""

from __future__ import annotations

from typing import Any
import uuid

from llmops.interfaces.store import ModelStore
from llmops.models.base import ModelStatus, utc_now
from llmops.models.model import ModelFilter, ModelInfo, ModelVersion
from llmops.repositories import (
    ModelAlreadyExistsError,
    ModelNotFoundError,
    VersionNotFoundError,
)


class MockModelStore(ModelStore):
    """内存字典模型存储.

    线程安全说明：单进程内存态，配合 asyncio 单线程事件循环无需加锁。
    跨进程场景请使用 MLflowModelStore。
    """

    def __init__(self) -> None:
        self._models: dict[str, ModelInfo] = {}
        # name -> model_id 索引，保证同名模型唯一
        self._name_index: dict[str, str] = {}

    # ---------- ModelStore ----------

    async def register_model(self, model_info: ModelInfo) -> str:
        # 若未带 id 则生成
        if not model_info.id:
            model_info.id = str(uuid.uuid4())
        # 同名校验
        if model_info.name in self._name_index:
            raise ModelAlreadyExistsError(model_info.name)
        # 写入
        now = utc_now()
        model_info.createdAt = now
        model_info.updatedAt = now
        self._models[model_info.id] = model_info
        self._name_index[model_info.name] = model_info.id
        return model_info.id

    async def get_model(self, model_id: str) -> ModelInfo:
        if model_id not in self._models:
            raise ModelNotFoundError(model_id)
        return self._models[model_id]

    async def list_models(self, filter: ModelFilter) -> list[ModelInfo]:
        result: list[ModelInfo] = []
        for m in self._models.values():
            if filter.name and filter.name not in m.name:
                continue
            if filter.type and m.type != filter.type:
                continue
            if filter.status and m.status != filter.status:
                continue
            if filter.tag:
                if "=" not in filter.tag:
                    continue
                k, v = filter.tag.split("=", 1)
                if m.tags.get(k) != v:
                    continue
            result.append(m)
        # 按 createdAt 倒序
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result[filter.offset : filter.offset + filter.limit]

    async def delete_model(self, model_id: str) -> None:
        if model_id not in self._models:
            raise ModelNotFoundError(model_id)
        m = self._models.pop(model_id)
        self._name_index.pop(m.name, None)

    async def get_model_versions(self, model_id: str) -> list[ModelVersion]:
        m = await self.get_model(model_id)
        return sorted(m.versions, key=lambda v: v.version)

    async def add_model_version(self, model_id: str, version: ModelVersion) -> ModelVersion:
        m = await self.get_model(model_id)
        # 版本号冲突校验
        existing_versions = {v.version for v in m.versions}
        if version.version in existing_versions:
            raise VersionNotFoundError(model_id, version.version)
        version.modelId = model_id
        m.versions.append(version)
        m.updatedAt = utc_now()
        # 首个版本自动设为生产版本
        if m.currentVersion is None:
            m.currentVersion = version.version
            version.isProduction = True
            m.status = ModelStatus.READY
        return version

    async def set_production_version(self, model_id: str, version: int) -> ModelInfo:
        m = await self.get_model(model_id)
        target: ModelVersion | None = None
        for v in m.versions:
            if v.version == version:
                target = v
                break
        if target is None:
            raise VersionNotFoundError(model_id, version)
        # 取消旧生产版本标记
        for v in m.versions:
            v.isProduction = False
        target.isProduction = True
        m.currentVersion = version
        m.status = ModelStatus.READY
        m.updatedAt = utc_now()
        return m

    async def update_model(self, model_id: str, **fields: Any) -> ModelInfo:
        m = await self.get_model(model_id)
        # 名称变更需同步索引
        if "name" in fields and fields["name"] != m.name:
            if fields["name"] in self._name_index:
                raise ModelAlreadyExistsError(fields["name"])
            self._name_index.pop(m.name, None)
            self._name_index[fields["name"]] = model_id
        for k, v in fields.items():
            if hasattr(m, k):
                setattr(m, k, v)
        m.updatedAt = utc_now()
        return m

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._models.clear()
        self._name_index.clear()

    def __len__(self) -> int:
        return len(self._models)
