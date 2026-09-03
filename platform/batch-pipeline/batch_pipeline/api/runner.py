"""批次提交与后台执行：进程内注册表 + 串行执行线程.

薄壳约束（设计 §3.2）：批处理主体是 run_pipeline 进程（CLI / K8s Job），
API 提交的批次在服务内后台线程串行执行——串行是刻意约束：run_pipeline
按批次配置 root logger（run/<batch>/logs/pipeline.log），并发执行会交叉
写 handler；吞吐扩展靠横向扩容 API 副本 + K8s Job，不在单进程内并发。

注册表是内存态（重启即失）；批次终态以磁盘 manifest.json 为准，
查询路由在注册表未命中时回读 manifest。
"""

from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional

from ..pipeline import run_pipeline


@dataclass
class BatchRecord:
    batch_id: str
    tenant_id: str
    status: str = "queued"  # queued -> running -> success | failed
    submitted_at: str = ""
    finished_at: str = ""
    error: Optional[str] = None
    config: dict[str, Any] = field(default_factory=dict)


def _utc_now() -> str:
    from ..helpers import utc_ts

    return utc_ts()


class BatchRunner:
    """提交批次并串行执行；线程安全."""

    def __init__(self) -> None:
        self._records: dict[str, BatchRecord] = {}
        self._lock = threading.Lock()
        self._exec_lock = threading.Lock()

    def submit(
        self, config: dict[str, Any], tenant_id: str, batch_id: Optional[str] = None
    ) -> BatchRecord:
        """登记批次并启动后台执行线程；立即返回记录.

        config 是服务端基础配置（可含请求体业务字段覆盖项，路由层已剔除
        tenant / storage / run_dir 等路径字段）；租户以 config tenant 段传入
        run_pipeline，由内核入口统一 apply_tenant 分区——runner 不预分区，
        apply_tenant 会对已分区路径重复追加（run/<tenant>/<tenant>/）。
        服务进程自身不应设置 BATCH_PIPELINE_TENANT_ID，否则会覆盖每请求租户。
        """
        if not batch_id:
            batch_id = "api-" + uuid.uuid4().hex[:12]
        with self._lock:
            existing = self._records.get(batch_id)
            if existing is not None and existing.status in ("queued", "running"):
                raise ConflictError(f"批次 {batch_id} 正在执行中")
            record = BatchRecord(
                batch_id=batch_id,
                tenant_id=tenant_id,
                status="queued",
                submitted_at=_utc_now(),
                config=config,
            )
            self._records[batch_id] = record
        thread = threading.Thread(
            target=self._execute, args=(record,), daemon=True, name=f"batch-{batch_id}"
        )
        thread.start()
        return record

    def get(self, batch_id: str, tenant_id: Optional[str] = None) -> Optional[BatchRecord]:
        """按 id 取记录；tenant_id 提供时跨租户记录视为不存在（隔离查询）."""
        with self._lock:
            record = self._records.get(batch_id)
            if record is not None and tenant_id is not None and record.tenant_id != tenant_id:
                return None
            return record

    def list_for_tenant(self, tenant_id: str) -> list[BatchRecord]:
        with self._lock:
            return [r for r in self._records.values() if r.tenant_id == tenant_id]

    def _execute(self, record: BatchRecord) -> None:
        with self._exec_lock:
            record.status = "running"
            try:
                cfg = {**record.config, "tenant": {"enabled": True, "id": record.tenant_id}}
                rc = run_pipeline(cfg, record.batch_id, "")
                record.status = "success" if rc == 0 else "failed"
                if rc != 0:
                    record.error = "pipeline exited with code " + str(rc)
            except Exception as exc:  # noqa: BLE001 - 后台线程必须收敛异常
                record.status = "failed"
                record.error = f"{type(exc).__name__}: {exc}"
            finally:
                record.finished_at = _utc_now()


class ConflictError(Exception):
    """同 batch_id 已有在途批次时抛出（路由层转 409）."""
