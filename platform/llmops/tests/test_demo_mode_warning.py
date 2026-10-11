"""演示模式启动告警测试.

注意（2026-10-11）：`Settings(storeType="mlflow")` 这类**构造参数**不保证生效——pydantic-settings
中「初始化参数 < 环境变量」优先级，而 conftest 会 `os.environ.setdefault("LLMOPS_STORE_TYPE", "mock")`。
必须用 monkeypatch 改写环境变量后再构造 Settings，测试才算真正走到 mlflow 分支
（此前的 `Settings(storeType="mlflow")` 在环境变量被静默忽略的年代"碰巧"通过）。
"""

from __future__ import annotations

import logging

from llmops.api.app import create_app
from llmops.config.settings import Settings


def test_mock_store_logs_demo_mode_warning(caplog, registry):
    with caplog.at_level(logging.WARNING, logger="llmops.api.app"):
        create_app(settings=registry.settings, registry=registry)
    assert any("演示模式" in r.message for r in caplog.records)


def test_mlflow_store_no_demo_mode_warning(caplog, registry, monkeypatch):
    monkeypatch.setenv("LLMOPS_STORE_TYPE", "mlflow")
    settings = Settings()
    assert settings.storeType == "mlflow", "前置条件：环境变量应把 storeType 置为 mlflow"
    registry.settings = settings

    with caplog.at_level(logging.WARNING, logger="llmops.api.app"):
        create_app(settings=settings, registry=registry)
    assert not any("演示模式" in r.message for r in caplog.records)
