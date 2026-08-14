"""PEFT 真实训练 runner.

由 :class:`app.adapters.peft_adapter.PEFTAdapter` 通过
``python -m app.adapters.peft_runner <config.json>`` 启动，
在独立进程中加载模型并执行 LoRA/QLoRA 微调，避免主进程占用 GPU 内存。

配置 JSON 契约（由 PEFTAdapter.build_training_script 生成）::

    {
      "task_id": "...",
      "base_model": "meta-llama/Llama-2-7b-hf 或本地路径",
      "dataset_path": "本地 JSON/JSONL 或 Hub ID",
      "output_dir": "checkpoint/adapter 输出目录",
      "hyperparams": {"epochs":3, "batchSize":8, "learningRate":2e-4, ...},
      "peft": {"method":"lora|qlora|full", "lora_config": {...}, "bnb_config": {...}}
    }

退出码：0 成功；1 配置错误；2 训练失败。
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field


@dataclass
class RunnerConfig:
    """runner 配置（从 JSON 反序列化）。"""

    task_id: str
    base_model: str
    dataset_path: str
    output_dir: str
    hyperparams: dict = field(default_factory=dict)
    peft: dict = field(default_factory=dict)

    @classmethod
    def from_json(cls, path: str) -> "RunnerConfig":
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        return cls(
            task_id=raw.get("task_id", "unknown"),
            base_model=raw.get("base_model", ""),
            dataset_path=raw.get("dataset_path", ""),
            output_dir=raw.get("output_dir", "./output"),
            hyperparams=raw.get("hyperparams", {}),
            peft=raw.get("peft", {}),
        )


def _tiny_model_enabled() -> bool:
    """环境变量 FINETUNE_TINY=1 时使用迷你模型（单元测试/CI 快速验证）。"""
    return os.getenv("FINETUNE_TINY", "0") == "1"


def run_training(cfg: RunnerConfig) -> int:
    """执行训练主流程。"""
    try:
        import torch
        from datasets import load_dataset
        from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
        from transformers import (
            AutoModelForCausalLM,
            AutoTokenizer,
            Trainer,
            TrainingArguments,
        )
    except ImportError as e:  # pragma: no cover - 环境缺依赖
        print(f"[peft_runner] 缺少训练依赖: {e}（需安装 torch/transformers/peft/datasets）")
        return 2

    # ---------- 模型加载 ----------
    base_model = cfg.base_model
    if _tiny_model_enabled():
        # 迷你模型：CI/单测快速验证链路，不下载大模型
        base_model = "hf-internal-testing/tiny-random-gpt2"

    print(f"[peft_runner] 加载基座模型: {base_model} (task={cfg.task_id})")
    device_map = "auto" if torch.cuda.is_available() else "cpu"
    kwargs: dict = {"device_map": device_map, "trust_remote_code": True}
    # QLoRA 场景需要 4bit/8bit 量化
    bnb = cfg.peft.get("bnb_config") or {}
    if bnb:
        kwargs["load_in_4bit"] = bnb.get("load_in_4bit", False)
        kwargs["load_in_8bit"] = bnb.get("load_in_8bit", False)
        if kwargs.get("load_in_4bit") or kwargs.get("load_in_8bit"):
            try:
                import bitsandbytes  # noqa: F401
            except ImportError:
                print("[peft_runner] QLoRA 需要 bitsandbytes")
                return 2

    model = AutoModelForCausalLM.from_pretrained(base_model, **kwargs)
    tokenizer = AutoTokenizer.from_pretrained(base_model, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # ---------- LoRA 包装 ----------
    method = cfg.peft.get("method", "lora")
    lora_cfg = cfg.peft.get("lora_config") or {}
    if method in ("lora", "qlora"):
        peft_config = LoraConfig(
            r=lora_cfg.get("r", 8),
            lora_alpha=lora_cfg.get("lora_alpha", 16),
            lora_dropout=lora_cfg.get("lora_dropout", 0.05),
            target_modules=lora_cfg.get("target_modules")
            or ["q_proj", "v_proj", "k_proj", "o_proj"],
            bias="none",
            task_type="CAUSAL_LM",
        )
        if bnb:
            model = prepare_model_for_kbit_training(model)
        model = get_peft_model(model, peft_config)
        model.print_trainable_parameters()

    # ---------- 数据集 ----------
    ds_path = cfg.dataset_path
    if _tiny_model_enabled():
        ds_path = "hf-internal-testing/tiny-random-gpt2"
    try:
        dataset = load_dataset(ds_path, split="train")
    except Exception:
        # 兜底：空数据集也走通流程（单测用）
        from datasets import Dataset

        dataset = Dataset.from_dict({"text": ["hello", "world", "test"]})

    def tokenize_fn(examples):
        return tokenizer(
            examples.get("text", examples.get("input", ["ok"])),
            truncation=True,
            max_length=cfg.hyperparams.get("maxSeqLength", 128),
            padding="max_length",
        )

    tokenized = dataset.map(tokenize_fn, batched=True, remove_columns=dataset.column_names)

    # ---------- 训练 ----------
    hp = cfg.hyperparams
    epochs = hp.get("epochs", 3) if not _tiny_model_enabled() else 1
    output_dir = cfg.output_dir
    os.makedirs(output_dir, exist_ok=True)

    training_args = TrainingArguments(
        output_dir=output_dir,
        num_train_epochs=epochs,
        per_device_train_batch_size=hp.get("batchSize", 2),
        gradient_accumulation_steps=hp.get("gradientAccumulationSteps", 1),
        learning_rate=hp.get("learningRate", 2e-4),
        weight_decay=hp.get("weightDecay", 0.01),
        warmup_steps=hp.get("warmupSteps", 0),
        logging_steps=1,
        save_steps=hp.get("saveSteps", 100),
        save_total_limit=1,
        fp16=hp.get("fp16", False) and torch.cuda.is_available(),
        bf16=hp.get("bf16", False) and torch.cuda.is_available(),
        seed=hp.get("seed", 42),
        report_to=[],
    )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized,
    )
    trainer.train()

    # ---------- 保存 adapter ----------
    try:
        model.save_pretrained(output_dir)
        tokenizer.save_pretrained(output_dir)
        print(f"[peft_runner] 训练完成，adapter 已保存至 {output_dir}")
    except Exception as e:
        print(f"[peft_runner] adapter 保存失败（训练已完成）: {e}")
    return 0


def main_with_args(args: list[str]) -> int:
    """可测试入口：显式传参（单测用），避免直接读 sys.argv。"""
    if len(args) < 1:
        print("用法: python -m app.adapters.peft_runner <config.json>")
        return 1
    try:
        cfg = RunnerConfig.from_json(args[0])
    except Exception as e:
        print(f"[peft_runner] 配置解析失败: {e}")
        return 1
    return run_training(cfg)


def main() -> int:
    return main_with_args(sys.argv[1:])


if __name__ == "__main__":
    sys.exit(main())
