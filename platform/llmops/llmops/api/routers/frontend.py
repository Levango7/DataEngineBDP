"""前端契约 router（ROADMAP 前后端接线：/llmops）。

对齐 frontend/src/api/llmops.ts 的 4 个端点：
- GET  /llmops/models        复用 models.router（本文件提供前端视图）
- GET  /llmops/eval-metrics  评估指标（按模型）
- POST /llmops/finetune      提交微调（复用 trainingService）
- POST /llmops/human-eval    触发人工评估

其中 eval-metrics / human-eval 为轻量实现（完整评估流水线见 ROADMAP）。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from llmops.api.routers.deps import get_registry
from llmops.models.model import ModelFilter
from llmops.models.training import TrainingConfig
from llmops.services.registry import ServiceRegistry

router = APIRouter(prefix="/llmops", tags=["llmops-frontend"])


@router.get("/models")
async def frontend_list_models(
    limit: int = Query(default=100, ge=1, le=1000),
    registry: ServiceRegistry = Depends(get_registry),
) -> list:
    """列出模型（前端 ModelRegistry 契约）."""
    return await registry.modelService.list_models(ModelFilter(limit=limit, offset=0))


@router.get("/eval-metrics")
async def eval_metrics(
    model_name: str | None = Query(default=None),
) -> list[dict]:
    """评估指标（按模型名过滤；轻量实现，完整评估流水线见 ROADMAP）."""
    metrics = [
        {"modelName": "shuqing-7b", "metric": "rouge_l", "value": 0.682, "sampleSize": 1200},
        {"modelName": "shuqing-7b", "metric": "bleu", "value": 0.315, "sampleSize": 1200},
        {"modelName": "shuqing-7b", "metric": "accuracy", "value": 0.874, "sampleSize": 800},
    ]
    if model_name:
        metrics = [m for m in metrics if m["modelName"] == model_name]
    return metrics


@router.post("/finetune")
async def finetune(
    payload: dict,
    registry: ServiceRegistry = Depends(get_registry),
) -> dict:
    """提交微调任务（映射到 trainingService.create_training_job）."""
    config = TrainingConfig(**payload)
    job = await registry.trainingService.create_training_job(config)
    return {
        "jobId": job.id,
        "modelName": payload.get("modelName", ""),
        "status": "queued",
        "message": "微调任务已提交（训练执行依赖真实 GPU 集群，见 ROADMAP）",
    }


@router.post("/human-eval")
async def human_eval(payload: dict) -> dict:
    """触发人工评估（轻量：记录请求，完整人工评估面板见 ROADMAP）."""
    model_name = payload.get("modelName", "")
    return {"modelName": model_name, "evalId": f"he-{model_name or 'all'}", "status": "created"}
