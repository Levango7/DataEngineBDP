"""loop 运行时缺陷回归测试（子进程隔离加载，避免同名 app 包冲突）.

覆盖缺陷修复：
- orchestrator._trigger_sync_execution 在运行中事件循环内
  不再自建 loop（不再抛 RuntimeError / 任务误标 failed），
  而是以 fire-and-forget 方式调度 _run_loop 后台执行
- step_executor._http_post 仅对 ConnectError 重试且最多 2 次尝试，
  ReadTimeout 等一律不重试直接失败
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

_LOOP_ROOT = Path(__file__).resolve().parents[1] / "loop"

_ORCHESTRATOR_SCRIPT = r'''
import asyncio
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

orchestrator = LoopOrchestrator(
    executor=StepExecutor(mock_mode=True),
    ws_manager=WebSocketManager(),
    adapter_registry=AdapterRegistry(storage_dir=tempfile.mkdtemp()),
    report_registry=ReportRegistry(storage_dir=tempfile.mkdtemp()),
)

request = LoopTaskRequest(
    taskName="lazy-loop",
    baseModel="qwen-7b",
    trainDataset={"name": "ds", "path": tempfile.mkdtemp()},
    finetune={"method": "qlora", "framework": "peft"},
    tenantId="tenant-lazy",
    skipDeploy=True,
)

task = orchestrator.submit_task(request)


async def main():
    seen = orchestrator.get_task(task.taskId)
    assert seen.status.value != "failed", seen.errorMessage
    for _ in range(1200):
        seen = orchestrator.get_task(task.taskId)
        if seen.is_terminal():
            break
        await asyncio.sleep(0.05)
    print(json.dumps({
        "status": seen.status.value,
        "error": seen.errorMessage,
        "adapterVersion": seen.adapterVersion,
        "handleTracked": task.taskId in orchestrator._async_handles,
    }))


asyncio.run(main())
'''

_HTTP_SCRIPT = r'''
import asyncio
import json
import sys

sys.path.insert(0, sys.argv[1])

import httpx

from app.core.step_executor import StepExecutor


def executor_with(handler):
    ex = StepExecutor(mock_mode=False, timeout=5)
    ex._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return ex


async def main():
    results = {}

    connect_counts = {"n": 0}

    def connect_fail(request):
        connect_counts["n"] += 1
        raise httpx.ConnectError("connection refused", request=request)

    ex = executor_with(connect_fail)
    connect_err = None
    try:
        await ex._http_post("http://upstream/api/v1/finetune/tasks", {"a": 1})
    except Exception as e:
        connect_err = str(e)
    results["connectAttempts"] = connect_counts["n"]
    results["connectErr"] = connect_err
    await ex._client.aclose()

    timeout_counts = {"n": 0}

    def read_timeout(request):
        timeout_counts["n"] += 1
        raise httpx.ReadTimeout("read timed out", request=request)

    ex2 = executor_with(read_timeout)
    timeout_err = None
    try:
        await ex2._http_post("http://upstream/api/v1/finetune/tasks", {"a": 1})
    except Exception as e:
        timeout_err = str(e)
    results["timeoutAttempts"] = timeout_counts["n"]
    results["timeoutErr"] = timeout_err
    await ex2._client.aclose()

    flaky_counts = {"n": 0}

    def flaky(request):
        flaky_counts["n"] += 1
        if flaky_counts["n"] == 1:
            raise httpx.ConnectError("first refused", request=request)
        return httpx.Response(201, json={"taskId": "ft-1"})

    ex3 = executor_with(flaky)
    resp = await ex3._http_post("http://upstream/api/v1/finetune/tasks", {})
    results["flakyAttempts"] = flaky_counts["n"]
    results["flakyTaskId"] = resp.get("taskId")
    await ex3._client.aclose()

    status_counts = {"n": 0}

    def server_error(request):
        status_counts["n"] += 1
        return httpx.Response(500, json={"detail": "boom"})

    ex4 = executor_with(server_error)
    status_err = None
    try:
        await ex4._http_post("http://upstream/api/v1/registry/deployments", {})
    except Exception as e:
        status_err = str(e)
    results["serverErrorAttempts"] = status_counts["n"]
    results["serverErrorErr"] = status_err
    await ex4._client.aclose()

    print(json.dumps(results))


asyncio.run(main())
'''


def _run_script(script: str) -> dict:
    proc = subprocess.run(
        [sys.executable, "-c", script, str(_LOOP_ROOT)],
        capture_output=True,
        text=True,
        timeout=180,
    )
    assert proc.returncode == 0, proc.stderr
    return json.loads(proc.stdout.strip().splitlines()[-1])


class TestLazyExecutionInsideRunningLoop:
    def test_pending_task_executes_without_runtime_error(self):
        """无句柄的 pending 任务在运行中 loop 内查询时应后台执行至完成."""
        payload = _run_script(_ORCHESTRATOR_SCRIPT)
        assert payload["status"] == "completed"
        assert not payload["error"]
        assert payload["adapterVersion"] == "0.1.0"
        assert payload["handleTracked"] is True


class TestHttpPostRetryPolicy:
    def test_connect_error_retried_at_most_twice(self):
        payload = _run_script(_HTTP_SCRIPT)
        assert payload["connectAttempts"] == 2
        assert "HTTP POST 失败" in payload["connectErr"]

    def test_read_timeout_never_retried(self):
        payload = _run_script(_HTTP_SCRIPT)
        assert payload["timeoutAttempts"] == 1
        assert "HTTP POST 失败" in payload["timeoutErr"]

    def test_connect_retry_can_succeed_on_second_attempt(self):
        payload = _run_script(_HTTP_SCRIPT)
        assert payload["flakyAttempts"] == 2
        assert payload["flakyTaskId"] == "ft-1"

    def test_http_status_error_never_retried(self):
        payload = _run_script(_HTTP_SCRIPT)
        assert payload["serverErrorAttempts"] == 1
        assert "HTTP POST 失败" in payload["serverErrorErr"]
