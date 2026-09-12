"""微调任务数据模型.

定义微调任务的完整生命周期数据结构，包括：
- 任务提交请求（用户输入）
- 任务实体（内部状态）
- 任务状态枚举
- 日志条目
- 任务查询响应

任务状态机：
    PENDING → RUNNING → SUCCEEDED
                    ↘ FAILED
                    ↘ TERMINATED
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Optional

from app.models.finetune_config import FinetuneConfig
from pydantic import BaseModel, Field


# ============================================================
# 任务状态枚举
# ============================================================
class TaskStatus(str, Enum):
    """微调任务状态."""

    PENDING = "pending"  # 已提交，等待调度
    RUNNING = "running"  # 训练中
    SUCCEEDED = "succeeded"  # 训练成功完成
    FAILED = "failed"  # 训练失败
    TERMINATED = "terminated"  # 被用户主动终止


# 终态集合，用于判断任务是否已结束
TERMINAL_STATUSES = frozenset({TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.TERMINATED})


# ============================================================
# GPU 资源需求
# ============================================================
class GPURequirement(BaseModel):
    """GPU 资源需求.

    Attributes:
        count: 需要的 GPU 卡数。
        type: GPU 型号，如 "A100-40G" / "V100-32G" / "任意"。
        memoryGB: 每卡显存下限（GB），0 表示不限制。
    """

    count: int = Field(default=1, ge=1, le=32, description="GPU 卡数")
    type: str = Field(default="any", description="GPU 型号")
    memoryGB: int = Field(default=0, ge=0, description="每卡显存下限 GB")


# ============================================================
# 数据集描述
# ============================================================
class DatasetSpec(BaseModel):
    """训练数据集描述.

    支持两种数据来源：
    - 本地路径（HuggingFace datasets 格式或 JSON/JSONL）
    - HuggingFace Hub 仓库 ID

    Attributes:
        name: 数据集名称（用于展示与日志）。
        path: 本地路径或 Hub ID。
        split: 使用的数据集 split，如 "train" / "validation"。
        format: 数据格式，如 "alpaca" / "sharegpt" / "jsonl"。
    """

    name: str = Field(description="数据集名称")
    path: str = Field(description="本地路径或 HuggingFace Hub ID")
    split: str = Field(default="train", description="数据集 split")
    format: str = Field(default="alpaca", description="数据格式")
    validationPath: Optional[str] = Field(default=None, description="验证集路径（可选）")


# ============================================================
# 任务提交请求
# ============================================================
class FinetuneTaskRequest(BaseModel):
    """微调任务提交请求.

    用户通过 POST /api/v1/finetune/tasks 提交的请求体。

    Attributes:
        taskName: 任务名称（用户可读）。
        baseModel: 基座模型 ID 或本地路径，如 "meta-llama/Llama-2-7b-hf"。
        dataset: 训练数据集描述。
        config: 微调配置（方式 + 框架 + 超参）。
        gpu: GPU 资源需求。
        outputDir: 输出目录（存放 checkpoint / adapter 权重）。
        tenantId: 租户 ID（用于多租户隔离与配额）。
        description: 任务描述（可选）。
    """

    taskName: str = Field(min_length=1, max_length=128, description="任务名称")
    baseModel: str = Field(description="基座模型 ID 或本地路径")
    dataset: DatasetSpec = Field(description="训练数据集")
    config: FinetuneConfig = Field(description="微调配置")
    gpu: GPURequirement = Field(default_factory=GPURequirement, description="GPU 需求")
    outputDir: str = Field(default="/data/finetune/output", description="输出目录")
    tenantId: str = Field(default="default", description="租户 ID")
    description: Optional[str] = Field(default=None, description="任务描述")


# ============================================================
# 任务实体（内部状态）
# ============================================================
class FinetuneTask(BaseModel):
    """微调任务实体.

    系统内部维护的完整任务记录，包含状态、时间戳、调度信息等。

    Attributes:
        taskId: 任务唯一 ID（UUID）。
        request: 原始提交请求。
        status: 当前状态。
        createdAt: 创建时间。
        startedAt: 开始训练时间。
        finishedAt: 结束时间。
        assignedNode: 被调度到的 GPU 节点名。
        assignedGPUs: 分配的 GPU ID 列表。
        outputModelPath: 训练产出的模型路径。
        errorMessage: 失败时的错误信息。
        progress: 训练进度百分比 0~100。
    """

    taskId: str = Field(description="任务唯一 ID")
    request: FinetuneTaskRequest = Field(description="原始请求")
    status: TaskStatus = Field(default=TaskStatus.PENDING, description="任务状态")
    createdAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc), description="创建时间")
    startedAt: Optional[datetime] = Field(default=None, description="开始时间")
    finishedAt: Optional[datetime] = Field(default=None, description="结束时间")
    assignedNode: Optional[str] = Field(default=None, description="调度节点")
    assignedGPUs: list[int] = Field(default_factory=list, description="分配的 GPU ID 列表")
    outputModelPath: Optional[str] = Field(default=None, description="产出模型路径")
    errorMessage: Optional[str] = Field(default=None, description="错误信息")
    progress: float = Field(default=0.0, ge=0.0, le=100.0, description="进度百分比")

    def is_terminal(self) -> bool:
        """判断任务是否处于终态."""
        return self.status in TERMINAL_STATUSES

    def mark_running(self, node: str, gpus: list[int]) -> None:
        """标记任务为运行中，记录调度结果."""
        self.status = TaskStatus.RUNNING
        self.startedAt = datetime.now(timezone.utc)
        self.assignedNode = node
        self.assignedGPUs = list(gpus)

    def mark_succeeded(self, output_path: str) -> None:
        """标记任务成功完成."""
        self.status = TaskStatus.SUCCEEDED
        self.finishedAt = datetime.now(timezone.utc)
        self.outputModelPath = output_path
        self.progress = 100.0

    def mark_failed(self, error: str) -> None:
        """标记任务失败."""
        self.status = TaskStatus.FAILED
        self.finishedAt = datetime.now(timezone.utc)
        self.errorMessage = error

    def mark_terminated(self) -> None:
        """标记任务被终止."""
        self.status = TaskStatus.TERMINATED
        self.finishedAt = datetime.now(timezone.utc)


# ============================================================
# 日志条目
# ============================================================
class LogEntry(BaseModel):
    """训练日志条目.

    实时记录训练过程中的 loss / lr / GPU 利用率等指标。

    Attributes:
        timestamp: 日志时间。
        step: 训练步数。
        epoch: 当前轮次。
        loss: 当前 loss。
        learningRate: 当前学习率。
        gpuUtil: 各卡 GPU 利用率（百分比）。
        gpuMemory: 各卡显存占用（GB）。
        message: 原始日志文本。
    """

    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    step: int = Field(default=0, ge=0)
    epoch: float = Field(default=0.0, ge=0.0)
    loss: Optional[float] = Field(default=None, description="当前 loss")
    learningRate: Optional[float] = Field(default=None, description="当前学习率")
    gpuUtil: list[float] = Field(default_factory=list, description="各卡利用率")
    gpuMemory: list[float] = Field(default_factory=list, description="各卡显存 GB")
    message: str = Field(default="", description="原始日志文本")


# ============================================================
# 任务查询响应
# ============================================================
class FinetuneTaskResponse(BaseModel):
    """微调任务查询响应.

    对外暴露的任务详情，隐藏内部敏感字段。
    """

    taskId: str
    taskName: str
    status: TaskStatus
    method: str
    framework: str
    baseModel: str
    datasetName: str
    progress: float
    createdAt: datetime
    startedAt: Optional[datetime] = None
    finishedAt: Optional[datetime] = None
    assignedNode: Optional[str] = None
    assignedGPUs: list[int] = Field(default_factory=list)
    outputModelPath: Optional[str] = None
    errorMessage: Optional[str] = None
    gpuCount: int = 1

    @classmethod
    def from_task(cls, task: FinetuneTask) -> "FinetuneTaskResponse":
        """从任务实体构造响应."""
        return cls(
            taskId=task.taskId,
            taskName=task.request.taskName,
            status=task.status,
            method=task.request.config.method.value,
            framework=task.request.config.framework.value,
            baseModel=task.request.baseModel,
            datasetName=task.request.dataset.name,
            progress=task.progress,
            createdAt=task.createdAt,
            startedAt=task.startedAt,
            finishedAt=task.finishedAt,
            assignedNode=task.assignedNode,
            assignedGPUs=task.assignedGPUs,
            outputModelPath=task.outputModelPath,
            errorMessage=task.errorMessage,
            gpuCount=task.request.gpu.count,
        )


# ============================================================
# 任务列表响应
# ============================================================
class FinetuneTaskListResponse(BaseModel):
    """微调任务列表响应."""

    total: int = Field(ge=0)
    data: list[FinetuneTaskResponse] = Field(default_factory=list)


# ============================================================
# 日志查询响应
# ============================================================
class LogListResponse(BaseModel):
    """训练日志查询响应."""

    taskId: str
    total: int = Field(ge=0)
    entries: list[LogEntry] = Field(default_factory=list)
