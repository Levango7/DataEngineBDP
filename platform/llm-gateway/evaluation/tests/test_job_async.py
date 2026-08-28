"""评测任务异步执行与终止单元测试。

覆盖：
- submit_job 立即返回（PENDING），评测在后台线程执行，不阻塞事件循环
- terminate 取消后台 Task 并流转状态
- 网关不可达时任务标记 FAILED（默认无 Mock 兜底）
"""

from __future__ import annotations

import threading
import time

from app.api.routes import create_router
from app.core.executor import EvalExecutor
from app.core.job_manager import JobManager
from app.core.llm_client import LLMGatewayClient
from app.models import EvalSample, JobStatus, SubmitJobRequest
from app.report.generator import ABReportGenerator
from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest


class TestAsyncSubmitJob:
    """submit_job 异步行为测试。"""

    @pytest.fixture
    def harness(self):
        """构建可观测的 JobManager + Executor + TestClient。"""
        job_manager = JobManager()
        llm_client = LLMGatewayClient(mock_mode=True)
        executor = EvalExecutor(job_manager=job_manager, llm_client=llm_client)
        app = FastAPI()
        router = create_router(
            job_manager=job_manager,
            executor=executor,
            llm_client=llm_client,
            report_generator=ABReportGenerator(job_manager=job_manager),
        )
        app.include_router(router)
        return job_manager, executor, TestClient(app)

    @staticmethod
    def _submit_body() -> dict:
        return {
            "model": "test-model",
            "dataset": "custom",
            "mode": "rule",
            "custom_samples": [
                {"id": "s1", "question": "q", "choices": ["A", "B"], "answer": "A"},
            ],
        }

    def test_submit_returns_before_completion(self, harness, monkeypatch):
        """提交应立即返回 PENDING，评测仍在后台执行。"""
        job_manager, executor, client = harness
        started = threading.Event()
        release = threading.Event()

        def fake_execute(job_id: str) -> None:
            job_manager.update_status(job_id, JobStatus.RUNNING)
            started.set()
            assert release.wait(timeout=5), "release not set"

        monkeypatch.setattr(executor, "execute", fake_execute)
        try:
            resp = client.post("/api/v1/eval/jobs", json=self._submit_body())
            assert resp.status_code == 200
            data = resp.json()
            assert data["status"] == JobStatus.PENDING.value

            assert started.wait(timeout=2), "background execute not started"
            job = job_manager.get(data["job_id"])
            assert job is not None
            assert job.status == JobStatus.RUNNING
        finally:
            release.set()

    def test_terminate_cancels_running_task(self, harness, monkeypatch):
        """terminate 应取消后台 Task 并将任务置为 TERMINATED。"""
        job_manager, executor, client = harness
        started = threading.Event()
        release = threading.Event()

        def fake_execute(job_id: str) -> None:
            job_manager.update_status(job_id, JobStatus.RUNNING)
            started.set()
            assert release.wait(timeout=5), "release not set"

        monkeypatch.setattr(executor, "execute", fake_execute)
        try:
            resp = client.post("/api/v1/eval/jobs", json=self._submit_body())
            job_id = resp.json()["job_id"]
            assert started.wait(timeout=2)

            handle = job_manager.get_task_handle(job_id)
            assert handle is not None

            term = client.delete(f"/api/v1/eval/jobs/{job_id}")
            assert term.status_code == 200
            assert term.json()["status"] == JobStatus.TERMINATED.value

            deadline = time.monotonic() + 2
            while not handle.done() and time.monotonic() < deadline:
                time.sleep(0.01)
            assert handle.done(), "async task handle not cancelled"
            assert handle.cancelled()

            assert job_manager.get(job_id).status == JobStatus.TERMINATED
        finally:
            release.set()


class TestUnreachableGatewayJobFailed:
    """网关不可达（默认关闭 Mock 兜底）时任务应失败而非产出 'B'。"""

    def test_job_marked_failed(self) -> None:
        job_manager = JobManager()
        llm_client = LLMGatewayClient(base_url="http://127.0.0.1:1", timeout=2)
        executor = EvalExecutor(job_manager=job_manager, llm_client=llm_client)

        request = SubmitJobRequest(
            model="test-model",
            dataset="custom",
            mode="rule",
            custom_samples=[
                EvalSample(id="s1", question="q", choices=["A", "B"], answer="A"),
            ],
        )
        job = job_manager.submit(request)
        executor.execute(job.job_id)

        finished = job_manager.get(job.job_id)
        assert finished is not None
        assert finished.status == JobStatus.FAILED
        assert finished.error != ""
        assert len(finished.predictions) == 0
