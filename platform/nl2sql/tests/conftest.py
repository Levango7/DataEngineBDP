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


# 保证无监听的回环端口（端口 9 discard 端口，本机环境通常无监听），
# 使"网关不可达"用例不受开发机/CI 机上偶发端口占用影响
UNREACHABLE_GATEWAY_URL = "http://127.0.0.1:9"


@pytest.fixture
def gatewayClientUnreachable() -> GatewayClient:
    """指向必然不可达网关的客户端（用于 UNREACHABLE 路径测试）。"""
    reset_settings()
    s = Settings(llmMode="mock", sqlGatewayUrl=UNREACHABLE_GATEWAY_URL)
    return GatewayClient(s)


@pytest.fixture
def unreachableClient(gatewayClientUnreachable: GatewayClient, settings: Settings) -> TestClient:
    """使用不可达网关的应用客户端（用于 execute 端点降级路径测试）。"""
    registry = build_services(settings)
    # 用不可达客户端替换注册表中的网关客户端
    if hasattr(registry, "gatewayClient"):
        registry.gatewayClient = gatewayClientUnreachable
    return TestClient(create_app(settings=registry.settings, registry=registry))
