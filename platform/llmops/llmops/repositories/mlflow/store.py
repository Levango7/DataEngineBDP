"""MLflow 模型存储实现 - 对接 MLflow Model Registry.

骨架实现：完整接口签名 + MLflow SDK 调用结构。
LLMOps 在 MLflow 之上扩展大模型特有的字段（type/base/params），通过 MLflow
Registered Model 的 tags 与 description 持久化。
"""

from __future__ import annotations

import asyncio
from typing import Any, Optional
import uuid

from llmops.interfaces.store import ModelStore
from llmops.models.model import ModelFilter, ModelInfo, ModelVersion
from llmops.repositories import (
    ModelAlreadyExistsError,
    ModelNotFoundError,
    VersionNotFoundError,
)
from llmops.repositories.mlflow.client import MLflowClient

# MLflow tag 前缀，避免与 MLflow 内置 tag 冲突
_TAG_PREFIX = "sq.llmops."


class MLflowModelStore(ModelStore):
    """基于 MLflow Model Registry 的模型存储."""

    def __init__(self, client: MLflowClient) -> None:
        self._client = client

    # ---------- ModelStore ----------

    async def register_model(self, model_info: ModelInfo) -> str:
        return await asyncio.to_thread(self._register_model_sync, model_info)

    def _register_model_sync(self, model_info: ModelInfo) -> str:
        mlflow = self._client.mlflow
        client = self._client.client
        # 同名校验
        try:
            client.get_registered_model(model_info.name)
            raise ModelAlreadyExistsError(model_info.name)
        except mlflow.exceptions.RestException as exc:
            # MLflow 中 not found 是 MlflowException with error_code RESOURCE_DOES_NOT_EXIST
            if "RESOURCE_DOES_NOT_EXIST" not in str(exc):
                raise
        # 创建 Registered Model
        if not model_info.id:
            model_info.id = str(uuid.uuid4())
        client.create_registered_model(
            model_info.name,
            tags=self._encode_tags(model_info),
            description=model_info.description or "",
        )
        # MLflow Registered Model 的 name 作为业务主键，model_info.id 存到 tag
        return model_info.id

    async def get_model(self, model_id: str) -> ModelInfo:
        # 通过 tag 反查 name，再取 Registered Model
        name = await self._find_name_by_id(model_id)
        if name is None:
            raise ModelNotFoundError(model_id)
        rm = await asyncio.to_thread(self._client.client.get_registered_model, name)
        return self._decode_registered_model(rm)

    async def list_models(self, filter: ModelFilter) -> list[ModelInfo]:
        # MLflow list_registered_models 不支持复杂过滤，全量拉取后内存过滤
        rms = await asyncio.to_thread(self._client.client.search_registered_models)
        result: list[ModelInfo] = []
        for rm in rms:
            m = self._decode_registered_model(rm)
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
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result[filter.offset : filter.offset + filter.limit]

    async def delete_model(self, model_id: str) -> None:
        name = await self._find_name_by_id(model_id)
        if name is None:
            raise ModelNotFoundError(model_id)
        await asyncio.to_thread(self._client.client.delete_registered_model, name)

    async def get_model_versions(self, model_id: str) -> list[ModelVersion]:
        m = await self.get_model(model_id)
        # MLflow 3.x 的 RegisteredModel.latest_versions 只含「每个 stage 的最新版本」——
        # 无 stage 的新注册版本（刚 register、未 transition）不在其中，用它会导致
        # get_model_versions 为空、推理部署解析不到 artifactUri（真链路实测踩到）。
        # 改为 search_model_versions 全量拉取。
        mvs = await asyncio.to_thread(self._client.client.search_model_versions, f"name='{m.name}'")
        versions = [
            ModelVersion(
                version=int(mv.version),
                modelId=model_id,
                sourceRunId=mv.run_id,
                artifactUri=mv.source,
                isProduction=(mv.current_stage == "Production"),
            )
            for mv in mvs
        ]
        return sorted(versions, key=lambda v: v.version)

    async def add_model_version(self, model_id: str, version: ModelVersion) -> ModelVersion:
        m = await self.get_model(model_id)
        # MLflow 要求 source=runs:/<run_id>/… 时必须同时传 run_id（标识包含 source 的
        # run），否则报 INVALID_PARAMETER_VALUE；调用方未显式给 sourceRunId 时从
        # artifactUri 提取（真链路实测踩到）。
        run_id = version.sourceRunId
        if not run_id and (version.artifactUri or "").startswith("runs:/"):
            run_id = version.artifactUri[len("runs:/") :].split("/", 1)[0]
        # 不传 tags（`sq.llmops.version` tag 无消费者；MLflow 2.x/3.x 的 tags 参数
        # 形态不同——dict vs ModelVersionTag 列表，省略可避开形态差异）。
        mv = await asyncio.to_thread(
            self._client.client.create_model_version,
            name=m.name,
            source=version.artifactUri or "",
            run_id=run_id,
            description=version.description,
        )
        # MLflow 3.x 的 mv.version 是字符串，ModelVersion.version 契约为 int。
        version.version = int(mv.version)
        version.modelId = model_id
        return version

    async def set_production_version(self, model_id: str, version: int) -> ModelInfo:
        m = await self.get_model(model_id)
        client = self._client.client
        try:
            await asyncio.to_thread(
                client.transition_model_version_stage,
                name=m.name,
                version=version,
                stage="Production",
            )
        except Exception as exc:
            raise VersionNotFoundError(model_id, version) from exc
        return await self.get_model(model_id)

    async def update_model(self, model_id: str, **fields: Any) -> ModelInfo:
        m = await self.get_model(model_id)
        if "description" in fields or "tags" in fields or "status" in fields:
            await asyncio.to_thread(self._update_model_sync, m.name, fields)
        return await self.get_model(model_id)

    def _update_model_sync(self, name: str, fields: dict[str, Any]) -> None:
        client = self._client.client
        if "description" in fields:
            client.update_registered_model(name, description=fields["description"])
        if "status" in fields:
            status = fields["status"]
            status_val = status.value if hasattr(status, "value") else str(status)
            client.set_registered_model_tag(name, f"{_TAG_PREFIX}status", status_val)
        if "tags" in fields:
            for k, v in fields["tags"].items():
                client.set_registered_model_tag(name, f"{_TAG_PREFIX}tag.{k}", v)

    # ---------- 内部工具 ----------

    def _encode_tags(self, m: ModelInfo) -> dict[str, str]:
        """把 ModelInfo 关键字段编码为 MLflow tag."""
        tags = {
            f"{_TAG_PREFIX}id": m.id,
            f"{_TAG_PREFIX}type": m.type.value,
            f"{_TAG_PREFIX}status": m.status.value,
        }
        if m.baseModelId:
            tags[f"{_TAG_PREFIX}base"] = m.baseModelId
        for k, v in m.tags.items():
            tags[f"{_TAG_PREFIX}tag.{k}"] = v
        return tags

    def _decode_registered_model(self, rm: Any) -> ModelInfo:
        """从 MLflow Registered Model 解码为 ModelInfo."""
        raw_tags = rm.tags or []
        # MLflow 3.x 的 tags 是 dict（2.x 是 Tag 实体列表）——直接 `t.key` 迭代 dict
        # 会报 "'str' object has no attribute 'key'"（真链路实测）。
        tags = dict(raw_tags) if isinstance(raw_tags, dict) else {t.key: t.value for t in raw_tags}
        model_id = tags.get(f"{_TAG_PREFIX}id", rm.name)
        type_str = tags.get(f"{_TAG_PREFIX}type", "base")
        status_str = tags.get(f"{_TAG_PREFIX}status", "draft")
        base = tags.get(f"{_TAG_PREFIX}base")
        # 解码业务 tag
        biz_tags = {}
        prefix = f"{_TAG_PREFIX}tag."
        for k, v in tags.items():
            if k.startswith(prefix):
                biz_tags[k[len(prefix) :]] = v
        # 版本
        versions: list[ModelVersion] = []
        for mv in rm.latest_versions or []:
            versions.append(
                ModelVersion(
                    version=mv.version,
                    modelId=model_id,
                    sourceRunId=mv.run_id,
                    artifactUri=mv.source,
                    isProduction=(mv.current_stage == "Production"),
                )
            )
        return ModelInfo(
            id=model_id,
            name=rm.name,
            type=type_str,  # type: ignore[arg-type]
            baseModelId=base,
            status=status_str,  # type: ignore[arg-type]
            description=rm.description,
            tags=biz_tags,
            versions=versions,
            createdAt=rm.creation_timestamp,
            updatedAt=rm.last_updated_timestamp,
        )

    async def _find_name_by_id(self, model_id: str) -> Optional[str]:
        """通过 model_id tag 反查 registered model name."""
        rms = await asyncio.to_thread(self._client.client.search_registered_models)
        for rm in rms:
            raw_tags = rm.tags or []
            # MLflow 3.x 的 tags 是 dict（2.x 是 Tag 实体列表）——直接 `t.key` 迭代
            # dict 会报 "'str' object has no attribute 'key'"（真链路实测）。
            tags = dict(raw_tags) if isinstance(raw_tags, dict) else {t.key: t.value for t in raw_tags}
            if tags.get(f"{_TAG_PREFIX}id") == model_id:
                return rm.name
        return None
