"""评测执行器。

执行评测任务的核心逻辑：
1. 加载数据集（通过适配器）
2. 对每条样本调用被评测模型（通过 LLM 网关）
3. 用评测模式判定预测正确性
4. 计算六指标
5. 更新任务状态与结果

执行流程：
- execute(job_id)：同步执行评测任务
- 支持任务终止检查（每条样本执行前检查状态）
- 支持进度更新（processed_samples）

设计要点：
- 同步执行（简化实现，生产环境可用 asyncio）
- 每条样本独立调用模型，采集延迟与 Token
- 评测模式通过 context 注入 LLM 客户端（模型模式需要）
"""

from __future__ import annotations

import logging

from app.core.job_manager import JobManager
from app.core.llm_client import LLMGatewayClient
from app.datasets.base import get_adapter
from app.metrics.base import compute_all
from app.models import (
    DatasetName,
    EvalMode,
    EvalSample,
    JobStatus,
    PredictionResult,
    SubmitJobRequest,
)
from app.modes.base import get_mode

logger = logging.getLogger(__name__)


class EvalExecutor:
    """评测执行器。"""

    def __init__(
        self,
        job_manager: JobManager,
        llm_client: LLMGatewayClient,
        token_price_per_1k: float = 0.01,
    ):
        """
        Args:
            job_manager: 任务管理器
            llm_client: LLM 网关客户端
            token_price_per_1k: Token 成本单价
        """
        self.job_manager = job_manager
        self.llm_client = llm_client
        self.token_price_per_1k = token_price_per_1k

    def execute(self, job_id: str) -> None:
        """执行评测任务。

        Args:
            job_id: 任务 ID

        流程：
        1. 获取任务请求
        2. 加载数据集
        3. 构造评测模式
        4. 逐条样本：调用模型 → 评测判定 → 采集指标
        5. 计算六指标
        6. 更新任务状态为 SUCCEEDED
        """
        request = self.job_manager.get_request(job_id)
        if request is None:
            logger.error("任务 %s 不存在", job_id)
            return

        # 检查任务状态（可能已被终止）
        job = self.job_manager.get(job_id)
        if job is None or job.status == JobStatus.TERMINATED:
            logger.info("任务 %s 已终止，跳过执行", job_id)
            return

        # 1. 更新状态为 RUNNING
        self.job_manager.update_status(job_id, JobStatus.RUNNING)
        self.job_manager.add_log(job_id, "开始执行评测任务")

        try:
            # 2. 加载数据集
            samples = self._load_dataset(request)
            self.job_manager.add_log(job_id, f"数据集 {request.dataset} 加载完成，共 {len(samples)} 条样本")
            self.job_manager.update_status(job_id, JobStatus.RUNNING, total_samples=len(samples))

            # 3. 构造评测模式
            mode = self._build_mode(request)

            # 4. 逐条样本评测
            predictions: list[PredictionResult] = []
            for i, sample in enumerate(samples):
                # 检查任务是否被终止
                current_job = self.job_manager.get(job_id)
                if current_job and current_job.status == JobStatus.TERMINATED:
                    self.job_manager.add_log(job_id, "任务被终止，停止评测")
                    return

                pred = self._evaluate_sample(sample, request, mode)
                predictions.append(pred)

                # 更新进度
                self.job_manager.update_status(
                    job_id,
                    JobStatus.RUNNING,
                    processed_samples=i + 1,
                )
                if (i + 1) % 5 == 0 or (i + 1) == len(samples):
                    self.job_manager.add_log(
                        job_id,
                        f"进度: {i + 1}/{len(samples)}，" f"样本 {sample.id} correct={pred.correct}",
                    )

            # 5. 计算六指标
            metrics = compute_all(
                predictions,
                token_price_per_1k=self.token_price_per_1k,
            )
            self.job_manager.add_log(
                job_id,
                f"指标计算完成: accuracy={metrics.accuracy:.4f}, "
                f"recall={metrics.recall:.4f}, f1={metrics.f1:.4f}, "
                f"latency_p95={metrics.latency_p95:.2f}ms, "
                f"cost={metrics.cost:.4f}, "
                f"hallucination={metrics.hallucination:.4f}",
            )

            # 6. 更新任务状态为 SUCCEEDED
            self.job_manager.update_status(
                job_id,
                JobStatus.SUCCEEDED,
                results=metrics,
                predictions=predictions,
            )
            self.job_manager.add_log(job_id, "评测任务执行成功")

        except Exception as e:  # noqa: BLE001
            logger.exception("任务 %s 执行失败", job_id)
            self.job_manager.update_status(job_id, JobStatus.FAILED, error=str(e))
            self.job_manager.add_log(job_id, f"评测任务执行失败: {e}")

    def _load_dataset(self, request: SubmitJobRequest) -> list[EvalSample]:
        """加载数据集。"""
        # 自定义数据集
        if request.dataset == DatasetName.CUSTOM:
            if not request.custom_samples:
                raise ValueError("自定义数据集未提供样本")
            samples = request.custom_samples
            if request.limit > 0:
                samples = samples[: request.limit]
            return samples

        # 标准集
        adapter = get_adapter(request.dataset.value)
        return adapter.load(limit=request.limit)

    def _build_mode(self, request: SubmitJobRequest):
        """构造评测模式。"""
        if request.mode == EvalMode.RULE:
            return get_mode("rule", patterns=request.rule_patterns)
        elif request.mode == EvalMode.MODEL:
            return get_mode("model", judge_model=request.judge_model or "mock-gpt-4")
        elif request.mode == EvalMode.HUMAN:
            return get_mode("human", human_labels=request.human_labels)
        else:
            raise ValueError(f"不支持的评测模式: {request.mode}")

    def _evaluate_sample(
        self,
        sample: EvalSample,
        request: SubmitJobRequest,
        mode,
    ) -> PredictionResult:
        """评测单条样本。

        Args:
            sample: 评测样本
            request: 任务请求
            mode: 评测模式

        Returns:
            PredictionResult
        """
        # 1. 构造 prompt
        prompt = self._build_prompt(sample)

        # 2. 调用被评测模型
        response = self.llm_client.chat_with_metrics(
            model=request.model,
            messages=[{"role": "user", "content": prompt}],
        )

        # 3. 评测模式判定
        context = {"llm_client": self.llm_client}
        judge_result = mode.judge(sample, response.content, context=context)

        # 4. 构造预测结果
        return PredictionResult(
            sample_id=sample.id,
            prediction=response.content,
            correct=judge_result.correct,
            latency_ms=response.latency_ms,
            prompt_tokens=response.prompt_tokens,
            completion_tokens=response.completion_tokens,
            total_tokens=response.total_tokens,
            hallucination=judge_result.hallucination,
            raw_response={
                "judge_reason": judge_result.reason,
                "model_response": response.raw,
            },
        )

    @staticmethod
    def _build_prompt(sample: EvalSample) -> str:
        """构造评测 prompt。

        对于选择题，将候选答案列出，要求模型输出字母选项。
        对于开放域问答，直接输出问题。
        """
        if sample.choices:
            choices_text = "\n".join(f"{chr(ord('A') + i)}. {choice}" for i, choice in enumerate(sample.choices))
            return (
                f"请回答以下选择题，只输出选项字母（A/B/C/D）。\n\n"
                f"问题：{sample.question}\n\n"
                f"选项：\n{choices_text}\n\n"
                f"答案："
            )
        return f"请回答以下问题：\n\n{sample.question}"
