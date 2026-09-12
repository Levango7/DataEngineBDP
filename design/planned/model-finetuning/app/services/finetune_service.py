"""微调任务管理服务.

负责任务全生命周期管理：
- 提交：校验配置 → 调度 GPU → 启动适配器 → 持久化任务
- 查询：任务列表 / 详情
- 日志：实时读取训练日志并解析指标
- 终止：终止训练进程 → 释放 GPU → 更新状态

任务存储：内存字典（生产可替换为 Redis / DB）。
日志存储：文件（适配器写入 {workDir}/{taskId}.log）。
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import os
import threading
from typing import Optional
import uuid

from app.adapters.base import BaseAdapter, ProcessHandle
from app.adapters.factory import get_adapter
from app.models.finetune_config import FinetuneFramework
from app.models.finetune_task import (
    FinetuneTask,
    FinetuneTaskListResponse,
    FinetuneTaskRequest,
    FinetuneTaskResponse,
    LogEntry,
    LogListResponse,
    TaskStatus,
)
from app.services.job_scheduler import JobScheduler
from loguru import logger


class FinetuneService:
    """微调任务管理服务.

    线程安全：通过 _lock 保护任务字典并发访问。
    """

    def __init__(
        self,
        workDir: str = "/tmp/finetune",
        mockMode: bool = True,
        scheduler: Optional[JobScheduler] = None,
    ):
        """初始化微调服务.

        Args:
            workDir: 工作目录（日志与配置文件存放处）.
            mockMode: 是否 Mock 模式（不实际调用 GPU/框架）.
            scheduler: GPU 调度器（None 则使用默认 Mock 调度器）.
        """
        self.workDir = workDir
        self.mockMode = mockMode
        self.scheduler = scheduler or JobScheduler(mockMode=mockMode)
        # 任务存储：taskId → FinetuneTask
        self._tasks: dict[str, FinetuneTask] = {}
        # 进程句柄：taskId → ProcessHandle
        self._handles: dict[str, ProcessHandle] = {}
        # 适配器实例：taskId → BaseAdapter
        self._adapters: dict[str, BaseAdapter] = {}
        self._lock = threading.RLock()

        os.makedirs(workDir, exist_ok=True)
        logger.info(f"FinetuneService 初始化完成，workDir={workDir}, mockMode={mockMode}")

    # ============================================================
    # 提交任务
    # ============================================================
    def submit_task(self, request: FinetuneTaskRequest) -> FinetuneTask:
        """提交微调任务.

        流程：
        1. 生成 taskId
        2. 校验配置（通过对应适配器）
        3. 调度 GPU 资源
        4. 启动适配器训练进程
        5. 持久化任务

        Args:
            request: 任务提交请求.

        Returns:
            创建的任务实体（状态为 PENDING 或 RUNNING）.

        Raises:
            ValueError: 配置校验失败或 GPU 资源不足.
        """
        with self._lock:
            # 1. 生成 taskId
            taskId = f"ft-{uuid.uuid4().hex[:12]}"

            # 2. 获取适配器并校验配置
            framework = request.config.framework
            adapter = get_adapter(framework, workDir=self.workDir, mockMode=self.mockMode)
            errors = adapter.validate_config(request.config)
            if errors:
                raise ValueError(f"微调配置校验失败: {'; '.join(errors)}")

            # 3. 构造任务实体
            task = FinetuneTask(taskId=taskId, request=request)
            self._tasks[taskId] = task
            self._adapters[taskId] = adapter

            # 4. 调度 GPU
            schedule_result = self.scheduler.schedule(request.gpu)
            if not schedule_result.success:
                task.mark_failed(schedule_result.reason)
                logger.warning(f"任务 {taskId} GPU 调度失败: {schedule_result.reason}")
                raise ValueError(schedule_result.reason)

            task.mark_running(
                node=schedule_result.nodeName or "mock-node",
                gpus=schedule_result.gpuIds,
            )
            logger.info(f"任务 {taskId} 调度到节点 {task.assignedNode}，" f"GPU {task.assignedGPUs}")

            # 5. 启动适配器训练
            try:
                handle = adapter.start(task)
                self._handles[taskId] = handle
                logger.info(f"任务 {taskId} 训练已启动，" f"pid={handle.pid}, mock={handle.isMock}")
            except Exception as e:
                # 启动失败，释放 GPU 并标记失败
                self.scheduler.release(task.assignedNode or "", task.assignedGPUs)
                task.mark_failed(f"启动训练失败: {e}")
                logger.error(f"任务 {taskId} 启动失败: {e}")
                raise

            return task

    # ============================================================
    # 查询任务
    # ============================================================
    def get_task(self, taskId: str) -> Optional[FinetuneTask]:
        """获取任务详情."""
        with self._lock:
            return self._tasks.get(taskId)

    def list_tasks(
        self,
        status: Optional[TaskStatus] = None,
        tenantId: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> FinetuneTaskListResponse:
        """查询任务列表.

        Args:
            status: 按状态过滤（None 表示全部）.
            tenantId: 按租户过滤.
            limit: 返回上限.
            offset: 偏移量.

        Returns:
            任务列表响应.
        """
        with self._lock:
            tasks = list(self._tasks.values())
            if status is not None:
                tasks = [t for t in tasks if t.status == status]
            if tenantId is not None:
                tasks = [t for t in tasks if t.request.tenantId == tenantId]
            tasks.sort(key=lambda t: t.createdAt, reverse=True)
            total = len(tasks)
            paged = tasks[offset : offset + limit]
            return FinetuneTaskListResponse(
                total=total,
                data=[FinetuneTaskResponse.from_task(t) for t in paged],
            )

    # ============================================================
    # 查询日志
    # ============================================================
    async def get_logs(self, taskId: str, tail: int = 100, parse: bool = True) -> Optional[LogListResponse]:
        """查询任务训练日志（文件读取经 to_thread 卸载，不阻塞事件循环）.

        Args:
            taskId: 任务 ID.
            tail: 返回最后 N 行.
            parse: 是否解析为结构化 LogEntry（False 则仅返回原始文本）.

        Returns:
            日志列表响应，或 None 表示任务不存在.
        """
        with self._lock:
            task = self._tasks.get(taskId)
            if task is None:
                return None
            adapter = self._adapters.get(taskId)
            handle = self._handles.get(taskId)

        log_path = self._resolve_log_path(taskId, handle)
        lines = await asyncio.to_thread(self._tail_file, log_path or "", tail)
        if not parse or adapter is None:
            entries = [LogEntry(step=i, message=line) for i, line in enumerate(lines)]
        else:
            entries = []
            for i, line in enumerate(lines):
                entry = adapter.parse_log_line(line, step=i)
                if entry is not None:
                    entries.append(entry)

        return LogListResponse(taskId=taskId, total=len(entries), entries=entries)

    # ============================================================
    # 终止任务
    # ============================================================
    def terminate_task(self, taskId: str) -> Optional[FinetuneTask]:
        """终止微调任务.

        流程：
        1. 终止训练进程
        2. 释放 GPU 资源
        3. 更新任务状态为 TERMINATED

        Args:
            taskId: 任务 ID.

        Returns:
            更新后的任务实体，或 None 表示任务不存在.
        """
        with self._lock:
            task = self._tasks.get(taskId)
            if task is None:
                return None
            if task.is_terminal():
                return task

            adapter = self._adapters.get(taskId)
            handle = self._handles.get(taskId)

            # 终止训练进程
            if handle is not None and adapter is not None:
                try:
                    adapter.stop(handle)
                    logger.info(f"任务 {taskId} 训练进程已终止")
                except Exception as e:
                    logger.error(f"任务 {taskId} 终止进程失败: {e}")

            # 释放 GPU
            if task.assignedNode:
                self.scheduler.release(task.assignedNode, task.assignedGPUs)

            task.mark_terminated()
            return task

    # ============================================================
    # 刷新任务状态（Mock 模式下模拟训练完成）
    # ============================================================
    async def refresh_task_status(self, taskId: str) -> Optional[FinetuneTask]:
        """刷新任务状态.

        Mock 模式下：检查日志尾部是否包含完成标记，若是则标记成功。
        真实模式下：检查进程是否退出并据退出码更新状态。

        文件存在性检查与内容读取均经 to_thread 卸载，不阻塞事件循环。

        Args:
            taskId: 任务 ID.

        Returns:
            更新后的任务实体，或 None.
        """
        with self._lock:
            task = self._tasks.get(taskId)
            if task is None or task.is_terminal():
                return task
            handle = self._handles.get(taskId)

        log_path = self._resolve_log_path(taskId, handle)
        content = await asyncio.to_thread(self._read_log_tail_text, log_path)
        if "训练完成" in content or "训练结束" in content:
            with self._lock:
                if not task.is_terminal():
                    output_path = os.path.join(task.request.outputDir, taskId)
                    task.mark_succeeded(output_path)
                    if task.assignedNode:
                        self.scheduler.release(task.assignedNode, task.assignedGPUs)
        return task

    # ============================================================
    # 适配器与节点信息
    # ============================================================
    def list_adapters(self) -> list[dict]:
        """列出所有适配器描述."""
        result = []
        for fw in FinetuneFramework:
            adapter = get_adapter(fw, workDir=self.workDir, mockMode=self.mockMode)
            result.append(adapter.describe())
        return result

    def list_nodes(self) -> list[dict]:
        """列出 GPU 节点池状态."""
        return self.scheduler.list_nodes()

    # ============================================================
    # 内部辅助
    # ============================================================
    def _resolve_log_path(self, taskId: str, handle: Optional[ProcessHandle]) -> Optional[str]:
        """定位任务日志文件路径."""
        if handle is not None and handle.extra:
            log_path = handle.extra.get("log")
            if log_path:
                return log_path
        # 兜底：默认日志路径
        return os.path.join(self.workDir, f"{taskId}.log")

    _LOG_TAIL_MAX_BYTES = 64 * 1024

    @classmethod
    def _read_log_tail_text(cls, path: Optional[str]) -> str:
        """同步读取日志尾部有限字节（阻塞操作，供 asyncio.to_thread 调用）.

        Args:
            path: 日志文件路径（None 或不存在时返回空串）.

        Returns:
            尾部文本内容.
        """
        if not path:
            return ""
        try:
            size = os.path.getsize(path)
            with open(path, "rb") as f:
                if size > cls._LOG_TAIL_MAX_BYTES:
                    f.seek(size - cls._LOG_TAIL_MAX_BYTES)
                data = f.read(cls._LOG_TAIL_MAX_BYTES)
        except OSError:
            return ""
        return cls._strip_incomplete_utf8_prefix(data).decode("utf-8", errors="replace")

    @staticmethod
    def _strip_incomplete_utf8_prefix(data: bytes) -> bytes:
        """丢弃从字节中间截断产生的首个不完整 UTF-8 序列."""
        i = 0
        n = len(data)
        while i < n:
            b = data[i]
            if b < 0x80:
                break
            if 0xC2 <= b <= 0xDF:
                seq = 2
            elif 0xE0 <= b <= 0xEF:
                seq = 3
            elif 0xF0 <= b <= 0xF7:
                seq = 4
            else:
                i += 1
                continue
            if i + seq <= n:
                try:
                    data[i : i + seq].decode("utf-8")
                    break
                except UnicodeDecodeError:
                    i += 1
            else:
                i += 1
        return data[i:]

    @classmethod
    def _tail_file(cls, path: str, n: int) -> list[str]:
        """读取文件最后 n 行（仅读取尾部有限字节，避免全文件加载）."""
        text = cls._read_log_tail_text(path)
        lines = text.splitlines()
        return lines[-n:] if n < len(lines) else lines

    # ============================================================
    # 统计信息（用于健康检查与监控）
    # ============================================================
    def stats(self) -> dict:
        """返回服务统计信息."""
        with self._lock:
            total = len(self._tasks)
            by_status = {}
            for t in self._tasks.values():
                by_status[t.status.value] = by_status.get(t.status.value, 0) + 1
            return {
                "totalTasks": total,
                "byStatus": by_status,
                "mockMode": self.mockMode,
                "workDir": self.workDir,
                "scheduler": self.scheduler.describe(),
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
