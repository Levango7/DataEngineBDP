"""异步日志读取与 UTF-8 尾部边界回归测试.

覆盖缺陷修复：
- refresh_task_status / get_logs 的文件读取经 asyncio.to_thread 卸载，
  大日志 + 慢盘场景下事件循环保持响应
- _tail_file 仅读取尾部有限字节，且从字节中间起读时正确丢弃
  不完整的 UTF-8 前缀，中文不被破坏
"""

from __future__ import annotations

import asyncio
import os
import time

from app.adapters.base import ProcessHandle
from app.adapters.factory import get_adapter
from app.models.finetune_task import (
    FinetuneTask,
    FinetuneTaskRequest,
    TaskStatus,
)
import app.services.finetune_service as finetune_service_module
from app.services.finetune_service import FinetuneService


def _make_request(tmp_path) -> FinetuneTaskRequest:
    return FinetuneTaskRequest(
        taskName="log-io-test",
        baseModel="qwen-7b",
        dataset={"name": "ds", "path": str(tmp_path / "ds")},
        config={"method": "lora", "framework": "peft"},
        outputDir=str(tmp_path / "out"),
        tenantId="tenant-logio",
    )


def _register_running_task(service: FinetuneService, tmp_path, log_path: str, task_id: str):
    request = _make_request(tmp_path)
    task = FinetuneTask(taskId=task_id, request=request)
    task.mark_running(node="mock-node", gpus=[0])
    handle = ProcessHandle(pid=0, isMock=True, extra={"log": log_path})
    adapter = get_adapter(request.config.framework, workDir=service.workDir, mockMode=True)
    with service._lock:
        service._tasks[task.taskId] = task
        service._handles[task.taskId] = handle
        service._adapters[task.taskId] = adapter
    return task


def _install_to_thread_spy(monkeypatch) -> list[str]:
    calls: list[str] = []
    real_to_thread = asyncio.to_thread

    async def _to_thread(fn, *args, **kwargs):
        calls.append(getattr(fn, "__name__", repr(fn)))
        return await real_to_thread(fn, *args, **kwargs)

    monkeypatch.setattr(finetune_service_module.asyncio, "to_thread", _to_thread)
    return calls


def _write_big_log(path: str, filler_mb: int = 10) -> int:
    filler_line = "x" * 99 + "\n"
    filler = filler_line * (filler_mb * 1024 * 1024 // len(filler_line))
    chinese_block = "多字节中文日志边界测试字符串，" * 4000
    tail = chinese_block + "\n" + "step-99 loss=0.123 learningRate=2e-4\n" + "最终状态：训练完成，输出目录 /data/out\n"
    with open(path, "wb") as f:
        f.write(filler.encode("ascii"))
        f.write(tail.encode("utf-8"))
    return os.path.getsize(path)


class TestTailFileUtf8Boundary:
    def test_strip_incomplete_utf8_prefix_variants(self):
        strip = FinetuneService._strip_incomplete_utf8_prefix
        zhong = "中".encode("utf-8")
        hao = "好".encode("utf-8")

        assert strip(b"") == b""
        assert strip(b"ascii") == b"ascii"
        assert strip(zhong + b"ok") == zhong + b"ok"
        assert strip(zhong[:1]) == b""
        assert strip(zhong[:2] + b"ok") == b"ok"
        assert strip(zhong[:2] + zhong) == zhong
        assert strip(zhong[:2] + hao) == hao
        assert strip(zhong[1:] + b"x") == b"x"

    def test_tail_only_reads_bounded_bytes_with_intact_chinese(self, tmp_path, monkeypatch):
        window = 4096
        monkeypatch.setattr(FinetuneService, "_LOG_TAIL_MAX_BYTES", window)
        log_path = tmp_path / "boundary.log"
        content = "A" * 50000 + "训练日志多字节内容" * 2000 + "\n尾部标记：中文完好END\n"
        raw = content.encode("utf-8")
        log_path.write_bytes(raw)

        lines = FinetuneService._tail_file(str(log_path), 50)
        joined = "\n".join(lines)

        seg = raw[len(raw) - window :]
        expected_text = None
        for skip in range(4):
            try:
                expected_text = seg[skip:].decode("utf-8")
                break
            except UnicodeDecodeError:
                continue
        assert expected_text is not None
        assert lines == expected_text.splitlines()
        assert "\ufffd" not in joined
        assert joined.endswith("尾部标记：中文完好END")
        assert "训练日志多字节内容" in joined
        assert "AAAA" not in joined


class TestAsyncLogIO:
    def test_get_logs_offloads_slow_tail_read_to_thread(self, tmp_path, monkeypatch):
        service = FinetuneService(workDir=str(tmp_path / "work"), mockMode=True)
        log_path = str(tmp_path / "big.log")
        _write_big_log(log_path, filler_mb=10)
        task = _register_running_task(service, tmp_path, log_path, "ft-logiotest0001")

        calls = _install_to_thread_spy(monkeypatch)

        original_tail = FinetuneService._tail_file

        def _tail_file(path, n):
            time.sleep(0.3)
            return original_tail(path, n)

        monkeypatch.setattr(FinetuneService, "_tail_file", staticmethod(_tail_file))

        async def scenario():
            ticks = 0

            async def heartbeat():
                nonlocal ticks
                for _ in range(100):
                    await asyncio.sleep(0.005)
                    ticks += 1

            hb = asyncio.create_task(heartbeat())
            result = await service.get_logs(task.taskId, tail=5)
            await hb
            return result, ticks

        result, ticks = asyncio.run(scenario())

        assert "_tail_file" in calls
        assert ticks >= 70, f"事件循环被阻塞：心跳仅 {ticks} 次"
        messages = [e.message for e in result.entries]
        assert messages[-1] == "最终状态：训练完成，输出目录 /data/out"
        assert any("多字节中文日志边界测试字符串" in m for m in messages)
        assert all("\ufffd" not in m for m in messages)
        assert all(not m.startswith("x" * 20) for m in messages)

    def test_refresh_marks_succeeded_via_to_thread_on_big_log(self, tmp_path, monkeypatch):
        service = FinetuneService(workDir=str(tmp_path / "work"), mockMode=True)
        log_path = str(tmp_path / "big-done.log")
        _write_big_log(log_path, filler_mb=10)
        task = _register_running_task(service, tmp_path, log_path, "ft-logiotest0001")

        calls = _install_to_thread_spy(monkeypatch)

        updated = asyncio.run(service.refresh_task_status(task.taskId))

        assert "_read_log_tail_text" in calls
        assert updated.status == TaskStatus.SUCCEEDED
        assert updated.progress == 100.0
        assert updated.outputModelPath == os.path.join(task.request.outputDir, task.taskId)

    def test_refresh_without_marker_or_missing_log_keeps_running(self, tmp_path, monkeypatch):
        service = FinetuneService(workDir=str(tmp_path / "work"), mockMode=True)
        _install_to_thread_spy(monkeypatch)

        running_log = tmp_path / "running.log"
        running_log.write_text("step-1 loss=1.2\nstep-2 loss=1.0\n", encoding="utf-8")
        task = _register_running_task(service, tmp_path, str(running_log), "ft-logiorun00001")
        updated = asyncio.run(service.refresh_task_status(task.taskId))
        assert updated.status == TaskStatus.RUNNING

        missing_task = _register_running_task(
            service,
            tmp_path,
            str(tmp_path / "no-such-file.log"),
            "ft-logiomiss001",
        )
        updated_missing = asyncio.run(service.refresh_task_status(missing_task.taskId))
        assert updated_missing.status == TaskStatus.RUNNING

        empty = asyncio.run(service.get_logs(missing_task.taskId, tail=10))
        assert empty.total == 0
        assert empty.entries == []
