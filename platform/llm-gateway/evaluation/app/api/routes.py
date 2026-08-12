"""FastAPI 路由定义。

端点：
- GET  /health                                健康检查
- POST /api/v1/eval/jobs                      提交评测任务
- GET  /api/v1/eval/jobs                      任务列表
- GET  /api/v1/eval/jobs/{job_id}             任务详情
- GET  /api/v1/eval/jobs/{job_id}/logs        任务日志
- DELETE /api/v1/eval/jobs/{job_id}           终止任务
- POST /api/v1/eval/ab-report                 生成 A/B 对比报告
- GET  /api/v1/eval/datasets                  支持的标准集列表
- GET  /api/v1/eval/datasets/{name}/stats     标准集统计信息
"""

from __future__ import annotations

import logging
from typing import Optional

from app.core.executor import EvalExecutor
from app.core.job_manager import JobManager
from app.core.llm_client import LLMGatewayClient
from app.datasets.base import get_adapter
from app.models import (
    ABReport,
    ABReportRequest,
    HealthResponse,
    JobInfo,
    JobListResponse,
    JobLogsResponse,
    JobStatus,
    SubmitJobRequest,
)
from app.report.generator import ABReportGenerator
from fastapi import APIRouter, HTTPException, Query

logger = logging.getLogger(__name__)


def create_router(
    job_manager: JobManager,
    executor: EvalExecutor,
    llm_client: LLMGatewayClient,
    report_generator: ABReportGenerator,
) -> APIRouter:
    """创建 API 路由。

    Args:
        job_manager: 任务管理器
        executor: 评测执行器
        llm_client: LLM 网关客户端
        report_generator: A/B 报告生成器

    Returns:
        APIRouter
    """
    router = APIRouter()

    # ---------------------------------------------------------------------------
    # 健康检查
    # ---------------------------------------------------------------------------
    @router.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        """健康检查。"""
        return HealthResponse(
            status="UP",
            component="evaluation",
            version="0.1.0",
            llm_gateway_reachable=llm_client.health(),
        )

    # ---------------------------------------------------------------------------
    # 评测任务管理
    # ---------------------------------------------------------------------------
    @router.post("/api/v1/eval/jobs", response_model=JobInfo)
    async def submit_job(request: SubmitJobRequest) -> JobInfo:
        """提交评测任务。

        请求体包含：模型 + 数据集 + 指标 + 模式。
        返回 job_id，状态为 PENDING，随后同步执行。
        """
        job = job_manager.submit(request)
        # 同步执行（简化实现；生产环境可用 asyncio.create_task）
        try:
            executor.execute(job.job_id)
        except Exception as e:  # noqa: BLE001
            logger.exception("评测任务执行异常: %s", e)
            # 异常已由 executor 内部处理，此处不重复设置
        # 重新查询最新状态返回
        latest = job_manager.get(job.job_id)
        return latest or job

    @router.get("/api/v1/eval/jobs", response_model=JobListResponse)
    async def list_jobs(
        status: Optional[JobStatus] = Query(None, description="按状态过滤"),
    ) -> JobListResponse:
        """获取任务列表。"""
        jobs = job_manager.list_jobs(status=status)
        return JobListResponse(total=len(jobs), jobs=jobs)

    @router.get("/api/v1/eval/jobs/{job_id}", response_model=JobInfo)
    async def get_job(job_id: str) -> JobInfo:
        """获取任务详情。"""
        job = job_manager.get(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail=f"任务 {job_id} 不存在")
        return job

    @router.get("/api/v1/eval/jobs/{job_id}/logs", response_model=JobLogsResponse)
    async def get_job_logs(job_id: str) -> JobLogsResponse:
        """获取任务日志。"""
        logs = job_manager.get_logs(job_id)
        if logs is None:
            raise HTTPException(status_code=404, detail=f"任务 {job_id} 不存在")
        return JobLogsResponse(job_id=job_id, logs=logs)

    @router.delete("/api/v1/eval/jobs/{job_id}", response_model=JobInfo)
    async def terminate_job(job_id: str) -> JobInfo:
        """终止任务。"""
        job = job_manager.terminate(job_id)
        if job is None:
            raise HTTPException(
                status_code=404,
                detail=f"任务 {job_id} 不存在或不可终止",
            )
        return job

    # ---------------------------------------------------------------------------
    # A/B 对比报告
    # ---------------------------------------------------------------------------
    @router.post("/api/v1/eval/ab-report", response_model=ABReport)
    async def generate_ab_report(request: ABReportRequest) -> ABReport:
        """生成 A/B 对比报告。"""
        try:
            report = report_generator.generate(
                job_a_id=request.job_a,
                job_b_id=request.job_b,
                highlight_threshold=request.highlight_threshold,
            )
            return report
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))

    # ---------------------------------------------------------------------------
    # 标准集信息
    # ---------------------------------------------------------------------------
    @router.get("/api/v1/eval/datasets")
    async def list_datasets() -> dict:
        """列出支持的标准集。"""
        return {
            "datasets": [
                {"name": "mmlu", "language": "en", "description": "MMLU：英文多任务语言理解评测集"},
                {"name": "cmmlu", "language": "zh", "description": "CMMLU：中文多任务语言理解评测集"},
                {"name": "ceval", "language": "zh", "description": "CEval：中文大模型评测集"},
                {"name": "custom", "language": "-", "description": "自定义数据集"},
            ]
        }

    @router.get("/api/v1/eval/datasets/{name}/stats")
    async def dataset_stats(name: str) -> dict:
        """获取标准集统计信息。"""
        try:
            adapter = get_adapter(name)
            stats = adapter.stats()
            return {
                "name": adapter.name,
                "language": adapter.language,
                "description": adapter.description,
                "total_samples": sum(stats.values()),
                "by_subject": stats,
            }
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))

    return router
