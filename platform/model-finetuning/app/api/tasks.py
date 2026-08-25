"""微调任务 API 路由.

端点：
    POST   /api/v1/finetune/tasks            提交微调任务
    GET    /api/v1/finetune/tasks             查询任务列表
    GET    /api/v1/finetune/tasks/{id}        查询任务详情
    DELETE /api/v1/finetune/tasks/{id}        终止任务
    GET    /api/v1/finetune/tasks/{id}/logs   查询任务日志
    GET    /api/v1/finetune/adapters          列出适配器
    GET    /api/v1/finetune/nodes             列出 GPU 节点池
    GET    /api/v1/finetune/stats             服务统计
"""
from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import JSONResponse

from app.models.finetune_task import (
    FinetuneTaskListResponse,
    FinetuneTaskRequest,
    FinetuneTaskResponse,
    LogListResponse,
    TaskStatus,
)
from app.services.finetune_service import FinetuneService


def create_router(service: FinetuneService) -> APIRouter:
    """创建微调任务路由器.

    通过依赖注入传入 FinetuneService，便于测试替换。
    """

    router = APIRouter(prefix="/api/v1/finetune", tags=["finetune"])

    # ============================================================
    # 提交任务
    # ============================================================
    @router.post("/tasks", status_code=201, response_model=FinetuneTaskResponse)
    async def submit_task(request: FinetuneTaskRequest):
        """提交微调任务.

        请求体包含基座模型、数据集、微调方式、超参与 GPU 需求。
        返回创建的任务详情（含 taskId 与调度结果）。
        """
        try:
            task = service.submit_task(request)
            return FinetuneTaskResponse.from_task(task)
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e)) from e
        except Exception as e:
            raise HTTPException(
                status_code=500, detail=f"提交任务失败: {e}"
            ) from e

    # ============================================================
    # 查询任务列表
    # ============================================================
    @router.get("/tasks", response_model=FinetuneTaskListResponse)
    async def list_tasks(
        status: Optional[TaskStatus] = Query(default=None, description="按状态过滤"),
        tenantId: Optional[str] = Query(default=None, description="按租户过滤"),
        limit: int = Query(default=50, ge=1, le=200, description="返回上限"),
        offset: int = Query(default=0, ge=0, description="偏移量"),
    ):
        """查询微调任务列表."""
        return service.list_tasks(
            status=status, tenantId=tenantId, limit=limit, offset=offset
        )

    # ============================================================
    # 查询任务详情
    # ============================================================
    @router.get("/tasks/{taskId}", response_model=FinetuneTaskResponse)
    async def get_task(taskId: str):
        """查询单个任务详情."""
        # 先刷新状态（Mock 模式下检测完成）
        task = await service.refresh_task_status(taskId)
        if task is None:
            raise HTTPException(status_code=404, detail=f"任务不存在: {taskId}")
        return FinetuneTaskResponse.from_task(task)

    # ============================================================
    # 终止任务
    # ============================================================
    @router.delete("/tasks/{taskId}", response_model=FinetuneTaskResponse)
    async def terminate_task(taskId: str):
        """终止微调任务."""
        task = service.terminate_task(taskId)
        if task is None:
            raise HTTPException(status_code=404, detail=f"任务不存在: {taskId}")
        return FinetuneTaskResponse.from_task(task)

    # ============================================================
    # 查询任务日志
    # ============================================================
    @router.get("/tasks/{taskId}/logs", response_model=LogListResponse)
    async def get_logs(
        taskId: str,
        tail: int = Query(default=100, ge=1, le=10000, description="最后 N 行"),
        parse: bool = Query(default=True, description="是否解析为结构化日志"),
    ):
        """查询任务训练日志.

        返回最后 N 行日志，可选解析为结构化 LogEntry（含 loss/lr/GPU 指标）。
        """
        result = await service.get_logs(taskId, tail=tail, parse=parse)
        if result is None:
            raise HTTPException(status_code=404, detail=f"任务不存在: {taskId}")
        return result

    # ============================================================
    # 适配器列表
    # ============================================================
    @router.get("/adapters")
    async def list_adapters():
        """列出所有微调框架适配器."""
        return {"adapters": service.list_adapters()}

    # ============================================================
    # GPU 节点池
    # ============================================================
    @router.get("/nodes")
    async def list_nodes():
        """列出 GPU 节点池状态."""
        return {"nodes": service.list_nodes()}

    # ============================================================
    # 服务统计
    # ============================================================
    @router.get("/stats")
    async def stats():
        """返回服务统计信息."""
        return service.stats()

    return router