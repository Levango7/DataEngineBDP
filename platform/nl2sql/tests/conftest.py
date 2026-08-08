"""pytest 共享 fixtures.

Mock 模式（settings.py 默认 llmMode="mock"、selectOnly=True，无需环境变量覆盖）。
确保单测无外部依赖（无需 Catalog / SQL Gateway / LLM）。

注：pydantic-settings v2 对 camelCase 字段的环境变量映射为 prefix+字段名原样，
故多 word 字段（如 llmMode）通过构造参数覆盖，单 word 字段（如 port）可通过环境变量覆盖。
"""

from __future__ import annotations

from app import ServiceRegistry, build_services, create_app
from config.settings import Settings, reset_settings
from dialogue_clarifier import DialogueClarifier
from fastapi.testclient import TestClient
from gateway_client import GatewayClient
from intent_recognition import IntentRecognizer
import pytest
from schema_context import SchemaContextBuilder
from slot_filler import SlotFiller
from sql_generator import MockSqlGenerator
from sql_validator import SqlValidator


@pytest.fixture
def settings() -> Settings:
    """Mock 模式配置."""
    reset_settings()
    return Settings(llmMode="mock")


@pytest.fixture
def registry(settings: Settings) -> ServiceRegistry:
    """服务注册表（Mock 模式）."""
    return build_services(settings)


@pytest.fixture
def app(registry: ServiceRegistry):
    """FastAPI 应用."""
    return create_app(settings=registry.settings, registry=registry)


@pytest.fixture
def client(app) -> TestClient:
    """同步 TestClient."""
    return TestClient(app)


# ---- 单组件 fixtures ----
@pytest.fixture
def schemaBuilder(settings: Settings) -> SchemaContextBuilder:
    return SchemaContextBuilder(settings)


@pytest.fixture
def intentRecognizer() -> IntentRecognizer:
    return IntentRecognizer()


@pytest.fixture
def validator(settings: Settings) -> SqlValidator:
    return SqlValidator(settings)


@pytest.fixture
def mockGenerator(settings: Settings, validator: SqlValidator) -> MockSqlGenerator:
    return MockSqlGenerator(settings, validator)


@pytest.fixture
def slotFiller() -> SlotFiller:
    return SlotFiller()


@pytest.fixture
def clarifier(slotFiller: SlotFiller) -> DialogueClarifier:
    return DialogueClarifier(slotFiller=slotFiller, maxTurns=5)


@pytest.fixture
def gatewayClient(settings: Settings) -> GatewayClient:
    return GatewayClient(settings)
