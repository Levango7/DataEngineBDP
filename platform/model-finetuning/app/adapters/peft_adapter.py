"""HuggingFace PEFT 适配器.

直接通过 Python 集成 HuggingFace PEFT 库执行 LoRA / QLoRA 微调，
支持 LoRA rank 8/16/32 与 QLoRA 4bit/8bit 量化。

PEFT（Parameter-Efficient Fine-Tuning）是 HuggingFace 官方轻量微调库，
与 transformers Trainer 深度集成，适合单卡或少量卡训练。

本适配器负责：
1. 构造 PEFT 配置（LoraConfig / BitsAndBytesConfig）
2. 加载基座模型（含 4bit/8bit 量化）
3. 通过 transformers Trainer 启动训练
4. Mock 模式下不实际加载模型，仅生成配置并模拟日志
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from typing import Any, Optional

from app.adapters.base import BaseAdapter, ProcessHandle
from app.models.finetune_config import (
    FinetuneConfig,
    FinetuneMethod,
    LoRAConfig,
    QLoRAConfig,
    QuantizationBits,
)
from app.models.finetune_task import FinetuneTask, LogEntry


class PEFTAdapter(BaseAdapter):
    """HuggingFace PEFT 适配器."""

    name = "peft"

    def validate_config(self, config: FinetuneConfig) -> list[str]:
        """校验配置对 PEFT 的合法性.

        PEFT 原生支持 LoRA / QLoRA；全参微调不是 PEFT 的典型用法，
        但仍可通过不应用 PEFT 包装实现，此处给出告警而非错误。
        """
        errors: list[str] = []
        effective = config.resolve_effective_config()

        if config.method == FinetuneMethod.LORA:
            lora = effective.get("lora")
            if lora and lora.rank not in (8, 16, 32, 64, 128):
                errors.append(
                    f"PEFT LoRA rank 建议取 8/16/32，当前 {lora.rank}"
                )
        elif config.method == FinetuneMethod.QLORA:
            qlora = effective.get("qlora")
            if qlora:
                if qlora.quantization not in (
                    QuantizationBits.BIT4,
                    QuantizationBits.BIT8,
                ):
                    errors.append("QLoRA 量化位宽必须为 4bit 或 8bit")
                if qlora.lora.rank not in (8, 16, 32, 64, 128):
                    errors.append(
                        f"QLoRA LoRA rank 建议取 8/16/32，当前 {qlora.lora.rank}"
                    )
        elif config.method == FinetuneMethod.FULL:
            # PEFT 原生不推荐全参微调，但允许（不应用 PEFT 包装）
            pass
        return errors

    def build_peft_config_dict(self, task: FinetuneTask) -> dict[str, Any]:
        """构造 PEFT 配置字典（用于日志展示与序列化）.

        返回的字典结构与 PEFT LoraConfig / BitsAndBytesConfig 字段对应。
        """
        cfg = task.request.config
        result: dict[str, Any] = {"method": cfg.method.value}

        if cfg.method == FinetuneMethod.LORA:
            lora = cfg.lora or LoRAConfig()
            result["lora_config"] = {
                "r": lora.rank,
                "lora_alpha": lora.alpha,
                "lora_dropout": lora.dropout,
                "target_modules": lora.targetModules,
                "bias": "none",
                "task_type": "CAUSAL_LM",
            }
        elif cfg.method == FinetuneMethod.QLORA:
            qlora = cfg.qlora or QLoRAConfig()
            lora = qlora.lora
            quant_bits = 4 if qlora.quantization == QuantizationBits.BIT4 else 8
            result["bnb_config"] = {
                "load_in_4bit": quant_bits == 4,
                "load_in_8bit": quant_bits == 8,
                "bnb_4bit_compute_dtype": qlora.computeDtype,
                "bnb_4bit_quant_storage": qlora.quantStorageDtype,
                "bnb_4bit_use_double_quant": qlora.doubleQuantization,
            }
            result["lora_config"] = {
                "r": lora.rank,
                "lora_alpha": lora.alpha,
                "lora_dropout": lora.dropout,
                "target_modules": lora.targetModules,
                "bias": "none",
                "task_type": "CAUSAL_LM",
            }
        elif cfg.method == FinetuneMethod.FULL:
            result["peft_wrapping"] = False
            full = cfg.full
            if full:
                result["gradient_checkpointing"] = full.gradientCheckpointing

        return result

    def build_training_script(self, task: FinetuneTask) -> str:
        """生成 PEFT 训练 Python 脚本路径.

        将训练逻辑写入独立 .py 文件，通过 subprocess 启动，
        避免在主进程中加载 torch 占用内存。
        """
        peft_cfg = self.build_peft_config_dict(task)
        cfg_path = os.path.join(self.workDir, f"{task.taskId}_peft_config.json")
        os.makedirs(self.workDir, exist_ok=True)
        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "task_id": task.taskId,
                    "base_model": task.request.baseModel,
                    "dataset_path": task.request.dataset.path,
                    "output_dir": task.request.outputDir,
                    "hyperparams": task.request.config.hyperparams.model_dump(),
                    "peft": peft_cfg,
                },
                f,
                ensure_ascii=False,
                indent=2,
            )
        return cfg_path

    def build_command(self, task: FinetuneTask) -> list[str]:
        """构建 python -m app.adapters.peft_runner 启动命令."""
        cfg_path = self.build_training_script(task)
        runner = "app.adapters.peft_runner"
        return [sys.executable, "-m", runner, cfg_path]

    def start(self, task: FinetuneTask) -> ProcessHandle:
        """启动 PEFT 训练."""
        cmd = self.build_command(task)
        task_id = task.taskId
        log_path = os.path.join(self.workDir, f"{task_id}.log")

        if self.mockMode:
            self._write_mock_log(log_path, task)
            return ProcessHandle(pid=0, isMock=True, extra={"cmd": cmd, "log": log_path})

        os.makedirs(self.workDir, exist_ok=True)
        log_fd = open(log_path, "w", encoding="utf-8")  # noqa: SIM115
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=log_fd,
                stderr=subprocess.STDOUT,
                cwd=self.workDir,
            )
        finally:
            log_fd.close()
        return ProcessHandle(
            pid=proc.pid, isMock=False, extra={"proc": proc, "log": log_path}
        )

    def stop(self, handle: ProcessHandle) -> bool:
        """终止 PEFT 训练进程."""
        if handle.isMock:
            return True
        proc = handle.extra.get("proc") if handle.extra else None
        if proc is None:
            return False
        try:
            proc.terminate()
            proc.wait(timeout=10)
            return True
        except subprocess.SubprocessError:
            proc.kill()
            return proc.poll() is not None

    def parse_log_line(self, line: str, step: int = 0) -> Optional[LogEntry]:
        """解析 PEFT/Trainer 日志行.

        PEFT 使用 transformers Trainer，日志格式与 LLaMA-Factory 一致：
            {'loss': 1.234, 'learning_rate': 0.0001, 'epoch': 0.5}
        """
        text = line.strip()
        if not text:
            return None
        dict_match = re.search(r"\{'loss':\s*([\d.eE+-]+)", text)
        lr_match = re.search(r"'learning_rate':\s*([\d.eE+-]+)", text)
        epoch_match = re.search(r"'epoch':\s*([\d.eE+-]+)", text)
        if dict_match:
            return LogEntry(
                step=step,
                epoch=float(epoch_match.group(1)) if epoch_match else 0.0,
                loss=float(dict_match.group(1)),
                learningRate=float(lr_match.group(1)) if lr_match else None,
                message=text,
            )
        return LogEntry(step=step, message=text)

    # ------------------------------------------------------------
    # 内部辅助
    # ------------------------------------------------------------
    def _write_mock_log(self, log_path: str, task: FinetuneTask) -> None:
        """Mock 模式下写入示例训练日志."""
        hp = task.request.config.hyperparams
        total_steps = hp.epochs * 100
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        with open(log_path, "w", encoding="utf-8") as f:
            f.write(f"[mock] PEFT 训练开始，任务 {task.taskId}\n")
            for step in range(0, total_steps + 1, hp.loggingSteps):
                loss = 2.2 * (0.985 ** step)
                lr = hp.learningRate
                epoch = step / 100.0
                f.write(
                    f"{{'loss': {loss:.4f}, 'learning_rate': {lr}, "
                    f"'epoch': {epoch:.2f}}}\n"
                )
            f.write(f"[mock] 训练完成，输出目录 {task.request.outputDir}\n")