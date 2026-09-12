"""评测报告版本化注册表.

管理评测报告的版本化存储，与 Adapter 版本关联：
- 每次评测生成一份报告，分配版本号
- 报告与 Adapter 版本关联，可追溯
- 支持版本历史查询、版本对比

存储：内存字典 + 文件系统元数据 JSON。
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
import threading
from typing import Any, Optional

from loguru import logger


class ReportVersion:
    """评测报告版本记录."""

    def __init__(
        self,
        version: str,
        adapter_version: str,
        dataset: str,
        tenant_id: str,
        loop_task_id: str = "",
        accuracy: float = 0.0,
        recall: float = 0.0,
        f1: float = 0.0,
        latency_p95: float = 0.0,
        cost: float = 0.0,
        hallucination: float = 0.0,
        created_at: datetime | None = None,
    ):
        self.version = version
        self.adapter_version = adapter_version
        self.dataset = dataset
        self.tenant_id = tenant_id
        self.loop_task_id = loop_task_id
        self.accuracy = accuracy
        self.recall = recall
        self.f1 = f1
        self.latency_p95 = latency_p95
        self.cost = cost
        self.hallucination = hallucination
        self.created_at = created_at or datetime.now(timezone.utc)

    def to_dict(self) -> dict[str, Any]:
        """序列化为字典."""
        return {
            "version": self.version,
            "adapterVersion": self.adapter_version,
            "dataset": self.dataset,
            "tenantId": self.tenant_id,
            "loopTaskId": self.loop_task_id,
            "accuracy": self.accuracy,
            "recall": self.recall,
            "f1": self.f1,
            "latencyP95": self.latency_p95,
            "cost": self.cost,
            "hallucination": self.hallucination,
            "createdAt": self.created_at.isoformat(),
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ReportVersion":
        """从字典反序列化."""
        return cls(
            version=data["version"],
            adapter_version=data.get("adapterVersion", ""),
            dataset=data.get("dataset", ""),
            tenant_id=data.get("tenantId", ""),
            loop_task_id=data.get("loopTaskId", ""),
            accuracy=float(data.get("accuracy", 0.0)),
            recall=float(data.get("recall", 0.0)),
            f1=float(data.get("f1", 0.0)),
            latency_p95=float(data.get("latencyP95", 0.0)),
            cost=float(data.get("cost", 0.0)),
            hallucination=float(data.get("hallucination", 0.0)),
            created_at=datetime.fromisoformat(data["createdAt"]) if data.get("createdAt") else None,
        )


class ReportRegistry:
    """评测报告版本化注册表."""

    def __init__(self, storage_dir: str = "/tmp/finetune-loop/registry"):
        self.storage_dir = storage_dir
        # 报告键 → 版本列表
        self._versions: dict[str, list[ReportVersion]] = {}
        self._lock = threading.RLock()

        os.makedirs(storage_dir, exist_ok=True)
        self._load_from_disk()
        logger.info("ReportRegistry 初始化完成")

    def _report_key(
        self,
        adapter_version: str,
        dataset: str,
        tenant_id: str,
    ) -> str:
        """构造报告唯一键."""
        return f"{tenant_id}/{adapter_version}/{dataset}"

    # ============================================================
    # 分配版本号
    # ============================================================
    def allocate_version(
        self,
        adapter_version: str,
        dataset: str,
        tenant_id: str,
    ) -> str:
        """分配新版本号.

        格式：r{序号}，如 r1, r2, r3...
        """
        key = self._report_key(adapter_version, dataset, tenant_id)
        with self._lock:
            count = len(self._versions.get(key, []))
            return f"r{count + 1}"

    # ============================================================
    # 注册报告版本
    # ============================================================
    def register(
        self,
        version: str,
        adapter_version: str,
        dataset: str,
        tenant_id: str,
        loop_task_id: str = "",
        accuracy: float = 0.0,
        recall: float = 0.0,
        f1: float = 0.0,
        latency_p95: float = 0.0,
        cost: float = 0.0,
        hallucination: float = 0.0,
    ) -> ReportVersion:
        """注册一个评测报告版本."""
        key = self._report_key(adapter_version, dataset, tenant_id)
        record = ReportVersion(
            version=version,
            adapter_version=adapter_version,
            dataset=dataset,
            tenant_id=tenant_id,
            loop_task_id=loop_task_id,
            accuracy=accuracy,
            recall=recall,
            f1=f1,
            latency_p95=latency_p95,
            cost=cost,
            hallucination=hallucination,
        )
        with self._lock:
            if key not in self._versions:
                self._versions[key] = []
            self._versions[key].append(record)
            self._save_to_disk()
        logger.info(f"评测报告已注册: key={key}, version={version}")
        return record

    # ============================================================
    # 查询版本历史
    # ============================================================
    def list_versions(
        self,
        adapter_version: str = "",
        dataset: str = "",
        tenant_id: str = "default",
    ) -> list[dict]:
        """查询评测报告版本历史."""
        with self._lock:
            result: list[dict] = []
            for key, versions in self._versions.items():
                parts = key.split("/")
                if len(parts) != 3:
                    continue
                k_tenant, k_adapter, k_dataset = parts
                if tenant_id and k_tenant != tenant_id:
                    continue
                if adapter_version and k_adapter != adapter_version:
                    continue
                if dataset and k_dataset != dataset:
                    continue
                for v in versions:
                    result.append(v.to_dict())
            result.sort(key=lambda x: x["createdAt"])
            return result

    # ============================================================
    # 版本对比
    # ============================================================
    def compare_versions(
        self,
        version_a: str,
        version_b: str,
        tenant_id: str = "default",
    ) -> dict:
        """对比两个报告版本."""
        with self._lock:
            va = self._find_version(version_a, tenant_id)
            vb = self._find_version(version_b, tenant_id)
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
                "metricsDiff": self._diff_metrics(va, vb),
            }

    def _find_version(
        self,
        version: str,
        tenant_id: str,
    ) -> Optional[ReportVersion]:
        """查找指定版本记录."""
        for key, versions in self._versions.items():
            parts = key.split("/")
            if len(parts) != 3:
                continue
            if parts[0] != tenant_id:
                continue
            for v in versions:
                if v.version == version:
                    return v
        return None

    @staticmethod
    def _diff_metrics(a: ReportVersion, b: ReportVersion) -> dict:
        """计算六指标差异."""
        metrics = [
            ("accuracy", a.accuracy, b.accuracy),
            ("recall", a.recall, b.recall),
            ("f1", a.f1, b.f1),
            ("latencyP95", a.latency_p95, b.latency_p95),
            ("cost", a.cost, b.cost),
            ("hallucination", a.hallucination, b.hallucination),
        ]
        result = {}
        for name, va, vb in metrics:
            diff = vb - va
            result[name] = {
                "a": va,
                "b": vb,
                "diff": diff,
                "diffPercent": (diff / va * 100) if va else 0.0,
            }
        return result

    # ============================================================
    # 持久化
    # ============================================================
    def _save_to_disk(self) -> None:
        """保存到磁盘."""
        try:
            path = os.path.join(self.storage_dir, "reports.json")
            data = {
                "versions": {key: [v.to_dict() for v in versions] for key, versions in self._versions.items()},
            }
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except OSError as e:
            logger.warning(f"保存报告元数据失败: {e}")

    def _load_from_disk(self) -> None:
        """从磁盘加载."""
        try:
            path = os.path.join(self.storage_dir, "reports.json")
            if not os.path.exists(path):
                return
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            for key, versions in data.get("versions", {}).items():
                self._versions[key] = [ReportVersion.from_dict(v) for v in versions]
        except (OSError, json.JSONDecodeError) as e:
            logger.warning(f"加载报告元数据失败: {e}")

    # ============================================================
    # 统计
    # ============================================================
    def stats(self) -> dict:
        """返回统计信息."""
        with self._lock:
            return {
                "totalReports": sum(len(v) for v in self._versions.values()),
                "totalKeys": len(self._versions),
            }
