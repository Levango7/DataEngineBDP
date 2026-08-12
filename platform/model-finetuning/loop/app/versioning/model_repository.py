"""模型仓库集成.

简化版模型仓库：基于文件系统 + 元数据 JSON。
生产环境可替换为 MLflow 或自研模型仓库服务。

职责：
- 模型注册：记录模型名、版本、路径、元数据
- 模型查询：按名称/版本查询
- 模型删除：软删除（标记 is_active=False）
- 与 Adapter 版本化模块联动
"""

from __future__ import annotations

import json
import os
import threading
from datetime import datetime, timezone
from typing import Any, Optional

from loguru import logger


class ModelRecord:
    """模型仓库记录."""

    def __init__(
        self,
        model_name: str,
        version: str,
        path: str,
        base_model: str = "",
        framework: str = "",
        method: str = "",
        tenant_id: str = "default",
        metadata: dict[str, Any] | None = None,
        created_at: datetime | None = None,
        is_active: bool = True,
    ):
        self.model_name = model_name
        self.version = version
        self.path = path
        self.base_model = base_model
        self.framework = framework
        self.method = method
        self.tenant_id = tenant_id
        self.metadata = metadata or {}
        self.created_at = created_at or datetime.now(timezone.utc)
        self.is_active = is_active

    def to_dict(self) -> dict[str, Any]:
        return {
            "modelName": self.model_name,
            "version": self.version,
            "path": self.path,
            "baseModel": self.base_model,
            "framework": self.framework,
            "method": self.method,
            "tenantId": self.tenant_id,
            "metadata": self.metadata,
            "createdAt": self.created_at.isoformat(),
            "isActive": self.is_active,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ModelRecord":
        return cls(
            model_name=data["modelName"],
            version=data["version"],
            path=data.get("path", ""),
            base_model=data.get("baseModel", ""),
            framework=data.get("framework", ""),
            method=data.get("method", ""),
            tenant_id=data.get("tenantId", "default"),
            metadata=data.get("metadata", {}),
            created_at=datetime.fromisoformat(
                data["createdAt"]
            ) if data.get("createdAt") else None,
            is_active=data.get("isActive", True),
        )


class ModelRepository:
    """模型仓库（简化版）.

    存储：内存字典 + 文件系统元数据 JSON。
    线程安全。
    """

    def __init__(self, storage_dir: str = "/tmp/finetune-loop/repository"):
        self.storage_dir = storage_dir
        # 模型键（modelName+tenantId）→ 版本列表
        self._models: dict[str, list[ModelRecord]] = {}
        self._lock = threading.RLock()

        os.makedirs(storage_dir, exist_ok=True)
        self._load_from_disk()
        logger.info("ModelRepository 初始化完成")

    def _key(self, model_name: str, tenant_id: str) -> str:
        return f"{tenant_id}/{model_name}"

    # ============================================================
    # 注册模型
    # ============================================================
    def register(
        self, model_name: str, version: str, path: str,
        base_model: str = "", framework: str = "", method: str = "",
        tenant_id: str = "default", metadata: dict | None = None,
    ) -> ModelRecord:
        """注册模型到仓库."""
        key = self._key(model_name, tenant_id)
        record = ModelRecord(
            model_name=model_name,
            version=version,
            path=path,
            base_model=base_model,
            framework=framework,
            method=method,
            tenant_id=tenant_id,
            metadata=metadata or {},
        )
        with self._lock:
            if key not in self._models:
                self._models[key] = []
            # 检查版本是否已存在
            existing = [
                r for r in self._models[key]
                if r.version == version and r.is_active
            ]
            if existing:
                logger.warning(
                    f"模型 {model_name} 版本 {version} 已存在，将覆盖"
                )
                for r in existing:
                    r.is_active = False
            self._models[key].append(record)
            self._save_to_disk()
        logger.info(
            f"模型已注册: {model_name}@{version}, path={path}"
        )
        return record

    # ============================================================
    # 查询模型
    # ============================================================
    def get_model(
        self, model_name: str, version: str = "",
        tenant_id: str = "default",
    ) -> Optional[dict]:
        """查询模型.

        Args:
            model_name: 模型名.
            version: 版本号（空表示最新）.
            tenant_id: 租户 ID.

        Returns:
            模型记录字典，或 None.
        """
        key = self._key(model_name, tenant_id)
        with self._lock:
            versions = self._models.get(key, [])
            if not versions:
                return None
            if version:
                for r in versions:
                    if r.version == version and r.is_active:
                        return r.to_dict()
                return None
            # 返回最新激活版本
            active = [r for r in versions if r.is_active]
            if not active:
                return None
            return active[-1].to_dict()

    def list_models(
        self, tenant_id: str = "default",
    ) -> list[dict]:
        """列出所有模型（最新版本）."""
        with self._lock:
            result = []
            for key, versions in self._models.items():
                parts = key.split("/", 1)
                if len(parts) != 2:
                    continue
                k_tenant, k_name = parts
                if tenant_id and k_tenant != tenant_id:
                    continue
                active = [r for r in versions if r.is_active]
                if active:
                    result.append(active[-1].to_dict())
            return result

    def list_versions(
        self, model_name: str, tenant_id: str = "default",
    ) -> list[dict]:
        """列出模型所有版本."""
        key = self._key(model_name, tenant_id)
        with self._lock:
            versions = self._models.get(key, [])
            return [r.to_dict() for r in versions]

    # ============================================================
    # 删除模型（软删除）
    # ============================================================
    def deactivate(
        self, model_name: str, version: str,
        tenant_id: str = "default",
    ) -> bool:
        """停用模型版本."""
        key = self._key(model_name, tenant_id)
        with self._lock:
            versions = self._models.get(key, [])
            for r in versions:
                if r.version == version:
                    r.is_active = False
                    self._save_to_disk()
                    return True
            return False

    # ============================================================
    # 持久化
    # ============================================================
    def _save_to_disk(self) -> None:
        try:
            path = os.path.join(self.storage_dir, "models.json")
            data = {
                "models": {
                    key: [r.to_dict() for r in records]
                    for key, records in self._models.items()
                },
            }
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except OSError as e:
            logger.warning(f"保存模型仓库元数据失败: {e}")

    def _load_from_disk(self) -> None:
        try:
            path = os.path.join(self.storage_dir, "models.json")
            if not os.path.exists(path):
                return
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            for key, records in data.get("models", {}).items():
                self._models[key] = [
                    ModelRecord.from_dict(r) for r in records
                ]
        except (OSError, json.JSONDecodeError) as e:
            logger.warning(f"加载模型仓库元数据失败: {e}")

    # ============================================================
    # 统计
    # ============================================================
    def stats(self) -> dict:
        with self._lock:
            return {
                "totalModels": len(self._models),
                "totalVersions": sum(
                    len(v) for v in self._models.values()
                ),
            }