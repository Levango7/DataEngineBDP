"""Adapter 版本分配回归测试.

覆盖闭环编排的 allocate/register key 一致性：
- 非 lora 微调方式连续两次 allocate_version 应得到递增版本号而非重复
- lora 场景回归不变

loop/app 与仓库根目录存在同名 app 包，为避免导入冲突，
此处以独立模块名从文件路径直接加载被测模块。
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

_VERSIONING_DIR = (
    Path(__file__).resolve().parents[1] / "loop" / "app" / "versioning"
)
_LOOP_ROOT = Path(__file__).resolve().parents[1] / "loop"


def _load_module(module_name: str, filename: str):
    spec = importlib.util.spec_from_file_location(
        module_name, _VERSIONING_DIR / filename
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


_adapter_registry = _load_module(
    "adapter_registry_under_test", "adapter_registry.py"
)
AdapterRegistry = _adapter_registry.AdapterRegistry


class TestAllocateVersionKeyConsistency:
    """allocate 与 register 的模型 key 必须一致."""

    def test_non_lora_method_versions_increment(self, tmp_path):
        """非 lora method 连续两轮 分配→注册→分配 应递增而非重复."""
        registry = AdapterRegistry(storage_dir=str(tmp_path / "reg"))
        common = {
            "base_model": "qwen-7b",
            "method": "qlora",
            "framework": "peft",
            "tenant_id": "tenant-a",
        }

        v1 = registry.allocate_version(**common)
        assert v1 == "0.1.0"

        record1 = registry.register(
            version=v1,
            adapter_path=str(tmp_path / "adapter-v1"),
            tenant_id=common["tenant_id"],
            base_model=common["base_model"],
            method=common["method"],
            framework=common["framework"],
            loop_task_id="loop-1",
        )
        assert record1.method == "qlora"
        assert record1.framework == "peft"

        v2 = registry.allocate_version(**common)
        assert v2 == "0.1.1"
        assert v2 != v1

        registry.register(
            version=v2,
            adapter_path=str(tmp_path / "adapter-v2"),
            tenant_id=common["tenant_id"],
            base_model=common["base_model"],
            method=common["method"],
            framework=common["framework"],
            loop_task_id="loop-2",
        )

        v3 = registry.allocate_version(**common)
        assert v3 == "0.1.2"

    def test_non_default_framework_key_consistency(self, tmp_path):
        """非默认 framework 落库 key 与查询 key 一致."""
        registry = AdapterRegistry(storage_dir=str(tmp_path / "reg"))
        common = {
            "base_model": "llama-3-8b",
            "method": "full",
            "framework": "deepspeed",
            "tenant_id": "tenant-b",
        }

        v1 = registry.allocate_version(**common)
        registry.register(
            version=v1,
            adapter_path=str(tmp_path / "a1"),
            tenant_id=common["tenant_id"],
            base_model=common["base_model"],
            method=common["method"],
            framework=common["framework"],
        )

        v2 = registry.allocate_version(**common)
        assert v2 == "0.1.1"

        versions = registry.list_versions(
            base_model=common["base_model"],
            method=common["method"],
            framework=common["framework"],
            tenant_id=common["tenant_id"],
        )
        assert [v["version"] for v in versions] == ["0.1.0"]

    def test_lora_regression_unchanged(self, tmp_path):
        """lora/peft 场景行为与修复前保持一致."""
        registry = AdapterRegistry(storage_dir=str(tmp_path / "reg"))
        common = {
            "base_model": "qwen-7b",
            "method": "lora",
            "framework": "peft",
            "tenant_id": "tenant-c",
        }

        v1 = registry.allocate_version(**common)
        assert v1 == "0.1.0"
        registry.register(
            version=v1,
            adapter_path=str(tmp_path / "a1"),
            tenant_id=common["tenant_id"],
            base_model=common["base_model"],
            method=common["method"],
            framework=common["framework"],
        )

        v2 = registry.allocate_version(**common)
        assert v2 == "0.1.1"
        registry.register(
            version=v2,
            adapter_path=str(tmp_path / "a2"),
            tenant_id=common["tenant_id"],
            base_model=common["base_model"],
            method=common["method"],
            framework=common["framework"],
        )

        active = registry.get_active_version(
            base_model="qwen-7b",
            method="lora",
            framework="peft",
            tenant_id="tenant-c",
        )
        assert active is not None
        assert active["version"] == v2

    def test_different_methods_do_not_share_key(self, tmp_path):
        """不同 method 的版本序列互不干扰."""
        registry = AdapterRegistry(storage_dir=str(tmp_path / "reg"))

        lora_v1 = registry.allocate_version(
            base_model="qwen-7b", method="lora",
            framework="peft", tenant_id="tenant-d",
        )
        qlora_v1 = registry.allocate_version(
            base_model="qwen-7b", method="qlora",
            framework="peft", tenant_id="tenant-d",
        )
        assert lora_v1 == "0.1.0"
        assert qlora_v1 == "0.1.0"

        registry.register(
            version=lora_v1,
            adapter_path=str(tmp_path / "lora-a1"),
            tenant_id="tenant-d",
            base_model="qwen-7b",
            method="lora",
            framework="peft",
        )

        assert registry.allocate_version(
            base_model="qwen-7b", method="qlora",
            framework="peft", tenant_id="tenant-d",
        ) == "0.1.0"
        assert registry.allocate_version(
            base_model="qwen-7b", method="lora",
            framework="peft", tenant_id="tenant-d",
        ) == "0.1.1"


_ORCHESTRATOR_SCRIPT = r'''
import json
import sys
import tempfile

sys.path.insert(0, sys.argv[1])

from app.core.orchestrator import LoopOrchestrator
from app.core.step_executor import StepExecutor
from app.core.websocket_manager import WebSocketManager
from app.models import LoopTaskRequest
from app.versioning.adapter_registry import AdapterRegistry
from app.versioning.report_registry import ReportRegistry

tmp = tempfile.mkdtemp()
orchestrator = LoopOrchestrator(
    executor=StepExecutor(mock_mode=True),
    ws_manager=WebSocketManager(),
    adapter_registry=AdapterRegistry(storage_dir=tempfile.mkdtemp()),
    report_registry=ReportRegistry(storage_dir=tempfile.mkdtemp()),
)


def make_request(name: str) -> LoopTaskRequest:
    return LoopTaskRequest(
        taskName=name,
        baseModel="qwen-7b",
        trainDataset={"name": "ds", "path": tmp + "/ds"},
        finetune={"method": "qlora", "framework": "peft"},
        tenantId="tenant-x",
        skipDeploy=True,
    )


task1 = orchestrator.submit_task(make_request("run-1"))
done1 = orchestrator.get_task(task1.taskId)

task2 = orchestrator.submit_task(make_request("run-2"))
done2 = orchestrator.get_task(task2.taskId)

print(json.dumps({
    "v1": done1.adapterVersion,
    "s1": done1.status.value,
    "v2": done2.adapterVersion,
    "s2": done2.status.value,
}))
'''


class TestOrchestratorVersionFlow:
    """编排器 submit→register 链路的版本分配回归."""

    def test_two_qlora_loops_get_incremental_versions(self):
        """同一 qlora 配置连续两个闭环应得到 0.1.0 → 0.1.1 而非重复."""
        proc = subprocess.run(
            [sys.executable, "-c", _ORCHESTRATOR_SCRIPT, str(_LOOP_ROOT)],
            capture_output=True,
            text=True,
            timeout=120,
        )
        assert proc.returncode == 0, proc.stderr
        payload = json.loads(proc.stdout.strip().splitlines()[-1])
        assert payload["s1"] == "completed"
        assert payload["s2"] == "completed"
        assert payload["v1"] == "0.1.0"
        assert payload["v2"] == "0.1.1"
        assert payload["v2"] != payload["v1"]
