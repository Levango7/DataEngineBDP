"""pytest 共享 fixtures."""
from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient

# 强制 Mock 模式
os.environ.setdefault("INDUSTRY_TEMPLATES_DEPLOY_MODE", "mock")

from industry_templates.api.app import create_app
from industry_templates.config.settings import Settings, reset_settings
from industry_templates.services.registry import ServiceRegistry, build_services


@pytest.fixture
def settings() -> Settings:
    reset_settings()
    return Settings(deployMode="mock")


@pytest.fixture
def registry(settings: Settings) -> ServiceRegistry:
    """构建使用内置模板库的 registry."""
    return build_services(settings=settings)


@pytest.fixture
def app(registry: ServiceRegistry):
    return create_app(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient."""
    return TestClient(app)


@pytest.fixture
def engine(registry: ServiceRegistry):
    """直接暴露 TemplateEngine，便于单元测试."""
    return registry.engine