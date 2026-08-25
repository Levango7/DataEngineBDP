"""LLM 网关客户端。

封装对 T030 多模态网关（OpenAI 兼容 API）的调用：
- chat：同步对话补全
- chat_with_metrics：对话补全并返回延迟/Token 指标

网关端点：POST {base_url}/v1/chat/completions
认证：Bearer token（通过 api_key 注入）

支持 Mock 模式：当 base_url 不可达时，返回 Mock 响应，
保证评测在无网关环境也可运行（用于测试）。
"""

from __future__ import annotations

from dataclasses import dataclass
import logging
import time
from typing import Any, Optional

import httpx

logger = logging.getLogger(__name__)


@dataclass
class ChatResponse:
    """对话补全响应（含指标）。"""

    content: str
    latency_ms: float
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    raw: dict[str, Any]


class LLMGatewayClient:
    """LLM 网关客户端。

    封装对 T030 多模态网关的调用，支持：
    - 同步对话补全
    - 延迟与 Token 指标采集
    - Mock 模式（网关不可达时返回 Mock 响应）
    """

    def __init__(
        self,
        base_url: str = "http://localhost:18085",
        api_key: str = "dummy",
        timeout: int = 30,
        enable_mock_fallback: bool = False,
        mock_mode: bool = False,
    ):
        """
        Args:
            base_url: LLM 网关基础 URL
            api_key: API Key（Bearer token）
            timeout: 请求超时秒数
            enable_mock_fallback: 网关不可达时是否回退到 Mock 响应（默认关闭，
                避免网关故障时静默产出 Mock 计分导致指标失真；可用 EVAL_MOCK_FALLBACK 显式开启）
            mock_mode: 纯 Mock 模式，不发起任何 HTTP 请求，直接返回 Mock 响应
        """
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self.enable_mock_fallback = enable_mock_fallback
        self.mock_mode = mock_mode
        self._client: Optional[httpx.Client] = None

    @property
    def client(self) -> httpx.Client:
        """懒加载 httpx 客户端。"""
        if self._client is None:
            self._client = httpx.Client(
                timeout=self.timeout,
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                },
            )
        return self._client

    def chat(
        self,
        model: str,
        messages: list[dict[str, Any]],
        **kwargs: Any,
    ) -> dict[str, Any]:
        """同步对话补全。

        Args:
            model: 模型名
            messages: 消息列表（OpenAI 格式）
            **kwargs: 额外参数（temperature, max_tokens 等）

        Returns:
            响应字典，包含 content 与 usage 信息
        """
        payload = {"model": model, "messages": messages, **kwargs}
        url = f"{self.base_url}/v1/chat/completions"

        # 纯 Mock 模式：不发起 HTTP 请求，直接返回 Mock 响应
        if self.mock_mode:
            return self._mock_response(model, messages)

        try:
            resp = self.client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
            return self._parse_response(data)
        except (httpx.HTTPError, httpx.ConnectError, Exception) as e:  # noqa: BLE001
            logger.warning("LLM 网关调用失败: %s", e)
            if self.enable_mock_fallback:
                return self._mock_response(model, messages)
            raise

    def chat_with_metrics(
        self,
        model: str,
        messages: list[dict[str, Any]],
        **kwargs: Any,
    ) -> ChatResponse:
        """对话补全并采集延迟与 Token 指标。

        Args:
            model: 模型名
            messages: 消息列表
            **kwargs: 额外参数

        Returns:
            ChatResponse，含 content、延迟、Token 指标
        """
        start = time.perf_counter()
        result = self.chat(model, messages, **kwargs)
        latency_ms = (time.perf_counter() - start) * 1000

        return ChatResponse(
            content=result.get("content", ""),
            latency_ms=latency_ms,
            prompt_tokens=result.get("prompt_tokens", 0),
            completion_tokens=result.get("completion_tokens", 0),
            total_tokens=result.get("total_tokens", 0),
            raw=result,
        )

    def health(self) -> bool:
        """检查 LLM 网关是否可达。"""
        if self.mock_mode:
            return False  # Mock 模式下网关不可达
        try:
            resp = self.client.get(f"{self.base_url}/health")
            return resp.status_code == 200
        except Exception:  # noqa: BLE001
            return False

    def close(self) -> None:
        """关闭客户端。"""
        if self._client is not None:
            self._client.close()
            self._client = None

    @staticmethod
    def _parse_response(data: dict[str, Any]) -> dict[str, Any]:
        """解析 OpenAI 兼容响应。"""
        content = ""
        choices = data.get("choices", [])
        if choices:
            message = choices[0].get("message", {})
            content = message.get("content", "")
        usage = data.get("usage", {})
        return {
            "content": content,
            "prompt_tokens": usage.get("prompt_tokens", 0),
            "completion_tokens": usage.get("completion_tokens", 0),
            "total_tokens": usage.get("total_tokens", 0),
            "raw": data,
        }

    @staticmethod
    def _mock_response(model: str, messages: list[dict[str, Any]]) -> dict[str, Any]:
        """生成 Mock 响应（网关不可达时回退）。

        Mock 策略：
        - 提取最后一条 user 消息
        - 返回简化 Mock 内容（包含 "A" 或 "B" 等选项字母，便于评测）
        - Token 计量基于消息长度估算
        """
        # 提取最后一条 user 消息
        user_msg = ""
        for m in reversed(messages):
            if m.get("role") == "user":
                content = m.get("content", "")
                if isinstance(content, str):
                    user_msg = content
                break

        # Mock 内容：返回 "B"（最常见的正确选项，便于演示）
        # 若消息中包含评判 prompt，返回正确 JSON
        if "correct" in user_msg and "JSON" in user_msg:
            mock_content = '{"correct": true, "hallucination": false, "reason": "mock judge"}'
        else:
            mock_content = "B"

        # Token 估算：4 字符 ≈ 1 token
        prompt_tokens = sum(len(str(m.get("content", ""))) // 4 for m in messages)
        completion_tokens = len(mock_content) // 4

        return {
            "content": mock_content,
            "prompt_tokens": max(prompt_tokens, 1),
            "completion_tokens": max(completion_tokens, 1),
            "total_tokens": max(prompt_tokens + completion_tokens, 2),
            "raw": {"mock": True, "model": model},
        }
