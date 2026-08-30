"""T031 模型评测平台 Docker 集成测试。

被测对象：
- 核心组件（直接导入测试，无需服务启动）：
  - 标准集适配器（MMLU/CMMLU/CEval）
  - 六指标计算（accuracy/recall/f1/latency_p95/cost/hallucination）
  - 三模式评测（rule/model/human）
  - A/B 报告生成器
  - 评测任务管理器与执行器
- HTTP API（Docker 容器 it-evaluation，端口 18086 → 8086，服务不可用时跳过）：
  - 评测任务场景（提交/查询/日志/终止）
  - A/B 报告场景

测试策略：
- 核心组件测试始终运行（保证代码正确性）
- HTTP API 测试通过 evaluation_available fixture 控制跳过
- 使用 Mock LLM 客户端，无需真实 LLM 网关

覆盖 ≥20 个测试用例，分为：
- 标准集场景（4 个）
- 指标计算场景（6 个）
- 三模式场景（4 个）
- A/B 报告场景（4 个）
- 评测任务场景（核心组件 3 个 + HTTP API 4 个）
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any

import pytest

# ---------------------------------------------------------------------------
# 将评测平台代码加入 sys.path，便于直接导入测试核心组件
# ---------------------------------------------------------------------------
_EVAL_APP_DIR = Path(__file__).resolve().parents[3] / "platform" / "llm-gateway" / "evaluation"

# 清理已加载的 app / app.* 模块，防止 app 包命名冲突
# （多个组件都有 app 包，需确保从 llm-gateway/evaluation 导入）
for _mod in list(sys.modules.keys()):
    if _mod == "app" or _mod.startswith("app."):
        del sys.modules[_mod]

# 强制将 _EVAL_APP_DIR 放到 sys.path[0]，确保 app 包从 evaluation 导入
if str(_EVAL_APP_DIR) in sys.path:
    sys.path.remove(str(_EVAL_APP_DIR))
sys.path.insert(0, str(_EVAL_APP_DIR))

# 导入评测平台核心组件
from app.core.executor import EvalExecutor  # noqa: E402
from app.core.job_manager import JobManager  # noqa: E402
from app.core.llm_client import LLMGatewayClient  # noqa: E402
from app.datasets.base import get_adapter  # noqa: E402
from app.datasets.ceval import CEvalAdapter  # noqa: E402
from app.datasets.cmmlu import CMMLUAdapter  # noqa: E402
from app.datasets.mmlu import MMLUAdapter  # noqa: E402
from app.metrics.accuracy import AccuracyMetric  # noqa: E402
from app.metrics.base import compute_all  # noqa: E402
from app.metrics.cost import CostMetric  # noqa: E402
from app.metrics.f1 import F1Metric  # noqa: E402
from app.metrics.hallucination import HallucinationMetric  # noqa: E402
from app.metrics.latency import LatencyP95Metric  # noqa: E402
from app.metrics.recall import RecallMetric  # noqa: E402
from app.models import (  # noqa: E402
    ABReport,
    DatasetName,
    EvalMode,
    EvalSample,
    JobStatus,
    MetricsBundle,
    PredictionResult,
    SubmitJobRequest,
)
from app.modes.base import get_mode  # noqa: E402
from app.modes.human_mode import HumanMode  # noqa: E402
from app.modes.model_mode import ModelMode  # noqa: E402
from app.modes.rule_mode import RuleMode  # noqa: E402
from app.report.generator import ABReportGenerator  # noqa: E402

import requests  # noqa: E402


# ---------------------------------------------------------------------------
# 辅助函数与 fixtures
# ---------------------------------------------------------------------------
def _make_prediction(
    sample_id: str = "s1",
    correct: bool = True,
    latency_ms: float = 100.0,
    total_tokens: int = 100,
    hallucination: bool = False,
) -> PredictionResult:
    """构造测试用预测结果。"""
    return PredictionResult(
        sample_id=sample_id,
        prediction="A" if correct else "B",
        correct=correct,
        latency_ms=latency_ms,
        prompt_tokens=total_tokens // 2,
        completion_tokens=total_tokens // 2,
        total_tokens=total_tokens,
        hallucination=hallucination,
    )


def _make_sample(
    sample_id: str = "s1",
    question: str = "1+1=?",
    choices: list[str] | None = None,
    answer: str = "B",
    context: str = "",
) -> EvalSample:
    """构造测试用样本。"""
    if choices is None:
        choices = ["0", "1", "2", "3"]
    return EvalSample(
        id=sample_id,
        question=question,
        choices=choices,
        answer=answer,
        context=context,
    )


@pytest.fixture
def mock_llm_client() -> LLMGatewayClient:
    """Mock LLM 客户端（纯 mock 模式，不发起任何 HTTP 请求）。"""
    client = LLMGatewayClient(
        base_url="http://localhost:99999",  # 不可达地址
        api_key="dummy",
        timeout=2,
        enable_mock_fallback=True,
        mock_mode=True,  # 纯 mock 模式，直接返回 mock 响应
    )
    yield client
    client.close()


@pytest.fixture
def job_manager() -> JobManager:
    """任务管理器 fixture。"""
    return JobManager()


@pytest.fixture
def executor(job_manager, mock_llm_client) -> EvalExecutor:
    """评测执行器 fixture。"""
    return EvalExecutor(
        job_manager=job_manager,
        llm_client=mock_llm_client,
        token_price_per_1k=0.01,
    )


@pytest.fixture
def report_generator(job_manager) -> ABReportGenerator:
    """A/B 报告生成器 fixture。"""
    return ABReportGenerator(job_manager=job_manager)


# ===========================================================================
# 一、标准集场景（4 个测试）
# ===========================================================================
class TestDatasets:
    """标准集适配器测试。"""

    def test_mmlu_load_success(self):
        """MMLU 标准集加载成功，返回非空样本列表。"""
        adapter = MMLUAdapter()
        samples = adapter.load()
        assert len(samples) > 0, "MMLU 应返回非空样本"
        assert all(isinstance(s, EvalSample) for s in samples)
        # 验证样本结构
        first = samples[0]
        assert first.id.startswith("mmlu-")
        assert first.question
        assert len(first.choices) == 4
        assert first.answer in "ABCD"

    def test_cmmlu_load_success(self):
        """CMMLU 标准集加载成功，返回中文样本。"""
        adapter = CMMLUAdapter()
        samples = adapter.load()
        assert len(samples) > 0, "CMMLU 应返回非空样本"
        first = samples[0]
        assert first.id.startswith("cmmlu-")
        assert first.question
        assert len(first.choices) == 4

    def test_ceval_load_success(self):
        """CEval 标准集加载成功，返回中文样本。"""
        adapter = CEvalAdapter()
        samples = adapter.load()
        assert len(samples) > 0, "CEval 应返回非空样本"
        first = samples[0]
        assert first.id.startswith("ceval-")
        assert first.question
        assert len(first.choices) == 4

    def test_dataset_limit_and_stats(self):
        """标准集 limit 参数与 stats 统计功能正确。"""
        adapter = MMLUAdapter()
        # limit 限制
        samples_limited = adapter.load(limit=5)
        assert len(samples_limited) == 5
        # stats 统计
        stats = adapter.stats()
        assert isinstance(stats, dict)
        assert sum(stats.values()) > 0
        # 应包含多个任务类别
        assert len(stats) >= 2


# ===========================================================================
# 二、指标计算场景（6 个测试）
# ===========================================================================
class TestMetrics:
    """六指标计算测试。"""

    def test_accuracy(self):
        """准确率计算正确。"""
        preds = [
            _make_prediction("s1", correct=True),
            _make_prediction("s2", correct=True),
            _make_prediction("s3", correct=False),
            _make_prediction("s4", correct=False),
        ]
        assert AccuracyMetric().compute(preds) == 0.5

    def test_recall(self):
        """召回率计算正确（正确且非幻觉）。"""
        preds = [
            _make_prediction("s1", correct=True, hallucination=False),
            _make_prediction("s2", correct=True, hallucination=True),  # 幻觉不计入召回
            _make_prediction("s3", correct=False),
            _make_prediction("s4", correct=False),
        ]
        # 召回 = 正确且非幻觉 = 1，总数 = 4，recall = 0.25
        assert RecallMetric().compute(preds) == 0.25

    def test_f1(self):
        """F1 计算正确（调和平均）。"""
        preds = [
            _make_prediction("s1", correct=True),
            _make_prediction("s2", correct=False),
        ]
        # accuracy = 0.5, recall = 0.5, F1 = 0.5
        f1 = F1Metric().compute(preds)
        assert 0.4 <= f1 <= 0.6

    def test_latency_p95(self):
        """P95 延迟计算正确。"""
        # 20 个样本，延迟 1-20ms
        preds = [_make_prediction(f"s{i}", latency_ms=float(i)) for i in range(1, 21)]
        p95 = LatencyP95Metric().compute(preds)
        # P95 应接近 19.05（线性插值）
        assert 18.0 <= p95 <= 20.0

    def test_cost(self):
        """Token 成本计算正确。"""
        preds = [
            _make_prediction("s1", total_tokens=1000),
            _make_prediction("s2", total_tokens=3000),
        ]
        # 总 4000 token，单价 0.01 元/1K，cost = 4 * 0.01 = 0.04
        cost = CostMetric(token_price_per_1k=0.01).compute(preds)
        assert cost == pytest.approx(0.04, rel=1e-6)

    def test_hallucination(self):
        """幻觉率计算正确。"""
        preds = [
            _make_prediction("s1", hallucination=False),
            _make_prediction("s2", hallucination=True),
            _make_prediction("s3", hallucination=False),
            _make_prediction("s4", hallucination=True),
        ]
        assert HallucinationMetric().compute(preds) == 0.5

    def test_compute_all_returns_bundle(self):
        """compute_all 返回完整 MetricsBundle。"""
        preds = [
            _make_prediction("s1", correct=True, latency_ms=50, total_tokens=200),
            _make_prediction("s2", correct=False, latency_ms=150, total_tokens=300,
                             hallucination=True),
        ]
        bundle = compute_all(preds, token_price_per_1k=0.01)
        assert isinstance(bundle, MetricsBundle)
        assert 0.0 <= bundle.accuracy <= 1.0
        assert 0.0 <= bundle.recall <= 1.0
        assert 0.0 <= bundle.f1 <= 1.0
        assert bundle.latency_p95 > 0
        assert bundle.cost > 0
        assert 0.0 <= bundle.hallucination <= 1.0


# ===========================================================================
# 三、三模式场景（4 个测试）
# ===========================================================================
class TestModes:
    """三模式评测测试。"""

    def test_rule_mode_correct(self):
        """规则模式：正确答案判定为 correct。"""
        sample = _make_sample(answer="B")
        mode = RuleMode()
        result = mode.judge(sample, "B")
        assert result.correct is True

    def test_rule_mode_wrong(self):
        """规则模式：错误答案判定为 not correct。"""
        sample = _make_sample(answer="B")
        mode = RuleMode()
        result = mode.judge(sample, "A")
        assert result.correct is False

    def test_model_mode_with_mock_client(self, mock_llm_client):
        """模型模式：使用 Mock LLM 客户端判定。"""
        sample = _make_sample(answer="B")
        mode = ModelMode(judge_model="mock-gpt-4")
        # Mock 客户端会返回 {"correct": true, ...} JSON
        result = mode.judge(
            sample, "B", context={"llm_client": mock_llm_client}
        )
        # Mock 返回 correct=true
        assert isinstance(result.correct, bool)

    def test_human_mode_with_preset_labels(self):
        """人工模式：预置标注生效。"""
        sample = _make_sample(sample_id="human-001", answer="B")
        mode = HumanMode(human_labels={"human-001": True})
        result = mode.judge(sample, "A")  # 即使预测错误，预置标注为 True
        assert result.correct is True
        assert "human_label" in result.reason

    def test_human_mode_fallback_to_rule(self):
        """人工模式：无预置标注时回退到规则判定。"""
        sample = _make_sample(sample_id="no-label", answer="B")
        mode = HumanMode(human_labels={})
        result = mode.judge(sample, "B")
        # 回退到规则，B == B 正确
        assert result.correct is True
        assert "human_fallback" in result.reason


# ===========================================================================
# 四、A/B 报告场景（4 个测试）
# ===========================================================================
class TestABReport:
    """A/B 对比报告测试。"""

    def _setup_two_completed_jobs(
        self, job_manager: JobManager, executor: EvalExecutor
    ) -> tuple[str, str]:
        """辅助：创建两个已完成的评测任务。"""
        # 任务 A：MMLU 规则模式
        req_a = SubmitJobRequest(
            model="model-a", dataset=DatasetName.MMLU, mode=EvalMode.RULE, limit=5
        )
        job_a = job_manager.submit(req_a)
        executor.execute(job_a.job_id)

        # 任务 B：MMLU 规则模式（不同模型名）
        req_b = SubmitJobRequest(
            model="model-b", dataset=DatasetName.MMLU, mode=EvalMode.RULE, limit=5
        )
        job_b = job_manager.submit(req_b)
        executor.execute(job_b.job_id)

        return job_a.job_id, job_b.job_id

    def test_ab_report_markdown(self, job_manager, executor, report_generator):
        """A/B 报告 Markdown 格式生成成功。"""
        job_a_id, job_b_id = self._setup_two_completed_jobs(job_manager, executor)
        report = report_generator.generate(job_a_id, job_b_id, highlight_threshold=0.01)
        assert isinstance(report, ABReport)
        assert report.model_a == "model-a"
        assert report.model_b == "model-b"
        assert "模型 A/B 对比报告" in report.content_markdown
        assert "指标对比" in report.content_markdown

    def test_ab_report_html(self, job_manager, executor, report_generator):
        """A/B 报告 HTML 格式生成成功。"""
        job_a_id, job_b_id = self._setup_two_completed_jobs(job_manager, executor)
        report = report_generator.generate(job_a_id, job_b_id, highlight_threshold=0.01)
        assert "<html" in report.content_html
        assert "指标对比" in report.content_html
        assert "<table" in report.content_html

    def test_ab_report_diffs_computed(self, job_manager, executor, report_generator):
        """A/B 报告差异计算正确，包含六指标差异。"""
        job_a_id, job_b_id = self._setup_two_completed_jobs(job_manager, executor)
        report = report_generator.generate(job_a_id, job_b_id, highlight_threshold=0.01)
        # 应有 6 个指标差异
        assert len(report.diffs) == 6
        diff_names = {d.name for d in report.diffs}
        assert diff_names == {
            "accuracy", "recall", "f1", "latency_p95", "cost", "hallucination"
        }

    def test_ab_report_highlight(self, job_manager, executor, report_generator):
        """A/B 报告高亮差异超过阈值的指标。"""
        job_a_id, job_b_id = self._setup_two_completed_jobs(job_manager, executor)
        # 使用大阈值，所有差异都不高亮
        report_no_highlight = report_generator.generate(
            job_a_id, job_b_id, highlight_threshold=1000.0
        )
        assert all(not d.highlighted for d in report_no_highlight.diffs)

        # 使用小阈值，有差异的指标高亮
        report_highlight = report_generator.generate(
            job_a_id, job_b_id, highlight_threshold=0.0
        )
        # 至少有一个高亮（延迟几乎肯定有差异）
        highlighted = [d for d in report_highlight.diffs if d.highlighted]
        assert len(highlighted) >= 1


# ===========================================================================
# 五、评测任务场景 - 核心组件（3 个测试）
# ===========================================================================
class TestJobManagerCore:
    """评测任务管理器核心测试（无需服务启动）。"""

    def test_submit_and_get_job(self, job_manager):
        """提交任务并查询详情。"""
        req = SubmitJobRequest(
            model="test-model", dataset=DatasetName.MMLU, mode=EvalMode.RULE
        )
        job = job_manager.submit(req)
        assert job.job_id.startswith("eval-")
        assert job.status == JobStatus.PENDING
        assert job.model == "test-model"

        # 查询
        retrieved = job_manager.get(job.job_id)
        assert retrieved is not None
        assert retrieved.job_id == job.job_id

    def test_job_logs(self, job_manager):
        """任务日志记录正确。"""
        req = SubmitJobRequest(
            model="test-model", dataset=DatasetName.CEVAL, mode=EvalMode.RULE
        )
        job = job_manager.submit(req)
        logs = job_manager.get_logs(job.job_id)
        assert logs is not None
        assert len(logs) >= 1
        assert "任务已提交" in logs[0]

    def test_terminate_job(self, job_manager):
        """终止任务状态更新正确。"""
        req = SubmitJobRequest(
            model="test-model", dataset=DatasetName.CMMLU, mode=EvalMode.RULE
        )
        job = job_manager.submit(req)
        terminated = job_manager.terminate(job.job_id)
        assert terminated is not None
        assert terminated.status == JobStatus.TERMINATED


# ===========================================================================
# 六、评测执行器集成测试（2 个测试）
# ===========================================================================
class TestExecutorIntegration:
    """评测执行器集成测试（使用 Mock LLM）。"""

    def test_execute_rule_mode_mmlu(self, job_manager, executor):
        """执行 MMLU 规则模式评测任务，任务成功完成。"""
        req = SubmitJobRequest(
            model="mock-gpt-4",
            dataset=DatasetName.MMLU,
            mode=EvalMode.RULE,
            limit=5,
        )
        job = job_manager.submit(req)
        executor.execute(job.job_id)

        result = job_manager.get(job.job_id)
        assert result.status == JobStatus.SUCCEEDED
        assert result.results is not None
        assert result.total_samples == 5
        assert result.processed_samples == 5

    def test_execute_all_three_modes(self, job_manager, executor):
        """三种模式评测任务均可成功执行。"""
        for mode in [EvalMode.RULE, EvalMode.MODEL, EvalMode.HUMAN]:
            req = SubmitJobRequest(
                model="mock-gpt-4",
                dataset=DatasetName.MMLU,
                mode=mode,
                limit=3,
                judge_model="mock-gpt-4" if mode == EvalMode.MODEL else "",
            )
            job = job_manager.submit(req)
            executor.execute(job.job_id)
            result = job_manager.get(job.job_id)
            assert result.status == JobStatus.SUCCEEDED, (
                f"模式 {mode} 执行失败: {result.error}"
            )


# ===========================================================================
# 七、HTTP API 场景（需要 Docker 服务，4 个测试）
# ===========================================================================
class TestHTTPAPI:
    """HTTP API 集成测试（需要 evaluation 服务启动）。"""

    @pytest.fixture(autouse=True)
    def _skip_if_unavailable(self, evaluation_available):
        """服务不可用时跳过整个类的测试。"""
        if not evaluation_available:
            pytest.skip("Docker 容器 it-evaluation 不可用")

    def test_health_check(self, evaluation_url):
        """HTTP 健康检查返回 200。"""
        resp = requests.get(evaluation_url + "/health", timeout=10)
        assert resp.status_code == 200
        body = resp.json()
        assert body.get("status") == "UP"
        assert body.get("component") == "evaluation"

    def test_http_submit_and_get_job(self, evaluation_url):
        """HTTP 提交评测任务并查询详情。"""
        # 提交
        resp = requests.post(
            evaluation_url + "/api/v1/eval/jobs",
            json={
                "model": "mock-gpt-4",
                "dataset": "mmlu",
                "mode": "rule",
                "limit": 5,
            },
            timeout=60,
        )
        assert resp.status_code == 200
        job = resp.json()
        job_id = job["job_id"]
        assert job["status"] in ("succeeded", "running", "pending")

        # 查询详情
        resp = requests.get(
            evaluation_url + f"/api/v1/eval/jobs/{job_id}", timeout=10
        )
        assert resp.status_code == 200
        detail = resp.json()
        assert detail["job_id"] == job_id

    def test_http_job_logs(self, evaluation_url):
        """HTTP 查询任务日志。"""
        # 先提交任务
        resp = requests.post(
            evaluation_url + "/api/v1/eval/jobs",
            json={
                "model": "mock-gpt-4",
                "dataset": "cmmlu",
                "mode": "rule",
                "limit": 3,
            },
            timeout=60,
        )
        job_id = resp.json()["job_id"]

        # 查询日志
        resp = requests.get(
            evaluation_url + f"/api/v1/eval/jobs/{job_id}/logs", timeout=10
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["job_id"] == job_id
        assert len(body["logs"]) > 0

    def test_http_ab_report(self, evaluation_url):
        """HTTP 生成 A/B 对比报告。"""
        # 提交两个任务
        job_a = requests.post(
            evaluation_url + "/api/v1/eval/jobs",
            json={"model": "model-a", "dataset": "mmlu", "mode": "rule", "limit": 5},
            timeout=60,
        ).json()
        job_b = requests.post(
            evaluation_url + "/api/v1/eval/jobs",
            json={"model": "model-b", "dataset": "mmlu", "mode": "rule", "limit": 5},
            timeout=60,
        ).json()

        # 生成 A/B 报告
        resp = requests.post(
            evaluation_url + "/api/v1/eval/ab-report",
            json={
                "job_a": job_a["job_id"],
                "job_b": job_b["job_id"],
                "format": "markdown",
                "highlight_threshold": 0.01,
            },
            timeout=30,
        )
        assert resp.status_code == 200
        report = resp.json()
        assert report["model_a"] == "model-a"
        assert report["model_b"] == "model-b"
        assert "模型 A/B 对比报告" in report["content_markdown"]
        assert "<html" in report["content_html"]


# ===========================================================================
# 八、标准集 HTTP API 测试（需要服务，1 个测试）
# ===========================================================================
class TestDatasetHTTPAPI:
    """标准集 HTTP API 测试。"""

    @pytest.fixture(autouse=True)
    def _skip_if_unavailable(self, evaluation_available):
        if not evaluation_available:
            pytest.skip("Docker 容器 it-evaluation 不可用")

    def test_http_list_datasets(self, evaluation_url):
        """HTTP 列出支持的标准集。"""
        resp = requests.get(
            evaluation_url + "/api/v1/eval/datasets", timeout=10
        )
        assert resp.status_code == 200
        body = resp.json()
        names = {d["name"] for d in body["datasets"]}
        assert {"mmlu", "cmmlu", "ceval", "custom"}.issubset(names)