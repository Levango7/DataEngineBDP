"""演示模式启动告警测试（main.py 进程内；loop 因同名 app 包冲突走子进程隔离）."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

if "FINETUNE_WORK_DIR" not in os.environ:
    os.environ["FINETUNE_WORK_DIR"] = tempfile.mkdtemp(prefix="finetune-demo-")

_ROOT = str(Path(__file__).resolve().parents[1])
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from loguru import logger
import main as finetune_main

_LOOP_ROOT = Path(__file__).resolve().parents[1] / "loop"

_DEMO_SCRIPT = r"""
import json
import logging
import sys

sys.path.insert(0, sys.argv[1])

records = []


class _Collect(logging.Handler):
    def emit(self, record):
        records.append(record.getMessage())


logging.getLogger("app.main").addHandler(_Collect(level=logging.WARNING))

from app.main import create_app

records.clear()
create_app()
mock_on = any("演示模式" in m for m in records)

import app.config as config_module

config_module._settings = config_module.Settings(mock_mode=False)
records.clear()
create_app()
mock_off = any("演示模式" in m for m in records)

print(json.dumps({"mockOn": mock_on, "mockOff": mock_off}))
"""


class TestFinetuneDemoModeWarning:
    def test_mock_mode_emits_warning(self, monkeypatch):
        monkeypatch.setenv("FINETUNE_MOCK_MODE", "true")
        records: list[str] = []
        sink_id = logger.add(records.append, level="WARNING")
        try:
            finetune_main.create_app()
        finally:
            logger.remove(sink_id)
        assert any("演示模式" in m for m in records)

    def test_real_mode_no_warning(self, monkeypatch):
        monkeypatch.setenv("FINETUNE_MOCK_MODE", "false")
        monkeypatch.setenv("FINETUNE_WORK_DIR", tempfile.mkdtemp(prefix="finetune-demo-real-"))
        records: list[str] = []
        sink_id = logger.add(records.append, level="WARNING")
        try:
            finetune_main.create_app()
        finally:
            logger.remove(sink_id)
        assert not any("演示模式" in m for m in records)


class TestLoopDemoModeWarning:
    def test_warning_only_in_mock_mode(self):
        # 演示模式告警测试关注 mock 开关日志：显式开发模式启动（生产默认强制 JWT）
        proc = subprocess.run(
            [sys.executable, "-c", _DEMO_SCRIPT, str(_LOOP_ROOT)],
            capture_output=True,
            text=True,
            timeout=180,
            cwd=str(_LOOP_ROOT),
            env={**os.environ, "LOOP_DEV_MODE": "true", "AUTH_MODE": "none"},
        )
        assert proc.returncode == 0, proc.stderr
        payload = json.loads(proc.stdout.strip().splitlines()[-1])
        assert payload["mockOn"] is True
        assert payload["mockOff"] is False
