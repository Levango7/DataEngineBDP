"""演示模式启动告警测试.

注意（2026-10-12）：`Settings(backendType="sklearn")` 这类构造参数不保证生效——
pydantic-settings 中「初始化参数 < 环境变量」优先级，而 conftest 会
`os.environ.setdefault("ML_BACKEND_TYPE", "mock")`。必须用 monkeypatch 改写环境变量后
再构造 Settings，测试才算真正走到 sklearn 分支（同 llmops test_demo_mode_warning 的修法）。
"""

from __future__ import annotations

import logging

from ml_platform.api.app import createApp
from ml_platform.config.settings import Settings


def test_mock_backend_logs_demo_mode_warning(caplog, registry):
    with caplog.at_level(logging.WARNING, logger="ml_platform.api.app"):
        createApp(settings=registry.settings, registry=registry)
    assert any("演示模式" in r.message for r in caplog.records)


def test_sklearn_backend_no_demo_mode_warning(caplog, registry, monkeypatch):
    monkeypatch.setenv("ML_BACKEND_TYPE", "sklearn")
    settings = Settings()
    assert settings.backendType == "sklearn", "前置条件：环境变量应把 backendType 置为 sklearn"
    registry.settings = settings

    with caplog.at_level(logging.WARNING, logger="ml_platform.api.app"):
        createApp(settings=settings, registry=registry)
    assert not any("演示模式" in r.message for r in caplog.records)
