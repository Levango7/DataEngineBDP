"""评测任务管理器。

管理评测任务的生命周期：
- 提交任务（创建 job_id，加入队列）
- 查询任务列表/详情
- 查询任务日志
- 终止任务

任务存储：内存字典（简化实现，生产环境可替换为 Redis/DB）
任务执行：同步执行（简化实现，生产环境可用 asyncio/Celery）

线程安全：使用 threading.Lock 保护任务字典。
"""

from __future__ import annotations

import logging
import threading
from typing import Any, Optional
import uuid

from app.models import (
    JobInfo,
    JobStatus,
    SubmitJobRequest,
    utcnow,
)

logger = logging.getLogger(__name__)


class JobManager:
    """评测任务管理器。"""

    def __init__(self):
        self._jobs: dict[str, JobInfo] = {}
        self._logs: dict[str, list[str]] = {}
        self._requests: dict[str, SubmitJobRequest] = {}
        self._lock = threading.Lock()

    def submit(self, request: SubmitJobRequest) -> JobInfo:
        """提交评测任务。

        Args:
            request: 评测任务请求

        Returns:
            JobInfo（含 job_id，状态为 PENDING）
        """
        job_id = self._generate_job_id()
        now = utcnow()

        job = JobInfo(
            job_id=job_id,
            model=request.model,
            dataset=request.dataset.value,
            mode=request.mode.value,
            metrics=[m.value for m in request.metrics],
            status=JobStatus.PENDING,
            created_at=now,
            limit=request.limit,
        )

        with self._lock:
            self._jobs[job_id] = job
            self._requests[job_id] = request
            self._logs[job_id] = [f"[{now.isoformat()}] 任务已提交，job_id={job_id}"]

        logger.info(
            "提交评测任务 %s: model=%s, dataset=%s, mode=%s", job_id, request.model, request.dataset, request.mode
        )
        return job

    def get(self, job_id: str) -> Optional[JobInfo]:
        """获取任务详情。"""
        with self._lock:
            return self._jobs.get(job_id)

    def get_request(self, job_id: str) -> Optional[SubmitJobRequest]:
        """获取任务请求（内部使用）。"""
        with self._lock:
            return self._requests.get(job_id)

    def list_jobs(self, status: Optional[JobStatus] = None) -> list[JobInfo]:
        """列出任务。

        Args:
            status: 按状态过滤（None 表示全部）

        Returns:
            任务列表（按创建时间降序）
        """
        with self._lock:
            jobs = list(self._jobs.values())
        if status is not None:
            jobs = [j for j in jobs if j.status == status]
        # 按创建时间降序
        jobs.sort(key=lambda j: j.created_at, reverse=True)
        return jobs

    def get_logs(self, job_id: str) -> Optional[list[str]]:
        """获取任务日志。"""
        with self._lock:
            return self._logs.get(job_id)

    def add_log(self, job_id: str, message: str) -> None:
        """添加任务日志（内部使用）。"""
        timestamp = utcnow().isoformat()
        with self._lock:
            if job_id in self._logs:
                self._logs[job_id].append(f"[{timestamp}] {message}")

    def terminate(self, job_id: str) -> Optional[JobInfo]:
        """终止任务。

        仅 PENDING 和 RUNNING 状态可终止。

        Args:
            job_id: 任务 ID

        Returns:
            更新后的 JobInfo，若任务不存在或不可终止返回 None
        """
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return None
            if job.status not in (JobStatus.PENDING, JobStatus.RUNNING):
                return None
            job.status = JobStatus.TERMINATED
            job.finished_at = utcnow()
            self._add_log_unlocked(job_id, f"任务被用户终止，原状态={job.status.value}")
            return job

    def update_status(
        self,
        job_id: str,
        status: JobStatus,
        **fields: Any,
    ) -> Optional[JobInfo]:
        """更新任务状态（内部使用）。"""
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return None
            job.status = status
            for key, value in fields.items():
                setattr(job, key, value)
            if status == JobStatus.RUNNING and job.started_at is None:
                job.started_at = utcnow()
            if status in (JobStatus.SUCCEEDED, JobStatus.FAILED):
                job.finished_at = utcnow()
            return job

    def _add_log_unlocked(self, job_id: str, message: str) -> None:
        """添加日志（不持锁，内部使用）。"""
        timestamp = utcnow().isoformat()
        if job_id in self._logs:
            self._logs[job_id].append(f"[{timestamp}] {message}")

    @staticmethod
    def _generate_job_id() -> str:
        """生成任务 ID。"""
        return f"eval-{uuid.uuid4().hex[:12]}"
