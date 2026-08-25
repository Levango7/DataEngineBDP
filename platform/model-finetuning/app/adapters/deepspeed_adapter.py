"""DeepSpeed 适配器.

通过 DeepSpeed 实现多卡数据并行（ZeRO-2/3）与张量并行（TP），
适用于全参微调或大模型 LoRA 微调。

DeepSpeed 是微软开源的大模型训练优化库：
- ZeRO-2：切分优化器状态 + 梯度，适合中等规模模型
- ZeRO-3：额外切分模型参数，支持最大规模模型
- TP：张量并行，跨卡切分矩阵运算

本适配器负责：
1. 选择/生成 DeepSpeed JSON 配置（zero2 / zero3）
2. 通过 deepspeed 启动分布式训练
3. Mock 模式下不实际启动多卡，仅生成配置并模拟日志
"""
from __future__ import annotations

import atexit
import json
import os
import re
import subprocess
from typing import Any, IO, Optional

from app.adapters.base import BaseAdapter, ProcessHandle
from app.models.finetune_config import (
    DeepSpeedStage,
    FinetuneConfig,
    FinetuneMethod,
)
from app.models.finetune_task import FinetuneTask, LogEntry


def _close_log_fd(log_fd: IO[str]) -> None:
    """安全关闭日志文件句柄（幂等，已关闭则跳过）.

    作为 atexit 兜底回调，防止调用方遗漏 ``stop()`` 导致句柄泄漏；
    也可在 ``stop()`` 中显式调用。
    """
    try:
        if not log_fd.closed:
            log_fd.close()
    except Exception:
        # 关闭过程中的异常不应影响进程退出或 stop 流程
        pass


def _close_handle_log_fd(handle: ProcessHandle) -> None:
    """从 ProcessHandle 中取出并关闭日志句柄（若存在）."""
    if handle.extra:
        log_fd = handle.extra.get("log_fd")
        if log_fd is not None:
            _close_log_fd(log_fd)


class DeepSpeedAdapter(BaseAdapter):
    """DeepSpeed 多卡并行适配器."""

    name = "deepspeed"

    #: DeepSpeed 配置文件目录（相对工作目录）
    CONFIG_DIR = "config"

    def validate_config(self, config: FinetuneConfig) -> list[str]:
        """校验配置对 DeepSpeed 的合法性."""
        errors: list[str] = []
        ds = config.deepspeed
        if ds is None:
            return errors

        # ZeRO-3 才支持参数卸载
        if ds.offloadParam and ds.stage != DeepSpeedStage.ZERO_3:
            errors.append("参数卸载（offloadParam）仅在 ZeRO-3 下可用")

        # TP 与 DP 乘积应与 GPU 总数一致（此处仅做合理性提示）
        total_parallel = ds.tensorParallelSize * ds.dataParallelSize
        if total_parallel > 32:
            errors.append(
                f"并行度过高（TP×DP={total_parallel}），请确认 GPU 资源充足"
            )

        # 全参微调 + ZeRO-2 是常见组合，无需告警
        return errors

    def resolve_config_path(self, stage: DeepSpeedStage) -> str:
        """根据 ZeRO 阶段选择 DeepSpeed JSON 配置文件路径.

        优先使用项目内置配置（config/deepspeed_zero2.json / zero3.json），
        若不存在则动态生成到工作目录。
        """
        builtin_name = (
            "deepspeed_zero2.json" if stage == DeepSpeedStage.ZERO_2
            else "deepspeed_zero3.json"
        )
        # 尝试项目内置配置（相对于项目根目录）
        project_root = os.path.abspath(
            os.path.join(os.path.dirname(__file__), "..", "..")
        )
        builtin_path = os.path.join(project_root, "config", builtin_name)
        if os.path.exists(builtin_path):
            return builtin_path

        # 动态生成
        os.makedirs(os.path.join(self.workDir, self.CONFIG_DIR), exist_ok=True)
        gen_path = os.path.join(self.workDir, self.CONFIG_DIR, builtin_name)
        template = self._default_config_template(stage)
        with open(gen_path, "w", encoding="utf-8") as f:
            json.dump(template, f, indent=2)
        return gen_path

    def build_deepspeed_config(self, task: FinetuneTask) -> dict[str, Any]:
        """根据任务配置生成 DeepSpeed JSON 配置字典."""
        cfg = task.request.config
        ds = cfg.deepspeed
        if ds is None:
            from app.models.finetune_config import DeepSpeedConfig

            ds = DeepSpeedConfig()

        template = self._default_config_template(ds.stage)
        # 注入卸载配置
        if ds.offloadOptimizer:
            template["zero_optimization"]["offload_optimizer"] = {
                "device": "cpu",
                "pin_memory": True,
            }
        if ds.offloadParam and ds.stage == DeepSpeedStage.ZERO_3:
            template["zero_optimization"]["offload_param"] = {
                "device": "cpu",
                "pin_memory": True,
            }
        # 张量并行
        if ds.tensorParallelSize > 1:
            template["zero_optimization"]["tensor_parallel"] = {
                "tp_size": ds.tensorParallelSize
            }
        return template

    def build_command(self, task: FinetuneTask) -> list[str]:
        """构建 deepspeed 启动命令.

        命令格式：
            deepspeed --num_gpus=<N> train_script.py --deepspeed --deepspeed_config <cfg.json>
        """
        cfg = task.request.config
        ds = cfg.deepspeed
        from app.models.finetune_config import DeepSpeedConfig

        ds = ds or DeepSpeedConfig()
        total_gpus = ds.tensorParallelSize * ds.dataParallelSize

        # 写入 DeepSpeed 配置 JSON
        ds_cfg = self.build_deepspeed_config(task)
        ds_cfg_path = os.path.join(self.workDir, f"{task.taskId}_deepspeed.json")
        os.makedirs(self.workDir, exist_ok=True)
        with open(ds_cfg_path, "w", encoding="utf-8") as f:
            json.dump(ds_cfg, f, indent=2)

        # 训练脚本（复用 PEFT runner 或独立脚本）
        train_script = "app.adapters.deepspeed_runner"
        return [
            "deepspeed",
            f"--num_gpus={total_gpus}",
            "--module",
            train_script,
            "--deepspeed",
            f"--deepspeed_config={ds_cfg_path}",
            f"--task_id={task.taskId}",
        ]

    def start(self, task: FinetuneTask) -> ProcessHandle:
        """启动 DeepSpeed 训练."""
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
        except Exception:
            # Popen 失败时立即关闭日志句柄，避免泄漏
            log_fd.close()
            raise
        # 注意：此处不关闭 log_fd。
        # Windows 上 Python 3.4+ 默认 close_fds=True，若父进程在 Popen 后
        # 立即关闭 stdout 句柄，子进程通过 STARTUPINFO 继承的 handle 可能
        # 写入失败。故将 log_fd 生命周期绑定到 ProcessHandle，由 stop() 关闭；
        # 同时注册 atexit 兜底，防止调用方遗漏 stop() 导致句柄泄漏。
        atexit.register(_close_log_fd, log_fd)
        return ProcessHandle(
            pid=proc.pid,
            isMock=False,
            extra={"proc": proc, "log": log_path, "log_fd": log_fd},
        )

    def stop(self, handle: ProcessHandle) -> bool:
        """终止 DeepSpeed 训练进程."""
        if handle.isMock:
            return True
        proc = handle.extra.get("proc") if handle.extra else None
        if proc is None:
            return False
        try:
            proc.terminate()
            proc.wait(timeout=15)
            return True
        except subprocess.SubprocessError:
            proc.kill()
            return proc.poll() is not None
        finally:
            # 终止进程后关闭日志句柄，确保子进程不再写入后再释放 fd
            _close_handle_log_fd(handle)

    def parse_log_line(self, line: str, step: int = 0) -> Optional[LogEntry]:
        """解析 DeepSpeed 训练日志行.

        DeepSpeed 日志包含：
        - transformers Trainer 的字典格式 loss 日志
        - DeepSpeed 自身的 ZeRO 分配信息
        - GPU 利用率信息（如 [GPU 0] util=85.2% mem=32.1GB）
        """
        text = line.strip()
        if not text:
            return None

        # loss 日志
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

        # GPU 利用率日志
        gpu_match = re.search(r"\[GPU\s*(\d+)\]\s*util=([\d.]+)%\s*mem=([\d.]+)GB", text)
        if gpu_match:
            return LogEntry(
                step=step,
                gpuUtil=[float(gpu_match.group(2))],
                gpuMemory=[float(gpu_match.group(3))],
                message=text,
            )
        return LogEntry(step=step, message=text)

    # ------------------------------------------------------------
    # 内部辅助
    # ------------------------------------------------------------
    def _default_config_template(self, stage: DeepSpeedStage) -> dict[str, Any]:
        """返回 ZeRO-2 / ZeRO-3 默认配置模板."""
        if stage == DeepSpeedStage.ZERO_2:
            return {
                "bf16": {"enabled": False},
                "fp16": {
                    "enabled": True,
                    "loss_scale": 0,
                    "loss_scale_window": 1000,
                    "initial_scale_power": 16,
                    "hysteresis": 2,
                    "min_loss_scale": 1,
                },
                "zero_optimization": {
                    "stage": 2,
                    "allgather_partitions": True,
                    "allgather_bucket_size": 5e8,
                    "overlap_comm": True,
                    "reduce_scatter": True,
                    "reduce_bucket_size": 5e8,
                    "contiguous_gradients": True,
                },
                "gradient_accumulation_steps": "auto",
                "gradient_clipping": "auto",
                "train_batch_size": "auto",
                "train_micro_batch_size_per_gpu": "auto",
                "steps_per_print": 2000,
                "wall_clock_breakdown": False,
            }
        return {
            "bf16": {"enabled": False},
            "fp16": {
                "enabled": True,
                "loss_scale": 0,
                "loss_scale_window": 1000,
                "initial_scale_power": 16,
                "hysteresis": 2,
                "min_loss_scale": 1,
            },
            "zero_optimization": {
                "stage": 3,
                "overlap_comm": True,
                "contiguous_gradients": True,
                "subgroup_size": 1e9,
                "reduce_bucket_size": "auto",
                "stage3_prefetch_bucket_size": "auto",
                "stage3_param_persistence_threshold": "auto",
                "stage3_max_live_parameters": 1e9,
                "stage3_max_reuse_distance": 1e9,
                "stage3_gather_16bit_weights_on_model_save": True,
            },
            "gradient_accumulation_steps": "auto",
            "gradient_clipping": "auto",
            "train_batch_size": "auto",
            "train_micro_batch_size_per_gpu": "auto",
            "steps_per_print": 2000,
            "wall_clock_breakdown": False,
        }

    def _write_mock_log(self, log_path: str, task: FinetuneTask) -> None:
        """Mock 模式下写入示例训练日志（含 GPU 利用率）."""
        hp = task.request.config.hyperparams
        ds_cfg = task.request.config.deepspeed
        from app.models.finetune_config import DeepSpeedConfig

        ds_cfg = ds_cfg or DeepSpeedConfig()
        total_gpus = ds_cfg.tensorParallelSize * ds_cfg.dataParallelSize
        total_steps = hp.epochs * 100
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        with open(log_path, "w", encoding="utf-8") as f:
            f.write(
                f"[mock] DeepSpeed 训练开始，任务 {task.taskId}，"
                f"ZeRO-{2 if ds_cfg.stage == DeepSpeedStage.ZERO_2 else 3}，"
                f"GPU 数 {total_gpus}\n"
            )
            for step in range(0, total_steps + 1, hp.loggingSteps):
                loss = 2.0 * (0.98 ** step)
                lr = hp.learningRate
                epoch = step / 100.0
                f.write(
                    f"{{'loss': {loss:.4f}, 'learning_rate': {lr}, "
                    f"'epoch': {epoch:.2f}}}\n"
                )
                # 模拟各卡 GPU 利用率
                for gpu_id in range(total_gpus):
                    util = 80.0 + (step % 20) - gpu_id * 2
                    mem = 30.0 + (step % 10) * 0.5
                    f.write(
                        f"[GPU {gpu_id}] util={util:.1f}% mem={mem:.1f}GB\n"
                    )
            f.write(f"[mock] 训练完成，输出目录 {task.request.outputDir}\n")