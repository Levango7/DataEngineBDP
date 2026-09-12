"""闭环编排器.

负责编排 微调 → 评测 → 部署 三步执行流程：
1. 接收闭环任务请求，创建任务实体
2. 分配 Adapter 版本号（通过版本化模块）
3. 依次执行三步，每步完成后更新状态、推送 WebSocket
4. 任一步失败则回滚（标记失败、记录错误）
5. 全部完成后分配评测报告版本号

状态机：
    pending → finetuning → evaluating → deploying → completed
                                  ↘ failed
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import logging
import threading
from typing import Optional
import uuid

from app.core.step_executor import StepExecutor, StepOutcome
from app.core.websocket_manager import WebSocketManager
from app.models import (
    LoopStatus,
    LoopTask,
    LoopTaskListResponse,
    LoopTaskRequest,
    LoopTaskResponse,
)
from app.versioning.adapter_registry import AdapterRegistry
from app.versioning.report_registry import ReportRegistry

logger = logging.getLogger(__name__)


class LoopOrchestrator:
    """闭环编排器.

    线程安全：通过 _lock 保护任务字典。
    异步执行：每个闭环任务在独立 asyncio.Task 中执行。
    """

    def __init__(
        self,
        executor: StepExecutor,
        ws_manager: WebSocketManager,
        adapter_registry: AdapterRegistry,
        report_registry: ReportRegistry,
    ):
        self.executor = executor
        self.ws = ws_manager
        self.adapter_registry = adapter_registry
        self.report_registry = report_registry

        # 任务存储：taskId → LoopTask
        self._tasks: dict[str, LoopTask] = {}
        # 异步任务句柄：taskId → asyncio.Task
        self._async_handles: dict[str, asyncio.Task] = {}
        self._lock = threading.RLock()

    # ============================================================
    # 提交闭环任务
    # ============================================================
    def submit_task(self, request: LoopTaskRequest) -> LoopTask:
        """提交闭环任务.

        生成 taskId，分配 Adapter 版本号，创建任务实体，
        并启动异步执行协程。

        Args:
            request: 闭环任务请求.

        Returns:
            创建的任务实体（状态为 PENDING）.
        """
        with self._lock:
            taskId = f"loop-{uuid.uuid4().hex[:12]}"
            task = LoopTask(taskId=taskId, request=request)

            # 分配 Adapter 版本号
            version = self.adapter_registry.allocate_version(
                base_model=request.baseModel,
                method=request.finetune.method,
                framework=request.finetune.framework,
                tenant_id=request.tenantId,
            )
            task.adapterVersion = version

            self._tasks[taskId] = task

        # 启动异步执行（不阻塞提交请求）
        try:
            loop = asyncio.get_event_loop()
            handle = loop.create_task(self._run_loop(task))
            self._async_handles[taskId] = handle
        except RuntimeError:
            # 无事件循环（如同步测试环境），延迟到首次查询时执行
            logger.warning("无事件循环，闭环任务 %s 将在查询时触发执行", taskId)

        logger.info(
            "闭环任务已提交: taskId=%s, name=%s, adapterVersion=%s",
            taskId,
            request.taskName,
            version,
        )
        return task

    # ============================================================
    # 异步执行闭环
    # ============================================================
    async def _run_loop(self, task: LoopTask) -> None:
        """执行闭环三步.

        任一步失败则标记任务失败并推送错误。
        全部成功则标记完成并推送完成消息。
        """
        taskId = task.taskId
        try:
            # 步骤 1：微调
            success = await self._run_finetune(task)
            if not success:
                return

            # 步骤 2：评测
            success = await self._run_evaluate(task)
            if not success:
                return

            # 步骤 3：部署（可选）
            if not task.request.skipDeploy:
                success = await self._run_deploy(task)
                if not success:
                    return

            # 全部完成
            with self._lock:
                task.status = LoopStatus.COMPLETED
                task.currentStep = "done"
                task.finishedAt = datetime.now(timezone.utc)
                task.touch()

            await self.ws.push_completed(
                taskId,
                {
                    "status": "completed",
                    "adapterVersion": task.adapterVersion,
                    "reportVersion": task.reportVersion,
                    "evalAccuracy": task.evalResult.accuracy,
                    "deploymentId": task.deployResult.deploymentId,
                },
            )
            logger.info("闭环任务 %s 全部完成", taskId)

        except Exception as e:  # noqa: BLE001
            logger.exception("闭环任务 %s 执行异常", taskId)
            with self._lock:
                task.mark_failed(task.currentStep, str(e))
            await self.ws.push_error(taskId, task.currentStep, str(e))

    async def _run_finetune(self, task: LoopTask) -> bool:
        """执行微调步骤."""
        taskId = task.taskId
        with self._lock:
            task.status = LoopStatus.FINETUNING
            task.currentStep = "finetune"
            task.touch()

        await self.ws.push_status(
            taskId,
            "finetuning",
            "finetune",
            adapterVersion=task.adapterVersion,
        )

        async def on_metrics(metrics: dict) -> None:
            await self.ws.push_metrics(taskId, "finetune", metrics)

        outcome, result = await self.executor.execute_finetune(
            task,
            progress_callback=on_metrics,
        )

        with self._lock:
            task.finetuneResult = result
            task.touch()

        if not outcome.success:
            with self._lock:
                task.mark_failed("finetune", result.error or "微调失败")
            await self.ws.push_error(taskId, "finetune", result.error or "")
            return False

        # 注册 Adapter 版本
        self.adapter_registry.register(
            version=task.adapterVersion,
            base_model=task.request.baseModel,
            adapter_path=result.adapterPath or "",
            tenant_id=task.request.tenantId,
            method=task.request.finetune.method,
            framework=task.request.finetune.framework,
            loop_task_id=taskId,
            metrics=result.metrics,
        )

        await self.ws.push_status(
            taskId,
            "evaluating",
            "finetune",
            adapterPath=result.adapterPath,
        )
        return True

    async def _run_evaluate(self, task: LoopTask) -> bool:
        """执行评测步骤."""
        taskId = task.taskId
        with self._lock:
            task.status = LoopStatus.EVALUATING
            task.currentStep = "evaluate"
            task.touch()

        await self.ws.push_status(taskId, "evaluating", "evaluate")

        adapter_path = task.finetuneResult.adapterPath or task.request.baseModel
        outcome, result = await self.executor.execute_evaluate(
            task,
            adapter_path,
        )

        with self._lock:
            task.evalResult = result
            task.touch()

        if not outcome.success:
            with self._lock:
                task.mark_failed("evaluate", result.error or "评测失败")
            await self.ws.push_error(taskId, "evaluate", result.error or "")
            return False

        # 分配评测报告版本号并注册
        report_version = self.report_registry.allocate_version(
            adapter_version=task.adapterVersion or "0.1.0",
            dataset=task.request.eval.dataset,
            tenant_id=task.request.tenantId,
        )
        with self._lock:
            task.reportVersion = report_version
            task.touch()

        self.report_registry.register(
            version=report_version,
            adapter_version=task.adapterVersion or "0.1.0",
            dataset=task.request.eval.dataset,
            tenant_id=task.request.tenantId,
            loop_task_id=taskId,
            accuracy=result.accuracy,
            recall=result.recall,
            f1=result.f1,
            latency_p95=result.latencyP95,
            cost=result.cost,
            hallucination=result.hallucination,
        )

        await self.ws.push_metrics(
            taskId,
            "evaluate",
            {
                "accuracy": result.accuracy,
                "recall": result.recall,
                "f1": result.f1,
                "latencyP95": result.latencyP95,
                "cost": result.cost,
                "hallucination": result.hallucination,
                "reportVersion": report_version,
            },
        )
        return True

    async def _run_deploy(self, task: LoopTask) -> bool:
        """执行部署步骤."""
        taskId = task.taskId
        with self._lock:
            task.status = LoopStatus.DEPLOYING
            task.currentStep = "deploy"
            task.touch()

        await self.ws.push_status(taskId, "deploying", "deploy")

        adapter_path = task.finetuneResult.adapterPath or task.request.baseModel
        outcome, result = await self.executor.execute_deploy(
            task,
            adapter_path,
            eval_accuracy=task.evalResult.accuracy,
        )

        with self._lock:
            task.deployResult = result
            task.touch()

        if not outcome.success:
            with self._lock:
                task.mark_failed("deploy", result.error or "部署失败")
            await self.ws.push_error(taskId, "deploy", result.error or "")
            return False

        # 部署被跳过（评测不达标）也算成功
        await self.ws.push_status(
            taskId,
            "completed",
            "deploy",
            deploymentId=result.deploymentId,
            endpoint=result.endpoint,
            healthy=result.healthy,
        )
        return True

    # ============================================================
    # 查询任务
    # ============================================================
    def get_task(self, taskId: str) -> Optional[LoopTask]:
        """获取任务详情."""
        with self._lock:
            task = self._tasks.get(taskId)
            if task is None:
                return None
            # 若任务仍 pending 且无异步句柄，触发同步执行
            if task.status == LoopStatus.PENDING and taskId not in self._async_handles:
                self._trigger_sync_execution(task)
            return task

    def list_tasks(
        self,
        status: Optional[LoopStatus] = None,
        tenantId: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> LoopTaskListResponse:
        """查询任务列表."""
        with self._lock:
            tasks = list(self._tasks.values())
            if status is not None:
                tasks = [t for t in tasks if t.status == status]
            if tenantId is not None:
                tasks = [t for t in tasks if t.request.tenantId == tenantId]
            tasks.sort(key=lambda t: t.createdAt, reverse=True)
            total = len(tasks)
            paged = tasks[offset : offset + limit]
            return LoopTaskListResponse(
                total=total,
                data=[LoopTaskResponse.from_task(t) for t in paged],
            )

    # ============================================================
    # 取消任务
    # ============================================================
    def cancel_task(self, taskId: str) -> Optional[LoopTask]:
        """取消闭环任务.

        仅在非终态时可取消。取消后正在执行的步骤会自然完成，
        但后续步骤不再执行。
        """
        with self._lock:
            task = self._tasks.get(taskId)
            if task is None or task.is_terminal():
                return task
            task.status = LoopStatus.CANCELLED
            task.finishedAt = datetime.now(timezone.utc)
            task.touch()

            # 取消异步句柄
            handle = self._async_handles.pop(taskId, None)
            if handle and not handle.done():
                handle.cancel()

        return task

    # ============================================================
    # 执行触发（运行中 loop 内为后台调度，无 loop 时阻塞执行）
    # ============================================================
    def _trigger_sync_execution(self, task: LoopTask) -> None:
        """惰性触发闭环执行.

        语义为 fire-and-forget：调用方（get_task 查询路由）只期望任务
        开始执行并随后通过查询观察进度，不期望阻塞到完成。

        - 运行中 loop 内：用 get_running_loop().create_task 调度
          _run_loop 并登记句柄，绝不自建事件循环。
        - 无任何 loop（纯同步测试/脚本）：asyncio.run 阻塞跑完，
          结束后自动清理，不残留线程级事件循环。
        """
        try:
            running_loop = asyncio.get_running_loop()
        except RuntimeError:
            running_loop = None

        if running_loop is not None:
            handle = running_loop.create_task(self._run_loop(task))
            with self._lock:
                self._async_handles[task.taskId] = handle
            return

        try:
            asyncio.run(self._run_loop(task))
        except Exception as e:  # noqa: BLE001
            logger.exception("同步执行闭环失败")
            with self._lock:
                task.mark_failed(task.currentStep, str(e))

    # ============================================================
    # 统计
    # ============================================================
    def stats(self) -> dict:
        """返回编排器统计信息."""
        with self._lock:
            total = len(self._tasks)
            by_status: dict[str, int] = {}
            for t in self._tasks.values():
                key = t.status.value
                by_status[key] = by_status.get(key, 0) + 1
            return {
                "totalTasks": total,
                "byStatus": by_status,
                "wsManager": self.ws.stats(),
                "adapterVersions": self.adapter_registry.stats(),
                "reportVersions": self.report_registry.stats(),
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
