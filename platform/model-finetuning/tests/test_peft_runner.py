"""peft_runner 单元测试.

通过 FINETUNE_TINY=1 + 迷你模型验证真实训练链路
（不下载大模型、无需 GPU；CI 中确保 ModuleNotFoundError 问题不再复发）。
"""

import json
import os
import subprocess
import sys
import tempfile

from app.adapters import peft_runner


def _sample_config(tmpdir: str, base_model: str) -> str:
    cfg = {
        "task_id": "task-unit-1",
        "base_model": base_model,
        "dataset_path": "hf-internal-testing/tiny-random-gpt2",
        "output_dir": os.path.join(tmpdir, "out"),
        "hyperparams": {
            "epochs": 1,
            "batchSize": 1,
            "gradientAccumulationSteps": 1,
            "learningRate": 2e-4,
            "maxSeqLength": 64,
            "loggingSteps": 1,
            "saveSteps": 10,
            "fp16": False,
            "bf16": False,
        },
        "peft": {
            "method": "lora",
            "lora_config": {
                "r": 8,
                "lora_alpha": 16,
                "lora_dropout": 0.05,
                "target_modules": ["q_proj", "v_proj"],
            },
        },
    }
    path = os.path.join(tmpdir, "cfg.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False)
    return path


def test_runner_config_parsing():
    with tempfile.TemporaryDirectory() as tmp:
        cfg_path = _sample_config(tmp, "dummy")
        cfg = peft_runner.RunnerConfig.from_json(cfg_path)
        assert cfg.task_id == "task-unit-1"
        assert cfg.peft["method"] == "lora"
        assert cfg.hyperparams["epochs"] == 1


def test_missing_config_returns_error():
    rc = peft_runner.main_with_args([])
    assert rc == 1


def test_invalid_config_returns_error(tmp_path):
    bad = tmp_path / "bad.json"
    bad.write_text("{not-json", encoding="utf-8")
    rc = peft_runner.main_with_args([str(bad)])
    assert rc == 1


def test_tiny_training_runs_end_to_end():
    """迷你模型 + LoRA 完整跑通 1 epoch（不依赖 GPU/大模型）。"""
    deps = ("torch", "transformers", "peft", "datasets")
    try:
        import torch  # noqa: F401
        import transformers  # noqa: F401
        import peft  # noqa: F401
        import datasets  # noqa: F401
    except ImportError:
        # 环境缺训练依赖：跳过（CI 不强制装 torch）
        import pytest

        pytest.skip("训练依赖未安装（torch/transformers/peft/datasets），跳过 E2E")

    with tempfile.TemporaryDirectory() as tmp:
        cfg_path = _sample_config(tmp, "hf-internal-testing/tiny-random-gpt2")
        env = dict(os.environ, FINETUNE_TINY="1")
        proc = subprocess.run(
            [sys.executable, "-m", "app.adapters.peft_runner", cfg_path],
            capture_output=True,
            text=True,
            cwd=_project_root(),
            env=env,
            timeout=600,
        )
        assert proc.returncode == 0, proc.stdout + proc.stderr
        # adapter 权重应已保存
        adapter = os.path.join(tmp, "out", "adapter_model.safetensors")
        assert os.path.exists(adapter) or os.path.exists(
            os.path.join(tmp, "out", "adapter_model.bin")
        ), proc.stdout


def _project_root() -> str:
    """返回 platform/model-finetuning 目录（含 app 包）。"""
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
