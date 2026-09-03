"""批次路由：提交 / 列表 / 状态 / 质量报告（租户隔离）.

查询一律按请求租户过滤——run 根目录按租户分区（run/<tenant>/<batch>/），
跨租户的 batch_id 天然不可见，无需额外过滤逻辑。
"""

from __future__ import annotations

import os
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Request

from ...helpers import json_load
from ...tenant import TenantError, validate_tenant_id
from ..deps import getTenantId
from ..runner import BatchRunner, ConflictError

router = APIRouter(tags=["batches"])

# 请求体 config 覆盖项的剔除清单：租户与路径类字段由服务端按租户强制分区，
# 请求体提供会构成路径逃逸面（如 run_dir 指向 ".." 穿透到其他租户目录）
_OVERRIDE_BLOCKED_TOP = ("tenant", "storage")
_OVERRIDE_BLOCKED_SUB = {"pipeline": ("run_dir",), "incremental": ("state_dir",)}


def _mergeOverride(config: dict[str, Any], override: dict[str, Any]) -> None:
    """把请求体 config 覆盖项并入基础配置（受限一级深合并，原地修改）.

    剔除清单之外的子段是 dict 时与基础配置按键合并，避免部分覆盖整段
    替换丢掉服务端字段（如 body 只传 pipeline.batch_id 时丢掉 run_dir）。
    """
    for key, value in override.items():
        if key in _OVERRIDE_BLOCKED_TOP:
            continue
        blocked = _OVERRIDE_BLOCKED_SUB.get(key)
        if isinstance(value, dict):
            merged = dict(config[key]) if isinstance(config.get(key), dict) else {}
            for sub_key, sub_value in value.items():
                if blocked and sub_key in blocked:
                    continue
                merged[sub_key] = sub_value
            config[key] = merged
        else:
            config[key] = value


def _getRunner(request: Request) -> BatchRunner:
    return request.app.state.runner


def _tenantBatchDir(request: Request, tenant_id: str, batch_id: str) -> str:
    try:
        validate_tenant_id(tenant_id)
    except TenantError as exc:
        raise HTTPException(status_code=403, detail=f"租户 id 非法: {exc}") from exc
    if not batch_id or "/" in batch_id or "\\" in batch_id or batch_id in (".", ".."):
        raise HTTPException(status_code=400, detail=f"批次 id 非法: {batch_id!r}")
    return os.path.join(request.app.state.settings.runRoot, tenant_id, batch_id)


def _loadManifest(batch_dir: str) -> Optional[dict[str, Any]]:
    path = os.path.join(batch_dir, "manifest.json")
    if not os.path.isfile(path):
        return None
    manifest = json_load(path)
    return manifest if isinstance(manifest, dict) else None


@router.post("/batches", status_code=202)
def submitBatch(
    request: Request,
    body: Optional[dict[str, Any]] = None,
    tenant_id: str = Depends(getTenantId),
) -> dict[str, Any]:
    """提交一个批次（202 Accepted；执行异步进行）.

    Body（均可省略）：{"batch_id": "...", "config": {...}}。
    config 缺省用服务端基础配置（PIPELINE_CONFIG）；提供时仅允许业务字段
    覆盖——tenant / storage / pipeline.run_dir / incremental.state_dir 由
    服务端剔除并按租户强制分区，其余字段与基础配置一级合并。
    """
    runner = _getRunner(request)
    body = body or {}
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="请求体必须是 JSON 对象")
    settings = request.app.state.settings
    config: dict[str, Any] = json_load(settings.configPath)
    override = body.get("config")
    if override is not None:
        if not isinstance(override, dict):
            raise HTTPException(status_code=400, detail="config 必须是 JSON 对象")
        _mergeOverride(config, override)
    batch_id = str(body.get("batch_id") or "").strip() or None
    if batch_id is not None:
        if "/" in batch_id or "\\" in batch_id or batch_id in (".", ".."):
            raise HTTPException(status_code=400, detail=f"批次 id 非法: {batch_id!r}")
    try:
        record = runner.submit(config, tenant_id, batch_id)
    except ConflictError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return {
        "batch_id": record.batch_id,
        "tenant_id": record.tenant_id,
        "status": record.status,
        "submitted_at": record.submitted_at,
    }


@router.get("/batches")
def listBatches(request: Request, tenant_id: str = Depends(getTenantId)) -> dict[str, Any]:
    runner = _getRunner(request)
    items: list[dict[str, Any]] = []
    seen: set[str] = set()
    for record in runner.list_for_tenant(tenant_id):
        seen.add(record.batch_id)
        items.append(
            {
                "batch_id": record.batch_id,
                "status": record.status,
                "submitted_at": record.submitted_at,
                "finished_at": record.finished_at,
                "error": record.error,
            }
        )
    # 注册表是内存态：重启后回读磁盘 manifest 补全历史批次
    tenant_dir = os.path.join(request.app.state.settings.runRoot, tenant_id)
    if os.path.isdir(tenant_dir):
        for batch_id in sorted(os.listdir(tenant_dir)):
            if batch_id in seen:
                continue
            manifest = _loadManifest(os.path.join(tenant_dir, batch_id))
            if manifest is None:
                continue
            items.append(
                {
                    "batch_id": batch_id,
                    "status": manifest.get("status", "unknown"),
                    "submitted_at": manifest.get("started_at", ""),
                    "finished_at": manifest.get("finished_at", ""),
                    "error": manifest.get("error"),
                }
            )
    items.sort(key=lambda x: x.get("submitted_at") or "", reverse=True)
    return {"tenant_id": tenant_id, "batches": items}


@router.get("/batches/{batch_id}")
def batchStatus(
    batch_id: str,
    request: Request,
    tenant_id: str = Depends(getTenantId),
) -> dict[str, Any]:
    runner = _getRunner(request)
    batch_dir = _tenantBatchDir(request, tenant_id, batch_id)
    record = runner.get(batch_id, tenant_id)
    manifest = _loadManifest(batch_dir)
    if record is None and manifest is None:
        raise HTTPException(status_code=404, detail=f"批次不存在: {batch_id}")
    if record is not None and manifest is None:
        return {
            "batch_id": batch_id,
            "tenant_id": tenant_id,
            "status": record.status,
            "submitted_at": record.submitted_at,
            "finished_at": record.finished_at,
            "error": record.error,
        }
    stages = [
        {
            "name": s.get("name"),
            "status": s.get("status"),
            "rows_in": s.get("rows_in"),
            "rows_out": s.get("rows_out"),
            "duration_ms": s.get("duration_ms"),
        }
        for s in (manifest.get("stages") or [])
    ]
    return {
        "batch_id": batch_id,
        "tenant_id": tenant_id,
        "status": manifest.get("status", record.status if record else "unknown"),
        "started_at": manifest.get("started_at"),
        "finished_at": manifest.get("finished_at"),
        "error": manifest.get("error") or (record.error if record else None),
        "stages": stages,
    }


@router.get("/batches/{batch_id}/quality")
def batchQuality(
    batch_id: str,
    request: Request,
    tenant_id: str = Depends(getTenantId),
) -> dict[str, Any]:
    batch_dir = _tenantBatchDir(request, tenant_id, batch_id)
    manifest = _loadManifest(batch_dir)
    if manifest is None:
        raise HTTPException(status_code=404, detail=f"批次不存在: {batch_id}")
    quality = manifest.get("quality")
    if not quality:
        raise HTTPException(
            status_code=404, detail=f"批次 {batch_id} 尚无质量报告（validate 阶段未完成或失败）"
        )
    return {"batch_id": batch_id, "tenant_id": tenant_id, "quality": quality}
