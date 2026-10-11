"""前端契约视图的租户内存存储（台账 #1b / llmops 单一权威）。

背景：网关 `/api/v1/llmops` 此前指向 `encaps-layer`（其 `LLMOpsService` 自持 4 个仓储，
与本服务重复）。裁决「以专用服务为权威」后，`llmops` 需完整承载
`frontend/src/api/llmops.ts` 的 9 个端点及其**前端视图形状**。

本模块提供三类前端视图实体的最小租户隔离内存存储：
- 评估指标（`EvalMetric`：id/modelName/modelVersion/evalType/accuracy/...）
- 微调任务视图（`FinetuneResult`：taskId/modelName/baseModel/...）
- 推理服务（`InferenceService`：id/serviceName/modelName/.../endpoint）

模型注册表（`/llmops/models`）复用既有 `registry.modelService`，微调任务提交复用
`registry.trainingService`；本模块只承载**前端视图专有**、既有服务模型不覆盖的字段。

说明：进程内存实现，满足当前 mock/mlflow 两种后端下的契约对齐；后续若需持久化，
可替换为与 `repositories` 同构的存储而不改端点契约。
"""

from __future__ import annotations

from datetime import datetime, timezone
import threading
from typing import Any, Optional
import uuid


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


class _TenantBucket(dict):
    """tenantId → 实体 dict 的薄封装（值由各 store 自行解释）。"""


class FrontendContractStore:
    """租户隔离的前端视图实体存储（进程内、线程安全）。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._metrics: dict[str, list[dict[str, Any]]] = _TenantBucket()
        self._finetunes: dict[str, dict[str, dict[str, Any]]] = _TenantBucket()
        self._inference: dict[str, list[dict[str, Any]]] = _TenantBucket()

    # ---------- 评估指标 ----------
    def create_metric(self, tenant: str, payload: dict[str, Any]) -> dict[str, Any]:
        metric: dict[str, Any] = {
            "id": str(uuid.uuid4()),
            "modelName": payload.get("modelName", ""),
            "modelVersion": payload.get("modelVersion"),
            "evalType": payload.get("evalType"),
            "accuracy": payload.get("accuracy"),
            "hallucinationRate": payload.get("hallucinationRate"),
            "baseLiftPt": payload.get("baseLiftPt"),
            "dataset": payload.get("dataset"),
            "createdAt": _now(),
        }
        with self._lock:
            self._metrics.setdefault(tenant, []).append(metric)
        return dict(metric)

    def list_metrics(self, tenant: str, model_name: Optional[str] = None) -> list[dict[str, Any]]:
        with self._lock:
            items = list(self._metrics.get(tenant, []))
        if model_name:
            items = [m for m in items if m.get("modelName") == model_name]
        return [dict(m) for m in items]

    # ---------- 微调任务视图 ----------
    def upsert_finetune(self, tenant: str, task_id: str, view: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            bucket = self._finetunes.setdefault(tenant, {})
            existing = bucket.get(task_id, {})
            merged = {**existing, **view, "taskId": task_id}
            bucket[task_id] = merged
        return dict(merged)

    def list_finetunes(self, tenant: str) -> list[dict[str, Any]]:
        with self._lock:
            return [dict(v) for v in self._finetunes.get(tenant, {}).values()]

    def get_finetune(self, tenant: str, task_id: str) -> Optional[dict[str, Any]]:
        with self._lock:
            v = self._finetunes.get(tenant, {}).get(task_id)
            return dict(v) if v is not None else None

    # ---------- 推理服务 ----------
    def list_inference(self, tenant: str, status: Optional[str] = None) -> list[dict[str, Any]]:
        with self._lock:
            items = list(self._inference.get(tenant, []))
        if status:
            items = [s for s in items if str(s.get("status", "")).upper() == status.upper()]
        return [dict(s) for s in items]
