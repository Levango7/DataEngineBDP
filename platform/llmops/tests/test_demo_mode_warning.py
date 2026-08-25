"""演示模式启动告警测试."""

from __future__ import annotations

import logging

from llmops.api.app import create_app
from llmops.config.settings import Settings


def test_mock_store_logs_demo_mode_warning(caplog, registry):
    with caplog.at_level(logging.WARNING, logger="llmops.api.app"):
        create_app(settings=registry.settings, registry=registry)
    assert any("演示模式" in r.message for r in caplog.records)


def test_mlflow_store_no_demo_mode_warning(caplog, registry):
    settings = Settings(storeType="mlflow")
    with caplog.at_level(logging.WARNING, logger="llmops.api.app"):
        create_app(settings=settings, registry=registry)
    assert not any("演示模式" in r.message for r in caplog.records)
