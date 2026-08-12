"""LLaMA-Factory 适配器.

通过 subprocess 调用 LLaMA-Factory CLI（llamafactory-cli train）执行微调，
支持 LoRA / QLoRA / 全参三种方式。

LLaMA-Factory 是开源低代码大模型微调框架，提供 YAML 配置驱动的 CLI：
    llamafactory-cli train <config.yaml>

本适配器负责：
1. 将 FinetuneConfig 转换为 LLaMA-Factory YAML 配置
2. 通过 subprocess 启动训练进程
3. 解析 LLaMA-Factory 训练日志，提取 loss/lr/GPU 指标
4. Mock 模式下不实际调用 CLI，仅生成配置并模拟日志
"""
from __future__ import annotations

import os
import re
import shlex
import subprocess
import tempfile
from typing import Any, Optional

import yaml

from app.adapters.base import BaseAdapter, ProcessHandle
from app.models.finetune_config import (
    FinetuneConfig,
    FinetuneMethod,
    QuantizationBits,
)
from app.models.finetune_task import FinetuneTask, LogEntry


class LlamaFactoryAdapter(BaseAdapter):
    """LLaMA-Factory CLI 适配器."""

    name = "llama_factory"

    #: LLaMA-Factory CLI 命令名
    CLI_BIN = "llamafactory-cli"

    def validate_config(self, config: FinetuneConfig) -> list[str]:
        """校验配置对 LLaMA-Factory 的合法性.

        LLaMA-Factory 支持全部三种微调方式，主要校验：
        - LoRA rank 为正整数
        - QLoRA 量化位宽为 4bit/8bit
        - 全参微调时不应同时配置 LoRA 参数
        """
        errors: list[str] = []
        effective = config.resolve_effective_config()

        if config.method == FinetuneMethod.LORA:
            lora = effective.get("lora")
            if lora and lora.rank <= 0:
                errors.append("LoRA rank 必须为正整数")
            if lora and lora.alpha < lora.rank:
                errors.append(
                    f"LoRA alpha({lora.alpha}) 建议 >= rank({lora.rank})"
                )
        elif config.method == FinetuneMethod.QLORA:
            qlora = effective.get("qlora")
            if qlora and qlora.quantization not in (
                QuantizationBits.BIT4,
                QuantizationBits.BIT8,
            ):
                errors.append("QLoRA 量化位宽必须为 4bit 或 8bit")
        elif config.method == FinetuneMethod.FULL:
            if config.lora is not None:
                errors.append("全参微调不应配置 LoRA 参数")
        return errors

    def build_yaml_config(self, task: FinetuneTask) -> dict[str, Any]:
        """将任务配置转换为 LLaMA-Factory YAML 配置字典.

        LLaMA-Factory 配置键名采用 camelCase（与官方一致）。
        """
        req = task.request
        cfg = req.config
        hp = cfg.hyperparams
        ds = req.dataset

        # 基础配置
        yaml_cfg: dict[str, Any] = {
            "model_name_or_path": req.baseModel,
            "dataset_name": ds.name,
            "dataset_dir": ds.path,
            "template": "default",
            "output_dir": req.outputDir,
            "num_train_epochs": hp.epochs,
            "per_device_train_batch_size": hp.batchSize,
            "gradient_accumulation_steps": hp.gradientAccumulationSteps,
            "learning_rate": hp.learningRate,
            "weight_decay": hp.weightDecay,
            "warmup_steps": hp.warmupSteps,
            "max_seq_length": hp.maxSeqLength,
            "logging_steps": hp.loggingSteps,
            "save_steps": hp.saveSteps,
            "seed": hp.seed,
            "fp16": hp.fp16,
            "bf16": hp.bf16,
        }
        if ds.validationPath:
            yaml_cfg["eval_steps"] = hp.evalSteps or 500

        # 按微调方式补充
        if cfg.method == FinetuneMethod.LORA:

            from app.models.finetune_config import LoRAConfig

            lora = cfg.lora or LoRAConfig()
            yaml_cfg.update(
                {
                    "finetuning_type": "lora",
                    "lora_rank": lora.rank,
                    "lora_alpha": lora.alpha,
                    "lora_dropout": lora.dropout,
                    "lora_target": ",".join(lora.targetModules),
                }
            )
        elif cfg.method == FinetuneMethod.QLORA:
            from app.models.finetune_config import LoRAConfig, QLoRAConfig

            qlora = cfg.qlora or QLoRAConfig()
            lora = qlora.lora
            quant_bits = 4 if qlora.quantization == QuantizationBits.BIT4 else 8
            yaml_cfg.update(
                {
                    "finetuning_type": "lora",
                    "quantization_bit": quant_bits,
                    "quantization_method": "bitsandbytes",
                    "lora_rank": lora.rank,
                    "lora_alpha": lora.alpha,
                    "lora_dropout": lora.dropout,
                    "lora_target": ",".join(lora.targetModules),
                }
            )
        elif cfg.method == FinetuneMethod.FULL:
            yaml_cfg["finetuning_type"] = "full"
            full = cfg.full
            if full and full.gradientCheckpointing:
                yaml_cfg["gradient_checkpointing"] = True

        return yaml_cfg

    def build_command(self, task: FinetuneTask) -> list[str]:
        """构建 llamafactory-cli train 命令.

        生成 YAML 配置文件后返回 CLI 命令列表。
        """
        yaml_cfg = self.build_yaml_config(task)
        config_path = os.path.join(
            self.workDir, f"{task.taskId}_llamafactory.yaml"
        )
        os.makedirs(self.workDir, exist_ok=True)
        with open(config_path, "w", encoding="utf-8") as f:
            yaml.safe_dump(yaml_cfg, f, allow_unicode=True, sort_keys=False)

        return [self.CLI_BIN, "train", config_path]

    def start(self, task: FinetuneTask) -> ProcessHandle:
        """启动 LLaMA-Factory 训练.

        Mock 模式下不实际执行 CLI，仅返回 mock 句柄并生成示例日志。
        """
        cmd = self.build_command(task)
        task_id = task.taskId
        log_path = os.path.join(self.workDir, f"{task_id}.log")

        if self.mockMode:
            # Mock 模式：写入示例日志，返回 mock 句柄
            self._write_mock_log(log_path, task)
            return ProcessHandle(pid=0, isMock=True, extra={"cmd": cmd, "log": log_path})

        # 真实模式：subprocess 启动
        os.makedirs(self.workDir, exist_ok=True)
        log_fd = open(log_path, "w", encoding="utf-8")  # noqa: SIM115
        proc = subprocess.Popen(
            shlex.join(cmd),
            shell=True,
            stdout=log_fd,
            stderr=subprocess.STDOUT,
            cwd=self.workDir,
        )
        return ProcessHandle(
            pid=proc.pid, isMock=False, extra={"proc": proc, "log": log_path}
        )

    def stop(self, handle: ProcessHandle) -> bool:
        """终止训练进程."""
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
        """解析 LLaMA-Factory 训练日志行.

        LLaMA-Factory（基于 transformers Trainer）日志格式示例：
            {'loss': 1.234, 'learning_rate': 0.0001, 'epoch': 0.5}
        """
        text = line.strip()
        if not text:
            return None

        # 匹配 transformers Trainer 的字典格式日志
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
        """Mock 模式下写入示例训练日志，便于前端联调."""
        hp = task.request.config.hyperparams
        total_steps = hp.epochs * 100  # 简化估算
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        with open(log_path, "w", encoding="utf-8") as f:
            f.write(f"[mock] LLaMA-Factory 训练开始，任务 {task.taskId}\n")
            for step in range(0, total_steps + 1, hp.loggingSteps):
                # 模拟 loss 下降
                loss = 2.5 * (0.99 ** step)
                lr = hp.learningRate
                epoch = step / 100.0
                f.write(
                    f"{{'loss': {loss:.4f}, 'learning_rate': {lr}, "
                    f"'epoch': {epoch:.2f}}}\n"
                )
            f.write(f"[mock] 训练完成，输出目录 {task.request.outputDir}\n")