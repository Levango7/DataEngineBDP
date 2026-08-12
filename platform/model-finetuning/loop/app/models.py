"""闭环编排数据模型.

定义微调→评测→部署一键闭环的完整数据结构：
- 闭环任务提交请求（用户输入：基座模型+训练集+微调超参+评测集）
- 闭环任务实体（内部状态：含三步子任务 ID 与状态机）
- 各步骤执行结果
- WebSocket 推送消息

闭环状态机：
    pending → finetuning → evaluating → deploying → completed
                                  ↘ failed（任一步失败）
                                  ↘ cancelled（用户取消）
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


# ============================================================
# 闭环任务状态枚举
# ============================================================
class LoopStatus(str, Enum):
    """闭环任务状态."""

    PENDING = "pending"            # 已提交，等待执行
    FINETUNING = "finetuning"      # 微调中
    EVALUATING = "evaluating"      # 评测中
    DEPLOYING = "deploying"        # 部署中
    COMPLETED = "completed"        # 全流程完成
    FAILED = "failed"              # 失败
    CANCELLED = "cancelled"        # 已取消


# 终态集合
TERMINAL_STATUSES = frozenset(
    {LoopStatus.COMPLETED, LoopStatus.FAILED, LoopStatus.CANCELLED}
)

# 步骤名 → 对应状态
STEP_TO_STATUS = {
    "finetune": LoopStatus.FINETUNING,
    "evaluate": LoopStatus.EVALUATING,
    "deploy": LoopStatus.DEPLOYING,
}


# ============================================================
# 微调配置（与 T032 微调引擎对齐，简化版）
# ============================================================
class LoRAConfig(BaseModel):
    """LoRA 微调配置."""

    rank: int = Field(default=16, ge=1, le=64, description="LoRA rank")
    alpha: int = Field(default=32, ge=1, description="LoRA alpha")
    dropout: float = Field(default=0.05, ge=0.0, le=1.0)
    targetModules: list[str] = Field(
        default_factory=lambda: ["q_proj", "k_proj", "v_proj", "o_proj"]
    )


class Hyperparams(BaseModel):
    """微调超参数."""

    epochs: int = Field(default=1, ge=1, le=100)
    batchSize: int = Field(default=4, ge=1, le=128)
    learningRate: float = Field(default=2e-4, gt=0)
    maxSeqLength: int = Field(default=1024, ge=64)
    loggingSteps: int = Field(default=5, ge=1)


class FinetuneConfig(BaseModel):
    """微调配置."""

    method: str = Field(default="lora", description="微调方式：lora/qlora/full")
    framework: str = Field(
        default="peft", description="框架：peft/llama_factory/deepspeed"
    )
    lora: Optional[LoRAConfig] = Field(default=None)
    hyperparams: Hyperparams = Field(default_factory=Hyperparams)


class DatasetSpec(BaseModel):
    """数据集描述."""

    name: str
    path: str
    format: str = Field(default="alpaca")


class GPURequirement(BaseModel):
    """GPU 资源需求."""

    count: int = Field(default=1, ge=1, le=32)
    type: str = Field(default="any")
    memoryGB: int = Field(default=0, ge=0)


# ============================================================
# 评测配置（与 T031 评测平台对齐，简化版）
# ============================================================
class EvalConfig(BaseModel):
    """评测配置."""

    dataset: str = Field(default="cmmlu", description="评测数据集")
    mode: str = Field(default="rule", description="评测模式：rule/model/human")
    metrics: list[str] = Field(
        default_factory=lambda: [
            "accuracy", "recall", "f1",
            "latency_p95", "cost", "hallucination",
        ]
    )
    limit: int = Field(default=0, ge=0, description="限制样本数，0 表示全部")


# ============================================================
# 部署配置
# ============================================================
class DeployConfig(BaseModel):
    """部署配置."""

    runtime: str = Field(
        default="vllm", description="推理运行时：vllm/triton/simple"
    )
    port: int = Field(default=8000, ge=1024, le=65535)
    replicas: int = Field(default=1, ge=1, le=10)
    gpuCount: int = Field(default=1, ge=1, le=8)
    autoRollback: bool = Field(
        default=False, description="评测不达标时自动回滚"
    )
    # 评测达标阈值（accuracy 下限），低于此值不部署
    minAccuracy: float = Field(
        default=0.0, ge=0.0, le=1.0,
        description="评测准确率下限，低于此值不部署"
    )


# ============================================================
# 闭环任务提交请求
# ============================================================
class LoopTaskRequest(BaseModel):
    """闭环任务提交请求.

    用户通过 POST /api/v1/loop/tasks 提交，
    系统自动执行 微调 → 评测 → 部署 三步。
    """

    taskName: str = Field(min_length=1, max_length=128)
    baseModel: str = Field(description="基座模型 ID 或路径")
    trainDataset: DatasetSpec = Field(description="训练数据集")
    evalDataset: str = Field(
        default="cmmlu", description="评测数据集名称"
    )
    finetune: FinetuneConfig = Field(
        default_factory=FinetuneConfig, description="微调配置"
    )
    eval: EvalConfig = Field(
        default_factory=EvalConfig, description="评测配置"
    )
    deploy: DeployConfig = Field(
        default_factory=DeployConfig, description="部署配置"
    )
    gpu: GPURequirement = Field(default_factory=GPURequirement)
    outputDir: str = Field(
        default="/data/finetune-loop/output", description="输出目录"
    )
    tenantId: str = Field(default="default")
    description: Optional[str] = Field(default=None)
    # 是否跳过部署步骤（仅微调+评测）
    skipDeploy: bool = Field(
        default=False, description="是否跳过部署步骤"
    )


# ============================================================
# 各步骤执行结果
# ============================================================
class FinetuneStepResult(BaseModel):
    """微调步骤结果."""

    taskId: Optional[str] = None
    status: str = "pending"
    adapterPath: Optional[str] = Field(
        default=None, description="Adapter 权重路径"
    )
    outputModelPath: Optional[str] = None
    startedAt: Optional[datetime] = None
    finishedAt: Optional[datetime] = None
    error: Optional[str] = None
    # 训练指标（最新 loss/lr）
    metrics: dict[str, Any] = Field(default_factory=dict)


class EvalStepResult(BaseModel):
    """评测步骤结果."""

    jobId: Optional[str] = None
    status: str = "pending"
    reportId: Optional[str] = None
    # 六指标
    accuracy: float = 0.0
    recall: float = 0.0
    f1: float = 0.0
    latencyP95: float = 0.0
    cost: float = 0.0
    hallucination: float = 0.0
    startedAt: Optional[datetime] = None
    finishedAt: Optional[datetime] = None
    error: Optional[str] = None


class DeployStepResult(BaseModel):
    """部署步骤结果."""

    deploymentId: Optional[str] = None
    status: str = "pending"
    endpoint: Optional[str] = Field(
        default=None, description="推理服务访问端点"
    )
    modelVersion: Optional[str] = None
    startedAt: Optional[datetime] = None
    finishedAt: Optional[datetime] = None
    error: Optional[str] = None
    healthy: bool = False


# ============================================================
# 闭环任务实体
# ============================================================
class LoopTask(BaseModel):
    """闭环任务实体."""

    taskId: str = Field(description="闭环任务唯一 ID")
    request: LoopTaskRequest
    status: LoopStatus = Field(default=LoopStatus.PENDING)
    currentStep: str = Field(
        default="finetune", description="当前执行步骤"
    )

    createdAt: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc)
    )
    updatedAt: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc)
    )
    finishedAt: Optional[datetime] = None

    # 各步骤结果
    finetuneResult: FinetuneStepResult = Field(
        default_factory=FinetuneStepResult
    )
    evalResult: EvalStepResult = Field(default_factory=EvalStepResult)
    deployResult: DeployStepResult = Field(
        default_factory=DeployStepResult
    )

    # Adapter 版本号（版本化模块分配）
    adapterVersion: Optional[str] = None
    # 评测报告版本号
    reportVersion: Optional[str] = None

    # 错误信息（任一步失败时填充）
    errorMessage: Optional[str] = None

    def is_terminal(self) -> bool:
        """判断是否处于终态。"""
        return self.status in TERMINAL_STATUSES

    def touch(self) -> None:
        """更新 updatedAt 时间戳。"""
        self.updatedAt = datetime.now(timezone.utc)

    def mark_failed(self, step: str, error: str) -> None:
        """标记任务失败。"""
        self.status = LoopStatus.FAILED
        self.currentStep = step
        self.errorMessage = f"[{step}] {error}"
        self.finishedAt = datetime.now(timezone.utc)
        self.touch()


# ============================================================
# 闭环任务响应
# ============================================================
class LoopTaskResponse(BaseModel):
    """闭环任务查询响应."""

    taskId: str
    taskName: str
    status: LoopStatus
    currentStep: str
    baseModel: str
    method: str
    framework: str
    adapterVersion: Optional[str] = None
    reportVersion: Optional[str] = None
    createdAt: datetime
    updatedAt: datetime
    finishedAt: Optional[datetime] = None
    errorMessage: Optional[str] = None

    finetuneResult: FinetuneStepResult
    evalResult: EvalStepResult
    deployResult: DeployStepResult

    @classmethod
    def from_task(cls, task: LoopTask) -> "LoopTaskResponse":
        """从实体构造响应。"""
        return cls(
            taskId=task.taskId,
            taskName=task.request.taskName,
            status=task.status,
            currentStep=task.currentStep,
            baseModel=task.request.baseModel,
            method=task.request.finetune.method,
            framework=task.request.finetune.framework,
            adapterVersion=task.adapterVersion,
            reportVersion=task.reportVersion,
            createdAt=task.createdAt,
            updatedAt=task.updatedAt,
            finishedAt=task.finishedAt,
            errorMessage=task.errorMessage,
            finetuneResult=task.finetuneResult,
            evalResult=task.evalResult,
            deployResult=task.deployResult,
        )


class LoopTaskListResponse(BaseModel):
    """闭环任务列表响应."""

    total: int = Field(ge=0)
    data: list[LoopTaskResponse] = Field(default_factory=list)


# ============================================================
# WebSocket 推送消息
# ============================================================
class WSMessage(BaseModel):
    """WebSocket 推送消息.

    type 取值：
        - status：状态变更
        - progress：进度更新
        - metrics：训练指标（loss/lr/GPU）
        - log：日志
        - error：错误
        - completed：完成
    """

    type: str
    taskId: str
    timestamp: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc)
    )
    data: dict[str, Any] = Field(default_factory=dict)


# ============================================================
# 健康检查
# ============================================================
class HealthResponse(BaseModel):
    """健康检查响应."""

    status: str = "UP"
    service: str = "finetuning-loop"
    version: str = "0.1.0"
    mockMode: bool = True
    finetuneReachable: bool = False
    evaluationReachable: bool = False
    registryReachable: bool = False