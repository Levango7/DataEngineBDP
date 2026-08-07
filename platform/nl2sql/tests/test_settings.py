"""Settings 配置单测."""
from __future__ import annotations

import os

from config.settings import Settings, get_settings, reset_settings


class TestSettings:
    def test_defaults(self) -> None:
        s = Settings(llmMode="mock")
        assert s.port == 8093
        assert s.apiPrefix == "/api/v1"
        assert s.catalogUrl == "http://localhost:8082"
        assert s.sqlGatewayUrl == "http://localhost:8081"
        assert s.llmGatewayUrl == "http://localhost:8084"
        assert s.defaultEngine == "trino"
        assert s.selectOnly is True
        assert s.maxTables == 20
        assert s.maxDialogueTurns == 5
        assert s.llmMode == "mock"
        assert s.isMockLlm is True
        assert s.isLangchainLlm is False

    def test_env_override(self, monkeypatch) -> None:
        # 单 word 字段可通过环境变量覆盖
        monkeypatch.setenv("NL2SQL_PORT", "9000")
        s = Settings()
        assert s.port == 9000

    def test_camelcase_field_override_via_constructor(self) -> None:
        # 多 word camelCase 字段通过构造参数覆盖
        s = Settings(llmMode="langchain", defaultEngine="doris")
        assert s.llmMode == "langchain"
        assert s.defaultEngine == "doris"
        assert s.isLangchainLlm is True

    def test_log_level_validation(self) -> None:
        s = Settings(logLevel="debug")
        assert s.logLevel == "debug"
        try:
            Settings(logLevel="invalid")
            assert False, "应拒绝非法日志级别"
        except Exception:
            pass

    def test_llm_endpoint(self) -> None:
        s = Settings(llmGatewayUrl="http://localhost:8084")
        assert s.llmEndpoint == "http://localhost:8084/v1"
        s2 = Settings(llmGatewayUrl="http://localhost:8084/v1")
        assert s2.llmEndpoint == "http://localhost:8084/v1"

    def test_singleton(self) -> None:
        reset_settings()
        a = get_settings()
        b = get_settings()
        assert a is b
        reset_settings()