"""core 层单元测试：LLM 客户端（mock 模式）。"""

from __future__ import annotations

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
