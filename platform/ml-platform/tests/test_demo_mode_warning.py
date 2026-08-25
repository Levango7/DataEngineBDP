"""演示模式启动告警测试."""

from __future__ import annotations

import logging

from ml_platform.api.app import createApp
from ml_platform.config.settings import Settings


def test_mock_backend_logs_demo_mode_warning(caplog, registry):
    with caplog.at_level(logging.WARNING, logger="ml_platform.api.app"):
        createApp(settings=registry.settings, registry=registry)
    assert any("演示模式" in r.message for r in caplog.records)


def test_sklearn_backend_no_demo_mode_warning(caplog, registry):
    settings = Settings(backendType="sklearn")
    with caplog.at_level(logging.WARNING, logger="ml_platform.api.app"):
        createApp(settings=settings, registry=registry)
    assert not any("演示模式" in r.message for r in caplog.records)
