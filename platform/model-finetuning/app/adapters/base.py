"""微调框架适配器基类.

定义统一的适配器接口，三种框架（LLaMA-Factory / PEFT / DeepSpeed）
各自实现该接口，业务层通过多态调用，无需关心具体框架差异。

接口设计：
    - build_command: 构建训练命令/参数（不实际执行）
    - start: 启动训练，返回进程句柄或任务句柄
    - stop: 终止训练
    - parse_log_line: 解析日志行，提取 loss/lr/GPU 指标
    - validate_config: 校验配置合法性
"""
from __future__ import annotations

import abc
from dataclasses import dataclass
from typing import Any, Optional

from app.models.finetune_config import FinetuneConfig
from app.models.finetune_task import FinetuneTask, LogEntry


@dataclass
class ProcessHandle:
    """训练进程句柄.

    封装 subprocess.Popen 或 mock 句柄，统一对外接口。

    Attributes:
        pid: 进程 ID（mock 模式下为 0）。
        isMock: 是否为 mock 进程（CPU 验证模式）。
        extra: 框架特定附加信息。
    """

    pid: int
    isMock: bool = False
    extra: dict[str, Any] = None  # type: ignore[assignment]


class BaseAdapter(abc.ABC):
    """微调框架适配器抽象基类."""

    #: 适配器名称，子类覆盖
    name: str = "base"

    def __init__(self, workDir: str = "/tmp/finetune", mockMode: bool = False):
        """初始化适配器.

        Args:
            workDir: 工作目录，存放临时文件与日志。
            mockMode: 是否为 Mock 模式（不实际调用 GPU/框架，仅验证流程）。
        """
        self.workDir = workDir
        self.mockMode = mockMode

    @abc.abstractmethod
    def validate_config(self, config: FinetuneConfig) -> list[str]:
        """校验配置合法性，返回错误信息列表（空列表表示通过）.

        Args:
            config: 微调配置。

        Returns:
            错误信息列表，空列表表示配置合法。
        """

    @abc.abstractmethod
    def build_command(self, task: FinetuneTask) -> list[str]:
        """构建训练命令（CLI 参数列表）.

        不实际执行，仅生成命令用于日志展示与 subprocess 调用。

        Args:
            task: 微调任务实体。

        Returns:
            命令列表，如 ["llamafactory-cli", "train", "config.yaml"]。
        """

    @abc.abstractmethod
    def start(self, task: FinetuneTask) -> ProcessHandle:
        """启动训练.

        Args:
            task: 微调任务实体。

        Returns:
            进程句柄。
        """

    @abc.abstractmethod
    def stop(self, handle: ProcessHandle) -> bool:
        """终止训练.

        Args:
            handle: 进程句柄。

        Returns:
            True 表示成功终止。
        """

    def parse_log_line(self, line: str, step: int = 0) -> Optional[LogEntry]:
        """解析单行日志，提取训练指标.

        默认实现仅保留原始文本，子类可覆盖以提取 loss/lr/GPU 指标。

        Args:
            line: 日志行文本。
            step: 当前步数提示。

        Returns:
            日志条目，或 None 表示该行不含可解析指标。
        """
        if not line.strip():
            return None
        return LogEntry(step=step, message=line.rstrip("\n"))

    def describe(self) -> dict[str, Any]:
        """返回适配器描述信息（用于 /api/v1/finetune/adapters 端点）."""
        return {
            "name": self.name,
            "mockMode": self.mockMode,
            "workDir": self.workDir,
        }