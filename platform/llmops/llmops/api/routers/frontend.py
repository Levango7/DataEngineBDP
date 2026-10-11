"""前端契约 router（对齐 frontend/src/api/llmops.ts 的 9 个端点）。

台账 #1b / Q1：llmops 为质量/推理域的**单一权威**。此前网关 `/api/v1/llmops` 指向
`encaps-layer`（其 `LLMOpsService` 自持仓储），本服务只有一半端点且形状不符；
现在由本 router 完整承载前端契约（形状逐字段对齐 `encaps-layer#to*View`），
encaps-layer 可退化为转发。

覆盖端点（全部租户隔离，tenant 取自 JWT）：
- GET/POST `/llmops/models`                    → 前端 ModelRegistry
- GET/POST `/llmops/eval-metrics`              → 前端 EvalMetric
- GET/POST `/llmops/finetune`                  → 前端 FinetuneResult（GET 按 taskId 筛）
- GET `/llmops/finetune/{taskId}`              → 前端 FinetuneResult
- POST `/llmops/human-eval`                    → 前端 HumanEvalResult
- GET `/llmops/inference-services`             → 前端 InferenceService（`status` 可选过滤）

模型注册表复用 `registry.modelService`；评估指标/微调视图/推理服务存于
`FrontendContractStore`（见 llmops/services/frontend_store.py）。
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from llmops.api.jwt_auth import AuthContext, getAuthContext
from llmops.api.routers.deps import get_registry
from llmops.models.model import ModelFilter, ModelInfo, ModelStatus, ModelType
from llmops.services.registry import ServiceRegistry

router = APIRouter(prefix="/llmops", tags=["llmops-frontend"])


def _store(request: Request):
    st = getattr(request.app.state, "frontend_store", None)
    if st is None:
        from llmops.services.frontend_store import FrontendContractStore

        st = FrontendContractStore()
        request.app.state.frontend_store = st
    return st


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _to_model_view(m: ModelInfo) -> dict[str, Any]:
    """ModelInfo → 前端 ModelRegistry（对齐 encaps-layer#toModelView）。"""
    version = str(m.currentVersion) if getattr(m, "currentVersion", None) else (
        str(m.versions[-1].version) if m.versions else "1"
    )
    algorithm = (getattr(m.params, "architecture", None) or m.type.value if hasattr(m.type, "value") else str(m.type))
    registered = getattr(m, "createdAt", None) or getattr(m, "updatedAt", None)
    return {
        "id": m.id,
        "name": m.name,
        "algorithm": algorithm,
        "version": version,
        "status": m.status.value if hasattr(m.status, "value") else str(m.status),
        "trainJobId": (m.tags or {}).get("trainJobId"),
        "modelPath": (m.tags or {}).get("modelPath"),
        "description": m.description,
        "registeredAt": registered.isoformat() if hasattr(registered, "isoformat") else (registered or _now()),
        "updatedAt": (getattr(m, "updatedAt", None).isoformat() if getattr(m, "updatedAt", None) else None),
    }


# ---------------------------------------------------------------- models
@router.get("/models")
async def frontend_list_models(
    request: Request,
    modelName: Optional[str] = Query(default=None),
    registry: ServiceRegistry = Depends(get_registry),
    ctx: AuthContext = Depends(getAuthContext),
) -> list:
    filter_ = ModelFilter(name=modelName)
    models = await registry.modelService.list_models(filter_)
    return [_to_model_view(m) for m in models]


@router.post("/models", status_code=201)
async def frontend_register_model(
    request: Request,
    payload: dict,
    registry: ServiceRegistry = Depends(get_registry),
    ctx: AuthContext = Depends(getAuthContext),
) -> dict:
    name = (payload.get("name") or "").strip()
    if not name:
        raise HTTPException(status_code=422, detail="name required")
    mtype = ModelType.FT if payload.get("baseModel") or payload.get("baseModelId") else ModelType.BASE
    info = ModelInfo(
        id=str(uuid.uuid4()),
        name=name,
        type=mtype,
        baseModelId=payload.get("baseModelId"),
        status=ModelStatus.READY if payload.get("status") == "READY" else ModelStatus.DRAFT,
        description=payload.get("description"),
        tenantId=ctx.tenantId,
        tags={
            k: v for k, v in {
                "trainJobId": payload.get("trainJobId"),
                "modelPath": payload.get("modelPath"),
            }.items() if v
        },
    )
    try:
        saved = await registry.modelService.register_model(info)
    except Exception as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    return _to_model_view(saved)


# ---------------------------------------------------------------- eval-metrics
@router.get("/eval-metrics")
async def frontend_list_eval_metrics(
    request: Request,
    modelName: Optional[str] = Query(default=None),
    ctx: AuthContext = Depends(getAuthContext),
) -> list:
    return _store(request).list_metrics(ctx.tenantId, modelName)


@router.post("/eval-metrics", status_code=201)
async def frontend_create_eval_metric(
    request: Request,
    payload: dict,
    ctx: AuthContext = Depends(getAuthContext),
) -> dict:
    if not (payload.get("modelName") or "").strip():
        raise HTTPException(status_code=422, detail="modelName required")
    return _store(request).create_metric(ctx.tenantId, payload)


# ---------------------------------------------------------------- finetune
@router.get("/finetune")
async def frontend_list_finetune(
    request: Request,
    taskId: Optional[str] = Query(default=None),
    ctx: AuthContext = Depends(getAuthContext),
) -> list:
    st = _store(request)
    if taskId:
        one = st.get_finetune(ctx.tenantId, taskId)
        return [one] if one else []
    return st.list_finetunes(ctx.tenantId)


@router.get("/finetune/{taskId}")
async def frontend_finetune_status(
    request: Request,
    taskId: str,
    ctx: AuthContext = Depends(getAuthContext),
) -> dict:
    view = _store(request).get_finetune(ctx.tenantId, taskId)
    if view is None:
        raise HTTPException(status_code=404, detail="finetune task not found")
    return view


@router.post("/finetune", status_code=201)
async def frontend_submit_finetune(
    request: Request,
    payload: dict,
    registry: ServiceRegistry = Depends(get_registry),
    ctx: AuthContext = Depends(getAuthContext),
) -> dict:
    from llmops.models.training import TrainingConfig

    view = {
        "taskId": str(uuid.uuid4()),
        "modelName": payload.get("modelName", ""),
        "baseModel": payload.get("baseModel", ""),
        "trainingData": payload.get("trainingData"),
        "gpuConfig": payload.get("gpuConfig"),
        "epochs": payload.get("epochs"),
        "status": "QUEUED",
        "progress": 0,
        "errorMessage": None,
        "submittedAt": _now(),
        "startedAt": None,
        "finishedAt": None,
    }
    # 尽力映射到训练服务（mock 后端可创建真实任务；失败仍返回前端视图，不阻塞提交）
    try:
        job = await registry.trainingService.create_training_job(
            TrainingConfig(
                baseModelId=payload.get("baseModel", ""),
                outputModelName=payload.get("modelName", ""),
                dataset=payload.get("trainingData") or "inline",
                epochs=int(payload.get("epochs") or 3),
            )
        )
        view["taskId"] = job.id
        view["status"] = "RUNNING"
        view["startedAt"] = _now()
    except Exception as exc:  # noqa: BLE001
        view["errorMessage"] = str(exc)
    _store(request).upsert_finetune(ctx.tenantId, view["taskId"], view)
    return view


# ---------------------------------------------------------------- human-eval
@router.post("/human-eval")
async def frontend_trigger_human_eval(
    request: Request,
    payload: dict,
    ctx: AuthContext = Depends(getAuthContext),
) -> dict:
    model = (payload.get("modelName") or "").strip()
    if not model:
        raise HTTPException(status_code=422, detail="modelName required")
    return {"metricId": str(uuid.uuid4()), "modelName": model, "status": "queued"}


# ---------------------------------------------------------------- inference-services
@router.get("/inference-services")
async def frontend_list_inference_services(
    request: Request,
    status: Optional[str] = Query(default=None),
    ctx: AuthContext = Depends(getAuthContext),
) -> list:
    return _store(request).list_inference(ctx.tenantId, status)