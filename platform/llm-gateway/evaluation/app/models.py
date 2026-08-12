"""Pydantic 数据模型定义。

定义评测任务的请求/响应结构，覆盖：
- 评测任务提交请求（模型 + 数据集 + 指标 + 模式）
- 评测任务详情响应
- 单条评测样本与预测结果
- 六指标计算结果
- A/B 对比报告

所有模型使用 Pydantic v2，启用严格模式与 JSON 序列化。
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field, field_validator


# ---------------------------------------------------------------------------
# 枚举类型
# ---------------------------------------------------------------------------
class EvalMode(str, Enum):
    """评测模式。

    - RULE：规则模式，正则/关键字匹配判定答案正确性
    - MODEL：模型模式，LLM as Judge，由评判模型判定答案正确性
    - HUMAN：人工模式，人工标注界面，由人工标注答案正确性
    """

    RULE = "rule"
    MODEL = "model"
    HUMAN = "human"


class JobStatus(str, Enum):
    """评测任务状态。

    - PENDING：已提交，等待执行
    - RUNNING：执行中
    - SUCCEEDED：执行成功
    - FAILED：执行失败
    - TERMINATED：被用户终止
    """

    PENDING = "pending"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    TERMINATED = "terminated"


class DatasetName(str, Enum):
    """支持的标准集名称。"""

    MMLU = "mmlu"  # 英文多任务
    CMMLU = "cmmlu"  # 中文多任务
    CEVAL = "ceval"  # 中文评测
    CUSTOM = "custom"  # 自定义数据集


class MetricName(str, Enum):
    """六指标名称。"""

    ACCURACY = "accuracy"  # 准确率
    RECALL = "recall"  # 召回率
    F1 = "f1"  # F1
    LATENCY_P95 = "latency_p95"  # P95 延迟（毫秒）
    COST = "cost"  # Token 成本
    HALLUCINATION = "hallucination"  # 幻觉率


# 六指标全集合，便于默认填充
ALL_METRICS: list[MetricName] = list(MetricName)


# ---------------------------------------------------------------------------
# 评测样本与预测结果
# ---------------------------------------------------------------------------
class EvalSample(BaseModel):
    """单条评测样本（标准集格式）。

    Attributes:
        id: 样本唯一标识
        question: 问题文本
        choices: 候选答案列表（选择题适用）
        answer: 标准答案（choices 的索引或文本）
        subject: 任务类别（如 math, history）
        context: 上下文/参考信息（可选，用于事实核查）
    """

    id: str
    question: str
    choices: list[str] = Field(default_factory=list)
    answer: str = ""
    subject: str = "unknown"
    context: str = ""

    model_config = {"extra": "allow"}


class PredictionResult(BaseModel):
    """单条样本的预测结果。

    Attributes:
        sample_id: 对应样本 ID
        prediction: 模型预测答案
        correct: 是否正确（由评测模式判定）
        latency_ms: 单条延迟（毫秒）
        prompt_tokens: 输入 Token 数
        completion_tokens: 输出 Token 数
        total_tokens: 总 Token 数
        hallucination: 是否幻觉（True 表示存在幻觉）
        raw_response: 原始响应（调试用）
    """

    sample_id: str
    prediction: str = ""
    correct: bool = False
    latency_ms: float = 0.0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    hallucination: bool = False
    raw_response: dict[str, Any] = Field(default_factory=dict)


# ---------------------------------------------------------------------------
# 指标结果
# ---------------------------------------------------------------------------
class MetricResult(BaseModel):
    """单个指标计算结果。

    Attributes:
        name: 指标名称
        value: 指标值
        description: 指标描述
    """

    name: str
    value: float
    description: str = ""

    model_config = {"extra": "allow"}


class MetricsBundle(BaseModel):
    """六指标计算结果集合。"""

    accuracy: float = Field(ge=0.0, le=1.0, description="准确率 [0,1]")
    recall: float = Field(ge=0.0, le=1.0, description="召回率 [0,1]")
    f1: float = Field(ge=0.0, le=1.0, description="F1 [0,1]")
    latency_p95: float = Field(ge=0.0, description="P95 延迟（毫秒）")
    cost: float = Field(ge=0.0, description="Token 成本（总 Token 数）")
    hallucination: float = Field(ge=0.0, le=1.0, description="幻觉率 [0,1]")

    def to_list(self) -> list[MetricResult]:
        """转换为 MetricResult 列表，便于序列化与对比。"""
        return [
            MetricResult(name=MetricName.ACCURACY.value, value=self.accuracy, description="准确率"),
            MetricResult(name=MetricName.RECALL.value, value=self.recall, description="召回率"),
            MetricResult(name=MetricName.F1.value, value=self.f1, description="F1"),
            MetricResult(name=MetricName.LATENCY_P95.value, value=self.latency_p95, description="P95 延迟（毫秒）"),
            MetricResult(name=MetricName.COST.value, value=self.cost, description="Token 成本"),
            MetricResult(name=MetricName.HALLUCINATION.value, value=self.hallucination, description="幻觉率"),
        ]


# ---------------------------------------------------------------------------
# 评测任务请求/响应
# ---------------------------------------------------------------------------
class SubmitJobRequest(BaseModel):
    """提交评测任务请求。

    必填：model + dataset + mode
    可选：metrics（默认全六指标）、limit（限制样本数）、judge_model（模型模式评判模型）
    """

    model: str = Field(..., description="被评测模型名（如 mock-gpt-4）")
    dataset: DatasetName = Field(..., description="标准集名称")
    mode: EvalMode = Field(..., description="评测模式")
    metrics: list[MetricName] = Field(
        default_factory=lambda: list(ALL_METRICS),
        description="计算的指标列表，默认全六指标",
    )
    limit: int = Field(default=0, ge=0, description="限制样本数，0 表示全部")
    judge_model: str = Field(default="", description="模型模式的评判模型名")
    # 规则模式参数
    rule_patterns: list[str] = Field(
        default_factory=list,
        description="规则模式正则/关键字列表",
    )
    # 自定义数据集样本（dataset=custom 时使用）
    custom_samples: list[EvalSample] = Field(
        default_factory=list,
        description="自定义数据集样本",
    )
    # 人工模式预置标注（sample_id → correct）
    human_labels: dict[str, bool] = Field(
        default_factory=dict,
        description="人工模式预置标注",
    )

    @field_validator("judge_model")
    @classmethod
    def validate_judge_model(cls, v: str, info) -> str:
        """模型模式必须提供 judge_model。"""
        mode = info.data.get("mode")
        if mode == EvalMode.MODEL and not v:
            # 允许为空，executor 会使用默认评判模型
            v = "mock-gpt-4"
        return v


class JobInfo(BaseModel):
    """评测任务详情。"""

    job_id: str
    model: str
    dataset: str
    mode: str
    metrics: list[str]
    status: JobStatus
    created_at: datetime
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    limit: int = 0
    total_samples: int = 0
    processed_samples: int = 0
    # 结果（任务完成后填充）
    results: Optional[MetricsBundle] = None
    predictions: list[PredictionResult] = Field(default_factory=list)
    error: str = ""

    model_config = {"extra": "allow"}


class JobListResponse(BaseModel):
    """任务列表响应。"""

    total: int
    jobs: list[JobInfo]


class JobLogsResponse(BaseModel):
    """任务日志响应。"""

    job_id: str
    logs: list[str]


# ---------------------------------------------------------------------------
# A/B 对比报告
# ---------------------------------------------------------------------------
class ABReportRequest(BaseModel):
    """A/B 对比报告生成请求。

    提供两个已完成任务的 job_id，生成对比报告。
    """

    job_a: str = Field(..., description="模型 A 的评测任务 ID")
    job_b: str = Field(..., description="模型 B 的评测任务 ID")
    format: str = Field(default="markdown", description="报告格式：markdown 或 html")
    highlight_threshold: float = Field(
        default=0.05,
        ge=0.0,
        le=1.0,
        description="差异高亮阈值，差异大于此值则高亮",
    )


class MetricDiff(BaseModel):
    """单个指标的 A/B 差异。"""

    name: str
    value_a: float
    value_b: float
    diff: float
    diff_percent: float
    highlighted: bool
    better: str = ""  # "a" / "b" / "tie"


class ABReport(BaseModel):
    """A/B 对比报告。"""

    job_a: str
    job_b: str
    model_a: str
    model_b: str
    dataset: str
    generated_at: datetime
    diffs: list[MetricDiff]
    summary: str
    content_markdown: str
    content_html: str


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
class HealthResponse(BaseModel):
    """健康检查响应。"""

    status: str = "UP"
    component: str = "evaluation"
    version: str = "0.1.0"
    llm_gateway_reachable: bool = False


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def utcnow() -> datetime:
    """返回当前 UTC 时间（带时区信息）。"""
    return datetime.now(timezone.utc)
