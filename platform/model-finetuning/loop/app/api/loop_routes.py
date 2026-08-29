"""闭环编排 API 路由.

端点：
    POST   /api/v1/loop/tasks                提交闭环任务
    GET    /api/v1/loop/tasks                 查询任务列表
    GET    /api/v1/loop/tasks/{taskId}        查询任务详情
    DELETE /api/v1/loop/tasks/{taskId}        取消任务
    GET    /api/v1/loop/tasks/{taskId}/logs   查询任务日志（聚合三步日志）
    WS     /api/v1/loop/tasks/{taskId}/ws     WebSocket 实时进度
    GET    /api/v1/loop/stats                 服务统计
    GET    /api/v1/loop/adapters/versions     Adapter 版本历史
    GET    /api/v1/loop/adapters/compare      Adapter 版本对比
    POST   /api/v1/loop/adapters/rollback     Adapter 回滚
    GET    /api/v1/loop/reports/versions      评测报告版本历史
    GET    /api/v1/loop/reports/compare       评测报告版本对比
"""

from __future__ import annotations

import logging
from typing import Optional

from app.core.orchestrator import LoopOrchestrator
from app.core.websocket_manager import WebSocketManager
from app.jwt_auth import getAuthContext
from app.models import (
    LoopStatus,
    LoopTaskListResponse,
    LoopTaskRequest,
    LoopTaskResponse,
)
from app.versioning.adapter_registry import AdapterRegistry
from app.versioning.report_registry import ReportRegistry
from fastapi import APIRouter, Depends, HTTPException, Query, WebSocket, WebSocketDisconnect

logger = logging.getLogger(__name__)


def create_router(
    orchestrator: LoopOrchestrator,
    ws_manager: WebSocketManager,
    adapter_registry: AdapterRegistry,
    report_registry: ReportRegistry,
) -> APIRouter:
    """创建闭环编排路由器."""

    # 全部业务端点统一 JWT 鉴权（对齐平台 jwt_auth 镜像模式；
    # WebSocket 路由不适用 HTTP Depends，仍匿名——由 ws token 参数或网关层防护）
    router = APIRouter(
        prefix="/api/v1/loop",
        tags=["finetuning-loop"],
        dependencies=[Depends(getAuthContext)],
    )

    # ============================================================
    # 提交闭环任务
    # ============================================================
    @router.post("/tasks", status_code=201, response_model=LoopTaskResponse)
    async def submit_task(request: LoopTaskRequest):
        """提交闭环任务.

        自动执行 微调 → 评测 → 部署 三步。
        返回创建的任务详情，可通过 WebSocket 订阅实时进度。
        """
        try:
            task = orchestrator.submit_task(request)
            return LoopTaskResponse.from_task(task)
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e)) from e
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"提交闭环任务失败: {e}") from e

    # ============================================================
    # 查询任务列表
    # ============================================================
    @router.get("/tasks", response_model=LoopTaskListResponse)
    async def list_tasks(
        status: Optional[LoopStatus] = Query(default=None),
        tenantId: Optional[str] = Query(default=None),
        limit: int = Query(default=50, ge=1, le=200),
        offset: int = Query(default=0, ge=0),
    ):
        """查询闭环任务列表."""
        return orchestrator.list_tasks(
            status=status,
            tenantId=tenantId,
            limit=limit,
            offset=offset,
        )

    # ============================================================
    # 查询任务详情
    # ============================================================
    @router.get("/tasks/{taskId}", response_model=LoopTaskResponse)
    async def get_task(taskId: str):
        """查询单个任务详情."""
        task = orchestrator.get_task(taskId)
        if task is None:
            raise HTTPException(status_code=404, detail=f"任务不存在: {taskId}")
        return LoopTaskResponse.from_task(task)

    # ============================================================
    # 取消任务
    # ============================================================
    @router.delete("/tasks/{taskId}", response_model=LoopTaskResponse)
    async def cancel_task(taskId: str):
        """取消闭环任务."""
        task = orchestrator.cancel_task(taskId)
        if task is None:
            raise HTTPException(status_code=404, detail=f"任务不存在: {taskId}")
        return LoopTaskResponse.from_task(task)

    # ============================================================
    # 查询任务日志（聚合三步日志）
    # ============================================================
    @router.get("/tasks/{taskId}/logs")
    async def get_logs(taskId: str):
        """查询任务聚合日志."""
        task = orchestrator.get_task(taskId)
        if task is None:
            raise HTTPException(status_code=404, detail=f"任务不存在: {taskId}")
        logs = []
        if task.finetuneResult.error:
            logs.append(f"[finetune] ERROR: {task.finetuneResult.error}")
        if task.finetuneResult.metrics:
            logs.append(f"[finetune] metrics: {task.finetuneResult.metrics}")
        if task.evalResult.error:
            logs.append(f"[evaluate] ERROR: {task.evalResult.error}")
        if task.evalResult.accuracy:
            logs.append(f"[evaluate] accuracy={task.evalResult.accuracy:.4f}, " f"f1={task.evalResult.f1:.4f}")
        if task.deployResult.error:
            logs.append(f"[deploy] ERROR: {task.deployResult.error}")
        if task.deployResult.endpoint:
            logs.append(f"[deploy] endpoint={task.deployResult.endpoint}, " f"healthy={task.deployResult.healthy}")
        return {"taskId": taskId, "logs": logs}

    # ============================================================
    # WebSocket 实时进度
    # ============================================================
    @router.websocket("/tasks/{taskId}/ws")
    async def task_ws(websocket: WebSocket, taskId: str):
        """WebSocket 实时推送闭环进度.

        客户端连接后，将收到该任务的实时进度消息：
        - status：状态变更
        - metrics：训练指标（loss/lr/GPU）
        - completed：完成
        - error：错误
        """
        await ws_manager.connect(taskId, websocket)
        try:
            while True:
                # 保持连接，接收客户端心跳
                await websocket.receive_text()
        except WebSocketDisconnect:
            await ws_manager.disconnect(taskId, websocket)
        except Exception as e:  # noqa: BLE001
            logger.warning("WebSocket 异常: %s", e)
            await ws_manager.disconnect(taskId, websocket)

    # ============================================================
    # 服务统计
    # ============================================================
    @router.get("/stats")
    async def stats():
        """返回服务统计信息."""
        return orchestrator.stats()

    # ============================================================
    # Adapter 版本管理
    # ============================================================
    @router.get("/adapters/versions")
    async def list_adapter_versions(
        baseModel: str = Query(..., description="基座模型"),
        method: str = Query(default="", description="微调方式"),
        framework: str = Query(default="", description="框架"),
        tenantId: str = Query(default="default"),
    ):
        """查询 Adapter 版本历史."""
        return {
            "versions": adapter_registry.list_versions(
                base_model=baseModel,
                method=method,
                framework=framework,
                tenant_id=tenantId,
            )
        }

    @router.get("/adapters/compare")
    async def compare_adapter_versions(
        baseModel: str = Query(...),
        versionA: str = Query(..., alias="versionA"),
        versionB: str = Query(..., alias="versionB"),
        tenantId: str = Query(default="default"),
    ):
        """对比两个 Adapter 版本."""
        return adapter_registry.compare_versions(
            base_model=baseModel,
            version_a=versionA,
            version_b=versionB,
            tenant_id=tenantId,
        )

    @router.post("/adapters/rollback")
    async def rollback_adapter(
        baseModel: str = Query(...),
        version: str = Query(...),
        method: str = Query(default=""),
        framework: str = Query(default=""),
        tenantId: str = Query(default="default"),
    ):
        """回滚到指定 Adapter 版本."""
        return adapter_registry.rollback(
            base_model=baseModel,
            version=version,
            method=method,
            framework=framework,
            tenant_id=tenantId,
        )

    @router.get("/adapters/active")
    async def get_active_adapter(
        baseModel: str = Query(...),
        method: str = Query(default=""),
        framework: str = Query(default=""),
        tenantId: str = Query(default="default"),
    ):
        """获取当前激活的 Adapter 版本."""
        result = adapter_registry.get_active_version(
            base_model=baseModel,
            method=method,
            framework=framework,
            tenant_id=tenantId,
        )
        if result is None:
            raise HTTPException(status_code=404, detail="无激活版本")
        return result

    # ============================================================
    # 评测报告版本管理
    # ============================================================
    @router.get("/reports/versions")
    async def list_report_versions(
        adapterVersion: str = Query(default="", description="Adapter 版本"),
        dataset: str = Query(default=""),
        tenantId: str = Query(default="default"),
    ):
        """查询评测报告版本历史."""
        return {
            "versions": report_registry.list_versions(
                adapter_version=adapterVersion,
                dataset=dataset,
                tenant_id=tenantId,
            )
        }

    @router.get("/reports/compare")
    async def compare_report_versions(
        versionA: str = Query(..., alias="versionA"),
        versionB: str = Query(..., alias="versionB"),
        tenantId: str = Query(default="default"),
    ):
        """对比两个评测报告版本."""
        return report_registry.compare_versions(
            version_a=versionA,
            version_b=versionB,
            tenant_id=tenantId,
        )

    return router
