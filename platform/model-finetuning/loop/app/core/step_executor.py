"""步骤执行器.

封装对 T032 微调引擎、T031 评测平台、模型仓库服务的 HTTP 调用。
支持 Mock 模式（不依赖外部服务）与真实模式。

每一步执行后返回结构化结果，由 orchestrator 编排。
"""

from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timezone
from typing import Any, Optional

import httpx

from app.models import (
    DeployConfig,
    DeployStepResult,
    EvalConfig,
    EvalStepResult,
    FinetuneConfig,
    FinetuneStepResult,
    LoopTask,
    LoopTaskRequest,
)

logger = logging.getLogger(__name__)


# ============================================================
# 步骤执行结果
# ============================================================
class StepOutcome:
    """步骤执行结果（内部使用）.

    Attributes:
        success: 是否成功.
        error: 失败时的错误信息.
        data: 成功时的结果数据.
    """

    def __init__(
        self, success: bool, error: str = "", data: dict[str, Any] | None = None
    ):
        self.success = success
        self.error = error
        self.data = data or {}


# ============================================================
# 步骤执行器
# ============================================================
class StepExecutor:
    """步骤执行器.

    封装对三个上游服务的调用：
    1. T032 微调引擎：POST /api/v1/finetune/tasks
    2. T031 评测平台：POST /api/v1/eval/jobs
    3. 模型仓库服务：POST /api/v1/registry/deployments

    Mock 模式下不实际调用，返回模拟结果。
    """

    def __init__(
        self,
        finetune_url: str = "http://localhost:8095",
        evaluation_url: str = "http://localhost:18086",
        registry_url: str = "http://localhost:18089",
        mock_mode: bool = True,
        timeout: int = 30,
        max_retries: int = 3,
    ):
        self.finetune_url = finetune_url.rstrip("/")
        self.evaluation_url = evaluation_url.rstrip("/")
        self.registry_url = registry_url.rstrip("/")
        self.mock_mode = mock_mode
        self.timeout = timeout
        self.max_retries = max_retries

        # httpx 客户端（真实模式使用）
        self._client: httpx.AsyncClient | None = None

    async def start(self) -> None:
        """初始化 HTTP 客户端."""
        if not self.mock_mode:
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(self.timeout),
                limits=httpx.Limits(max_connections=20),
            )

    async def close(self) -> None:
        """关闭 HTTP 客户端."""
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    # ============================================================
    # 健康检查
    # ============================================================
    async def health_check(self) -> dict[str, bool]:
        """检查上游服务可达性."""
        if self.mock_mode:
            return {
                "finetune": True,
                "evaluation": True,
                "registry": True,
            }
        result = {}
        async with httpx.AsyncClient(timeout=5) as c:
            for name, (url, path) in {
                "finetune": (self.finetune_url, "/api/v1/health"),
                "evaluation": (self.evaluation_url, "/health"),
                "registry": (self.registry_url, "/health"),
            }.items():
                try:
                    resp = await c.get(url + path)
                    result[name] = resp.status_code == 200
                except Exception:  # noqa: BLE001
                    result[name] = False
        return result

    # ============================================================
    # 步骤 1：微调
    # ============================================================
    async def execute_finetune(
        self, task: LoopTask,
        progress_callback=None,
    ) -> tuple[StepOutcome, FinetuneStepResult]:
        """执行微调步骤.

        调用 T032 微调引擎 POST /api/v1/finetune/tasks 提交微调任务，
        然后轮询任务状态直至完成。

        Args:
            task: 闭环任务.
            progress_callback: 进度回调（用于推送指标）.

        Returns:
            (StepOutcome, FinetuneStepResult)
        """
        result = FinetuneStepResult(
            status="running",
            startedAt=datetime.now(timezone.utc),
        )

        if self.mock_mode:
            # Mock 模式：模拟微调过程
            return await self._mock_finetune(task, result, progress_callback)

        # 真实模式：调用微调引擎
        try:
            payload = self._build_finetune_payload(task)
            ft_task = await self._http_post(
                self.finetune_url + "/api/v1/finetune/tasks", payload
            )
            ft_task_id = ft_task["taskId"]
            result.taskId = ft_task_id

            # 轮询任务状态
            final = await self._poll_finetune_task(
                ft_task_id, progress_callback
            )
            if final["status"] == "succeeded":
                result.status = "succeeded"
                result.adapterPath = final.get("outputModelPath")
                result.outputModelPath = final.get("outputModelPath")
                result.finishedAt = datetime.now(timezone.utc)
                return StepOutcome(True, data=final), result
            else:
                err = final.get("errorMessage") or "微调未成功完成"
                result.status = "failed"
                result.error = err
                result.finishedAt = datetime.now(timezone.utc)
                return StepOutcome(False, error=err), result

        except Exception as e:  # noqa: BLE001
            logger.exception("微调步骤异常")
            result.status = "failed"
            result.error = str(e)
            result.finishedAt = datetime.now(timezone.utc)
            return StepOutcome(False, error=str(e)), result

    async def _mock_finetune(
        self, task: LoopTask, result: FinetuneStepResult,
        progress_callback=None,
    ) -> tuple[StepOutcome, FinetuneStepResult]:
        """Mock 微调过程：模拟训练指标变化."""
        result.taskId = f"ft-mock-{int(time.time())}"
        total_steps = 20
        initial_loss = 2.5
        for step in range(1, total_steps + 1):
            await asyncio.sleep(0.05)
            # 模拟 loss 下降
            loss = initial_loss * (0.95 ** step) + 0.1
            lr = 2e-4 * (0.98 ** step)
            gpu_util = [75.0 + step * 0.5, 70.0 + step * 0.3]
            gpu_mem = [12.0 + step * 0.1, 11.5 + step * 0.1]
            result.metrics = {
                "step": step,
                "loss": round(loss, 4),
                "learningRate": round(lr, 6),
                "gpuUtil": gpu_util,
                "gpuMemory": gpu_mem,
                "epoch": round(step / total_steps, 2),
            }
            if progress_callback:
                await progress_callback(result.metrics)

        result.status = "succeeded"
        result.adapterPath = f"/tmp/finetune-loop/{task.taskId}/adapter"
        result.outputModelPath = result.adapterPath
        result.finishedAt = datetime.now(timezone.utc)
        return StepOutcome(True, data={"adapterPath": result.adapterPath}), result

    def _build_finetune_payload(self, task: LoopTask) -> dict:
        """构造微调引擎请求体."""
        req = task.request
        config: dict = {
            "method": req.finetune.method,
            "framework": req.finetune.framework,
            "hyperparams": req.finetune.hyperparams.model_dump(),
        }
        if req.finetune.lora:
            config["lora"] = req.finetune.lora.model_dump()
        return {
            "taskName": f"{req.taskName}-finetune",
            "baseModel": req.baseModel,
            "dataset": req.trainDataset.model_dump(),
            "config": config,
            "gpu": req.gpu.model_dump(),
            "outputDir": req.outputDir,
            "tenantId": req.tenantId,
            "description": req.description or "",
        }

    async def _poll_finetune_task(
        self, task_id: str, progress_callback=None,
        interval: float = 2.0, max_wait: int = 3600,
    ) -> dict:
        """轮询微调任务状态直至终态."""
        deadline = time.time() + max_wait
        url = self.finetune_url + f"/api/v1/finetune/tasks/{task_id}"
        while time.time() < deadline:
            resp = await self._http_get(url)
            if resp["status"] in ("succeeded", "failed", "terminated"):
                return resp
            # 推送进度
            if progress_callback:
                logs_url = (
                    self.finetune_url +
                    f"/api/v1/finetune/tasks/{task_id}/logs"
                )
                try:
                    logs = await self._http_get(
                        logs_url, params={"tail": 1, "parse": True}
                    )
                    if logs.get("entries"):
                        entry = logs["entries"][-1]
                        await progress_callback({
                            "step": entry.get("step", 0),
                            "loss": entry.get("loss"),
                            "learningRate": entry.get("learningRate"),
                            "gpuUtil": entry.get("gpuUtil", []),
                            "gpuMemory": entry.get("gpuMemory", []),
                        })
                except Exception:  # noqa: BLE001
                    pass
            await asyncio.sleep(interval)
        return {"status": "failed", "errorMessage": "微调超时"}

    # ============================================================
    # 步骤 2：评测
    # ============================================================
    async def execute_evaluate(
        self, task: LoopTask, adapter_path: str,
    ) -> tuple[StepOutcome, EvalStepResult]:
        """执行评测步骤.

        调用 T031 评测平台 POST /api/v1/eval/jobs 提交评测任务。
        """
        result = EvalStepResult(
            status="running",
            startedAt=datetime.now(timezone.utc),
        )

        if self.mock_mode:
            return await self._mock_evaluate(task, result)

        try:
            payload = self._build_eval_payload(task, adapter_path)
            eval_job = await self._http_post(
                self.evaluation_url + "/api/v1/eval/jobs", payload
            )
            result.jobId = eval_job["job_id"]
            result.status = eval_job["status"]
            # 同步执行模式下，返回时已完成
            if eval_job["status"] == "succeeded" and eval_job.get("results"):
                self._fill_eval_metrics(result, eval_job["results"])
                result.finishedAt = datetime.now(timezone.utc)
                return StepOutcome(True, data=eval_job), result
            else:
                err = eval_job.get("error") or "评测未成功完成"
                result.status = "failed"
                result.error = err
                result.finishedAt = datetime.now(timezone.utc)
                return StepOutcome(False, error=err), result

        except Exception as e:  # noqa: BLE001
            logger.exception("评测步骤异常")
            result.status = "failed"
            result.error = str(e)
            result.finishedAt = datetime.now(timezone.utc)
            return StepOutcome(False, error=str(e)), result

    async def _mock_evaluate(
        self, task: LoopTask, result: EvalStepResult,
    ) -> tuple[StepOutcome, EvalStepResult]:
        """Mock 评测过程."""
        await asyncio.sleep(0.1)
        result.jobId = f"eval-mock-{int(time.time())}"
        result.reportId = f"rpt-mock-{int(time.time())}"
        # 模拟六指标
        result.accuracy = 0.82
        result.recall = 0.78
        result.f1 = 0.80
        result.latencyP95 = 120.5
        result.cost = 5000.0
        result.hallucination = 0.05
        result.status = "succeeded"
        result.finishedAt = datetime.now(timezone.utc)
        return StepOutcome(True), result

    def _build_eval_payload(
        self, task: LoopTask, adapter_path: str,
    ) -> dict:
        """构造评测平台请求体."""
        req = task.request
        return {
            "model": adapter_path or req.baseModel,
            "dataset": req.eval.dataset,
            "mode": req.eval.mode,
            "metrics": req.eval.metrics,
            "limit": req.eval.limit,
        }

    @staticmethod
    def _fill_eval_metrics(result: EvalStepResult, metrics: dict) -> None:
        """从评测结果填充六指标."""
        result.accuracy = float(metrics.get("accuracy", 0.0))
        result.recall = float(metrics.get("recall", 0.0))
        result.f1 = float(metrics.get("f1", 0.0))
        result.latencyP95 = float(metrics.get("latency_p95", 0.0))
        result.cost = float(metrics.get("cost", 0.0))
        result.hallucination = float(metrics.get("hallucination", 0.0))

    # ============================================================
    # 步骤 3：部署
    # ============================================================
    async def execute_deploy(
        self, task: LoopTask, adapter_path: str,
        eval_accuracy: float = 0.0,
    ) -> tuple[StepOutcome, DeployStepResult]:
        """执行部署步骤.

        调用模型仓库服务 POST /api/v1/registry/deployments 创建部署。
        """
        result = DeployStepResult(
            status="running",
            startedAt=datetime.now(timezone.utc),
        )

        # 评测达标检查
        if task.request.deploy.minAccuracy > 0 and \
                eval_accuracy < task.request.deploy.minAccuracy:
            result.status = "skipped"
            result.error = (
                f"评测准确率 {eval_accuracy:.2%} 低于阈值 "
                f"{task.request.deploy.minAccuracy:.2%}，跳过部署"
            )
            result.finishedAt = datetime.now(timezone.utc)
            return StepOutcome(True, data={"skipped": True}), result

        if self.mock_mode:
            return await self._mock_deploy(task, result)

        try:
            # 1. 注册模型
            reg_payload = {
                "modelName": f"{task.request.baseModel}-finetuned",
                "version": task.adapterVersion or "0.1.0",
                "path": adapter_path,
                "baseModel": task.request.baseModel,
                "framework": task.request.finetune.framework,
                "method": task.request.finetune.method,
                "tenantId": task.request.tenantId,
                "metadata": {
                    "evalAccuracy": eval_accuracy,
                    "loopTaskId": task.taskId,
                },
            }
            reg_resp = await self._http_post(
                self.registry_url + "/api/v1/registry/models", reg_payload
            )
            model_version = reg_resp.get("version", "0.1.0")
            result.modelVersion = model_version

            # 2. 创建部署
            deploy_payload = {
                "modelName": reg_payload["modelName"],
                "version": model_version,
                "runtime": task.request.deploy.runtime,
                "port": task.request.deploy.port,
                "replicas": task.request.deploy.replicas,
                "gpuCount": task.request.deploy.gpuCount,
                "tenantId": task.request.tenantId,
            }
            dep_resp = await self._http_post(
                self.registry_url + "/api/v1/registry/deployments",
                deploy_payload,
            )
            result.deploymentId = dep_resp.get("deploymentId")
            result.endpoint = dep_resp.get("endpoint")
            result.status = dep_resp.get("status", "running")
            result.healthy = dep_resp.get("healthy", False)
            result.finishedAt = datetime.now(timezone.utc)
            return StepOutcome(True, data=dep_resp), result

        except Exception as e:  # noqa: BLE001
            logger.exception("部署步骤异常")
            result.status = "failed"
            result.error = str(e)
            result.finishedAt = datetime.now(timezone.utc)
            return StepOutcome(False, error=str(e)), result

    async def _mock_deploy(
        self, task: LoopTask, result: DeployStepResult,
    ) -> tuple[StepOutcome, DeployStepResult]:
        """Mock 部署过程."""
        await asyncio.sleep(0.1)
        result.deploymentId = f"dep-mock-{int(time.time())}"
        result.modelVersion = task.adapterVersion or "0.1.0"
        result.endpoint = (
            f"http://localhost:{task.request.deploy.port}"
        )
        result.status = "running"
        result.healthy = True
        result.finishedAt = datetime.now(timezone.utc)
        return StepOutcome(True), result

    # ============================================================
    # HTTP 工具
    # ============================================================
    async def _http_post(
        self, url: str, json_body: dict,
    ) -> dict:
        """带重试的 POST 请求."""
        last_err = ""
        for attempt in range(self.max_retries):
            try:
                if self._client is None:
                    async with httpx.AsyncClient(
                        timeout=self.timeout
                    ) as c:
                        resp = await c.post(url, json=json_body)
                else:
                    resp = await self._client.post(url, json=json_body)
                resp.raise_for_status()
                return resp.json()
            except Exception as e:  # noqa: BLE001
                last_err = str(e)
                logger.warning(
                    "POST %s 失败（第 %d 次）: %s",
                    url, attempt + 1, e,
                )
                await asyncio.sleep(0.5 * (attempt + 1))
        raise RuntimeError(f"HTTP POST 失败: {url}, 错误: {last_err}")

    async def _http_get(
        self, url: str, params: dict | None = None,
    ) -> dict:
        """带重试的 GET 请求."""
        last_err = ""
        for attempt in range(self.max_retries):
            try:
                if self._client is None:
                    async with httpx.AsyncClient(
                        timeout=self.timeout
                    ) as c:
                        resp = await c.get(url, params=params)
                else:
                    resp = await self._client.get(url, params=params)
                resp.raise_for_status()
                return resp.json()
            except Exception as e:  # noqa: BLE001
                last_err = str(e)
                logger.warning(
                    "GET %s 失败（第 %d 次）: %s",
                    url, attempt + 1, e,
                )
                await asyncio.sleep(0.5 * (attempt + 1))
        raise RuntimeError(f"HTTP GET 失败: {url}, 错误: {last_err}")