"""API 路由单元测试。

使用 FastAPI TestClient 测试健康检查与数据集列表端点。
任务相关端点因依赖 JobManager/Executor 较重，仅测试健康检查与数据集列表。
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import create_router
from app.core.executor import EvalExecutor
from app.core.job_manager import JobManager
from app.core.llm_client import LLMGatewayClient
from app.report.generator import ABReportGenerator


@pytest.fixture
def app() -> FastAPI:
    """创建带路由的测试 FastAPI 应用。"""
    job_manager = JobManager()
    llm_client = LLMGatewayClient(mock_mode=True)
    executor = EvalExecutor(
        job_manager=job_manager,
        llm_client=llm_client,
        token_price_per_1k=0.01,
    )
    report_generator = ABReportGenerator(job_manager=job_manager)
    app = FastAPI()
    router = create_router(
        job_manager=job_manager,
        executor=executor,
        llm_client=llm_client,
        report_generator=report_generator,
    )
    app.include_router(router)
    return app


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    return TestClient(app)


class TestHealthEndpoint:
    def test_health_returns_up(self, client: TestClient) -> None:
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "UP"
        assert data["component"] == "evaluation"
        assert data["version"] == "0.1.0"


class TestDatasetsEndpoint:
    def test_list_datasets(self, client: TestClient) -> None:
        resp = client.get("/api/v1/eval/datasets")
        assert resp.status_code == 200
        data = resp.json()
        assert "datasets" in data
        names = [d["name"] for d in data["datasets"]]
        assert "mmlu" in names
        assert "cmmlu" in names
        assert "ceval" in names
        assert "custom" in names

    def test_dataset_stats_unknown(self, client: TestClient) -> None:
        """未知数据集应返回 400。"""
        resp = client.get("/api/v1/eval/datasets/unknown-dataset/stats")
        assert resp.status_code == 400


class TestJobEndpoints:
    def test_get_nonexistent_job(self, client: TestClient) -> None:
        """不存在的任务应返回 404。"""
        resp = client.get("/api/v1/eval/jobs/nonexistent-job-id")
        assert resp.status_code == 404

    def test_get_nonexistent_job_logs(self, client: TestClient) -> None:
        """不存在的任务日志应返回 404。"""
        resp = client.get("/api/v1/eval/jobs/nonexistent-job-id/logs")
        assert resp.status_code == 404

    def test_terminate_nonexistent_job(self, client: TestClient) -> None:
        """终止不存在的任务C应返回 404。"""
        resp = client.delete("/api/v1/eval/jobs/nonexistent-job-id")
        assert resp.status_code == 404

    def test_list_jobs_empty(self, client: TestClient) -> None:
        """空任务列表应返回 total=0。"""
        resp = client.get("/api/v1/eval/jobs")
        assert resp.status_code == 200
        data = resp.json()
        assert data["total"] == 0
        assert data["jobs"] == []