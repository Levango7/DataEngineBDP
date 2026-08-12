"""Adapter 版本化注册表.

管理微调产物（Adapter 权重）的版本化存储：
- 自动递增版本号（语义化版本：major.minor.patch）
- 版本与基座模型、微调方式、框架关联
- 支持版本历史查询、版本对比、回滚

存储：内存字典 + 文件系统元数据 JSON（生产可替换为数据库）。
"""

from __future__ import annotations

import json
import os
import threading
from datetime import datetime, timezone
from typing import Any, Optional

from loguru import logger


class AdapterVersion:
    """Adapter 版本记录."""

    def __init__(
        self,
        version: str,
        base_model: str,
        method: str,
        framework: str,
        tenant_id: str,
        adapter_path: str = "",
        loop_task_id: str = "",
        metrics: dict[str, Any] | None = None,
        created_at: datetime | None = None,
        is_active: bool = True,
    ):
        self.version = version
        self.base_model = base_model
        self.method = method
        self.framework = framework
        self.tenant_id = tenant_id
        self.adapter_path = adapter_path
        self.loop_task_id = loop_task_id
        self.metrics = metrics or {}
        self.created_at = created_at or datetime.now(timezone.utc)
        self.is_active = is_active

    def to_dict(self) -> dict[str, Any]:
        """序列化为字典."""
        return {
            "version": self.version,
            "baseModel": self.base_model,
            "method": self.method,
            "framework": self.framework,
            "tenantId": self.tenant_id,
            "adapterPath": self.adapter_path,
            "loopTaskId": self.loop_task_id,
            "metrics": self.metrics,
            "createdAt": self.created_at.isoformat(),
            "isActive": self.is_active,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AdapterVersion":
        """从字典反序列化."""
        return cls(
            version=data["version"],
            base_model=data.get("baseModel", ""),
            method=data.get("method", ""),
            framework=data.get("framework", ""),
            tenant_id=data.get("tenantId", ""),
            adapter_path=data.get("adapterPath", ""),
            loop_task_id=data.get("loopTaskId", ""),
            metrics=data.get("metrics", {}),
            created_at=datetime.fromisoformat(
                data["createdAt"]
            ) if data.get("createdAt") else None,
            is_active=data.get("isActive", True),
        )


class AdapterRegistry:
    """Adapter 版本化注册表.

    线程安全：通过 _lock 保护内部字典。
    持久化：可选，将元数据写入文件系统。
    """

    def __init__(self, storage_dir: str = "/tmp/finetune-loop/registry"):
        """初始化.

        Args:
            storage_dir: 元数据存储目录.
        """
        self.storage_dir = storage_dir
        # 模型键 → 版本列表（按创建时间排序）
        self._versions: dict[str, list[AdapterVersion]] = {}
        # 模型键 → 当前激活版本号
        self._active: dict[str, str] = {}
        self._lock = threading.RLock()

        os.makedirs(storage_dir, exist_ok=True)
        self._load_from_disk()
        logger.info(
            f"AdapterRegistry 初始化完成，storage_dir={storage_dir}"
        )

    def _model_key(
        self, base_model: str, method: str, framework: str,
        tenant_id: str,
    ) -> str:
        """构造模型唯一键."""
        return f"{tenant_id}/{base_model}/{method}/{framework}"

    # ============================================================
    # 分配版本号
    # ============================================================
    def allocate_version(
        self, base_model: str, method: str, framework: str,
        tenant_id: str,
    ) -> str:
        """分配新版本号.

        版本号规则：major.minor.patch
        - 首次：0.1.0
        - 后续：patch 递增
        - 若基座模型变更：minor 递增（由调用方决定，此处简化为 patch）

        Args:
            base_model: 基座模型.
            method: 微调方式.
            framework: 框架.
            tenant_id: 租户 ID.

        Returns:
            新版本号字符串.
        """
        key = self._model_key(base_model, method, framework, tenant_id)
        with self._lock:
            versions = self._versions.get(key, [])
            if not versions:
                return "0.1.0"
            # 取最新版本，patch +1
            latest = versions[-1].version
            parts = latest.split(".")
            if len(parts) == 3:
                major, minor, patch = int(parts[0]), int(parts[1]), int(parts[2])
                return f"{major}.{minor}.{patch + 1}"
            return "0.1.0"

    # ============================================================
    # 注册版本
    # ============================================================
    def register(
        self, version: str, base_model: str, adapter_path: str,
        tenant_id: str, method: str = "lora", framework: str = "peft",
        loop_task_id: str = "", metrics: dict | None = None,
    ) -> AdapterVersion:
        """注册一个 Adapter 版本.

        Args:
            version: 版本号.
            base_model: 基座模型.
            adapter_path: Adapter 权重路径.
            tenant_id: 租户 ID.
            method: 微调方式.
            framework: 框架.
            loop_task_id: 关联的闭环任务 ID.
            metrics: 训练指标.

        Returns:
            创建的版本记录.
        """
        key = self._model_key(base_model, method, framework, tenant_id)
        record = AdapterVersion(
            version=version,
            base_model=base_model,
            method=method,
            framework=framework,
            tenant_id=tenant_id,
            adapter_path=adapter_path,
            loop_task_id=loop_task_id,
            metrics=metrics or {},
        )
        with self._lock:
            if key not in self._versions:
                self._versions[key] = []
            self._versions[key].append(record)
            self._active[key] = version
            self._save_to_disk()
        logger.info(
            f"Adapter 版本已注册: key={key}, version={version}"
        )
        return record

    # ============================================================
    # 查询版本历史
    # ============================================================
    def list_versions(
        self, base_model: str, method: str = "", framework: str = "",
        tenant_id: str = "default",
    ) -> list[dict]:
        """查询模型的版本历史.

        Args:
            base_model: 基座模型.
            method: 微调方式（空表示全部）.
            framework: 框架（空表示全部）.
            tenant_id: 租户 ID.

        Returns:
            版本记录列表（按时间正序）.
        """
        with self._lock:
            result: list[dict] = []
            for key, versions in self._versions.items():
                # 按 key 解析匹配
                parts = key.split("/")
                if len(parts) != 4:
                    continue
                k_tenant, k_model, k_method, k_framework = parts
                if k_model != base_model or k_tenant != tenant_id:
                    continue
                if method and k_method != method:
                    continue
                if framework and k_framework != framework:
                    continue
                for v in versions:
                    item = v.to_dict()
                    item["isActive"] = (v.version == self._active.get(key))
                    result.append(item)
            result.sort(key=lambda x: x["createdAt"])
            return result

    # ============================================================
    # 版本对比
    # ============================================================
    def compare_versions(
        self, base_model: str, version_a: str, version_b: str,
        tenant_id: str = "default",
    ) -> dict:
        """对比两个版本的差异.

        Args:
            base_model: 基座模型.
            version_a: 版本 A.
            version_b: 版本 B.
            tenant_id: 租户 ID.

        Returns:
            对比结果字典.
        """
        with self._lock:
            va = self._find_version(base_model, version_a, tenant_id)
            vb = self._find_version(base_model, version_b, tenant_id)
            if va is None or vb is None:
                missing = []
                if va is None:
                    missing.append(version_a)
                if vb is None:
                    missing.append(version_b)
                return {
                    "error": f"版本不存在: {missing}",
                    "versionA": version_a,
                    "versionB": version_b,
                }
            return {
                "versionA": va.to_dict(),
                "versionB": vb.to_dict(),
                "metricsDiff": self._diff_metrics(
                    va.metrics, vb.metrics
                ),
                "createdAtDiff": (
                    vb.created_at - va.created_at
                ).total_seconds(),
            }

    def _find_version(
        self, base_model: str, version: str, tenant_id: str,
    ) -> Optional[AdapterVersion]:
        """查找指定版本记录."""
        for key, versions in self._versions.items():
            parts = key.split("/")
            if len(parts) != 4:
                continue
            k_tenant, k_model, _, _ = parts
            if k_model != base_model or k_tenant != tenant_id:
                continue
            for v in versions:
                if v.version == version:
                    return v
        return None

    @staticmethod
    def _diff_metrics(a: dict, b: dict) -> dict:
        """计算指标差异."""
        keys = set(a.keys()) | set(b.keys())
        diff = {}
        for k in keys:
            va = a.get(k)
            vb = b.get(k)
            if isinstance(va, (int, float)) and isinstance(vb, (int, float)):
                diff[k] = {
                    "a": va, "b": vb,
                    "diff": vb - va,
                    "diffPercent": (
                        (vb - va) / va * 100 if va else 0.0
                    ),
                }
            else:
                diff[k] = {"a": va, "b": vb}
        return diff

    # ============================================================
    # 回滚到指定版本
    # ============================================================
    def rollback(
        self, base_model: str, version: str, method: str = "",
        framework: str = "", tenant_id: str = "default",
    ) -> dict:
        """回滚到指定版本.

        Args:
            base_model: 基座模型.
            version: 目标版本号.
            method/framework: 微调方式/框架（用于定位模型键）.
            tenant_id: 租户 ID.

        Returns:
            回滚结果.
        """
        with self._lock:
            target = self._find_version(base_model, version, tenant_id)
            if target is None:
                return {
                    "success": False,
                    "error": f"版本 {version} 不存在",
                }
            # 定位模型键
            keys = [
                k for k in self._versions.keys()
                if target in self._versions[k]
            ]
            if not keys:
                return {
                    "success": False,
                    "error": "无法定位版本所属模型",
                }
            key = keys[0]
            previous_active = self._active.get(key)
            self._active[key] = version
            # 标记其他版本为非激活
            for v in self._versions[key]:
                v.is_active = (v.version == version)
            self._save_to_disk()
            return {
                "success": True,
                "previousActive": previous_active,
                "currentActive": version,
                "message": f"已回滚到版本 {version}",
            }

    # ============================================================
    # 获取当前激活版本
    # ============================================================
    def get_active_version(
        self, base_model: str, method: str = "", framework: str = "",
        tenant_id: str = "default",
    ) -> Optional[dict]:
        """获取当前激活版本."""
        with self._lock:
            for key, version_no in self._active.items():
                parts = key.split("/")
                if len(parts) != 4:
                    continue
                k_tenant, k_model, k_method, k_framework = parts
                if k_model != base_model or k_tenant != tenant_id:
                    continue
                if method and k_method != method:
                    continue
                if framework and k_framework != framework:
                    continue
                v = self._find_version(base_model, version_no, tenant_id)
                if v:
                    return v.to_dict()
            return None

    # ============================================================
    # 持久化
    # ============================================================
    def _save_to_disk(self) -> None:
        """将元数据保存到磁盘."""
        try:
            path = os.path.join(self.storage_dir, "adapters.json")
            data = {
                "versions": {
                    key: [v.to_dict() for v in versions]
                    for key, versions in self._versions.items()
                },
                "active": self._active,
            }
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except OSError as e:
            logger.warning(f"保存 Adapter 元数据失败: {e}")

    def _load_from_disk(self) -> None:
        """从磁盘加载元数据."""
        try:
            path = os.path.join(self.storage_dir, "adapters.json")
            if not os.path.exists(path):
                return
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            for key, versions in data.get("versions", {}).items():
                self._versions[key] = [
                    AdapterVersion.from_dict(v) for v in versions
                ]
            self._active = data.get("active", {})
        except (OSError, json.JSONDecodeError) as e:
            logger.warning(f"加载 Adapter 元数据失败: {e}")

    # ============================================================
    # 统计
    # ============================================================
    def stats(self) -> dict:
        """返回统计信息."""
        with self._lock:
            return {
                "totalModels": len(self._versions),
                "totalVersions": sum(
                    len(v) for v in self._versions.values()
                ),
            }