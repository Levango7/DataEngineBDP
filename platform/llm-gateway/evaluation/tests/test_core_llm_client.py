"""core 层单元测试：LLM 客户端（mock 模式）。"""

from __future__ import annotations

import httpx
import pytest
from app.config import Settings
from app.core.llm_client import ChatResponse, LLMGatewayClient


class TestLLMGatewayClientMock:
    def test_mock_mode_chat(self) -> None:
        """mock7mock 模式应返回 Mock 响应，不发起 HTTP 请求。"""
        client = LLMGatewayClient(mock_mode=True)
        result = client.chat("test-model", [{"role": "user", "content": "What is 2+2?"}])
        assert "content" in result
        assert result["content"] == "B"  # 默认 Mock 返回 "B"
        assert result["prompt_tokens"] > 0
        assert result["total_tokens"] >= 2

    def test_mock_mode_judge_prompt(self) -> None:
        """包含评判 prompt 的消息应返回 JSON Mock 响应。"""
        client = LLMGatewayClient(mock_mode=True)
        msg = "Please judge if the answer is correct. Respond in JSON format."
        result = client.chat("judge-model", [{"role": "user", "content": msg}])
        assert "correct" in result["content"]
        assert "hallucination" in result["content"]

    def test_chat_with_metrics(self) -> None:
        client = LLMGatewayClient(mock_mode=True)
        response = client.chat_with_metrics("test", [{"role": "user", "content": "hi"}])
        assert isinstance(response, ChatResponse)
        assert response.content == "B"
        assert response.latency_ms >= 0
        assert response.total_tokens > 0

    def test_health_mock_mode(self) -> None:
        """mock 模式下 health 应返回 False。"""
        client = LLMGatewayClient(mock_mode=True)
        assert client.health() is False

    def test_close(self) -> None:
        client = LLMGatewayClient(mock_mode=True)
        client.close()  # 不应 panic

    def test_base_url_trailing_slash(self) -> None:
        client = LLMGatewayClient(base_url="http://example.com/", mock_mode=True)
        assert client.base_url == "http://example.com"


class TestLLMGatewayClientMockFallback:
    def test_mock_fallback_disabled_by_default(self) -> None:
        """默认关闭 Mock 兜底，防止网关故障时静默计分。"""
        client = LLMGatewayClient()
        assert client.enable_mock_fallback is False

    def test_settings_default_mock_fallback_false(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Settings 默认 enable_mock_fallback=False。"""
        monkeypatch.delenv("EVAL_MOCK_FALLBACK", raising=False)
        settings = Settings.from_env()
        assert settings.enable_mock_fallback is False

    def test_chat_raises_when_unreachable_without_fallback(self) -> None:
        """网关不可达且未开启兜底时应抛出异常而非返回 'B'。"""
        client = LLMGatewayClient(base_url="http://127.0.0.1:1", timeout=2)
        with pytest.raises(httpx.HTTPError):
            client.chat("test-model", [{"role": "user", "content": "hi"}])

    def test_fallback_response_marks_raw_mock(self) -> None:
        """显式开启兜底时，结果 raw.mock=True 可辨识。"""
        client = LLMGatewayClient(base_url="http://127.0.0.1:1", timeout=2, enable_mock_fallback=True)
        result = client.chat("test-model", [{"role": "user", "content": "hi"}])
        assert result["raw"]["mock"] is True


class TestLLMGatewayClientParseResponse:
    def test_parse_full_response(self) -> None:
        data = {
            "choices": [{"message": {"content": "Hello"}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
        }
        result = LLMGatewayClient._parse_response(data)
        assert result["content"] == "Hello"
        assert result["prompt_tokens"] == 10
        assert result["completion_tokens"] == 5
        assert result["total_tokens"] == 15

    def test_parse_empty_choices(self) -> None:
        data = {"choices": [], "usage": {}}
        result = LLMGatewayClient._parse_response(data)
        assert result["content"] == ""
        assert result["prompt_tokens"] == 0

    def test_parse_missing_usage(self) -> None:
        data = {"choices": [{"message": {"content": "Hi"}}]}
        result = LLMGatewayClient._parse_response(data)
        assert result["content"] == "Hi"
        assert result["total_tokens"] == 0


class TestLLMGatewayClientMockResponse:
    def test_mock_response_default(self) -> None:
        result = LLMGatewayClient._mock_response("m", [{"role": "user", "content": "hello"}])
        assert result["content"] == "B"
        assert result["prompt_tokens"] > 0
        assert result["raw"]["mock"] is True

    def test_mock_response_judge(self) -> None:
        msg = "Is this correct? Respond in JSON."
        result = LLMGatewayClient._mock_response("m", [{"role": "user", "content": msg}])
        assert "correct" in result["content"]

    def test_mock_response_no_user_message(self) -> None:
        result = LLMGatewayClient._mock_response("m", [{"role": "system", "content": "sys"}])
        # 无 user 消息应仍返回有效 Mock
        assert "content" in result
        assert result["total_tokens"] >= 2
