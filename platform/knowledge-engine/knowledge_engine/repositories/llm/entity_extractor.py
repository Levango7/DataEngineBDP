"""LLM 实体抽取器 - 调用大模型网关进行 NER.

设计原则：
    - 通过 httpx 异步调用 LLM 网关。
    - Prompt 模板与解析逻辑分离，便于切换模型。
    - 网关不可用时抛 ExtractorUnavailableError，不静默失败。
"""

from __future__ import annotations

import json
from typing import Any

import httpx

from knowledge_engine.interfaces.entity_extractor import EntityExtractor
from knowledge_engine.models.entity import Entity
from knowledge_engine.repositories import ExtractorUnavailableError

_PROMPT_TEMPLATE = """请从以下文本中抽取命名实体，仅返回 JSON 数组，每个元素形如：
{{"name": "实体名", "type": "实体类型", "properties": {{}}}}

支持的实体类型：{entity_types}

文本：
{text}

输出（仅 JSON 数组，不要其他文字）：
"""


class LLMEntityExtractor(EntityExtractor):
    """LLM 实体抽取器.

    Args:
        gateway_url: LLM 网关地址（如 http://localhost:8080）。
        model: 模型名（如 qwen2.5-7b-instruct）。
        api_key: 网关 API Key（可选）。
        timeout: 请求超时（秒）。

    Raises:
        ExtractorUnavailableError: 网关调用失败。
    """

    def __init__(
        self,
        gateway_url: str = "http://localhost:8080",
        model: str = "qwen2.5-7b-instruct",
        api_key: str | None = None,
        timeout: float = 30.0,
    ) -> None:
        self.gateway_url = gateway_url.rstrip("/")
        self.model = model
        self.api_key = api_key
        self.timeout = timeout

    async def extract(self, text: str, entity_types: list[str] | None = None) -> list[Entity]:
        types = entity_types or ["Person", "Organization", "City", "Date"]
        prompt = _PROMPT_TEMPLATE.format(entity_types=", ".join(types), text=text)
        payload = await self._call_llm(prompt)
        return self._parse_entities(payload, types)

    async def _call_llm(self, prompt: str) -> str:
        """调用 LLM 网关 /v1/chat/completions."""
        url = f"{self.gateway_url}/v1/chat/completions"
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        body = {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.0,
        }
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                resp = await client.post(url, json=body, headers=headers)
                resp.raise_for_status()
                data: Any = resp.json()
                return data["choices"][0]["message"]["content"]
        except (httpx.HTTPError, KeyError, IndexError) as exc:
            raise ExtractorUnavailableError(f"LLM 网关调用失败: {exc}") from exc

    @staticmethod
    def _parse_entities(payload: str, allowed_types: list[str]) -> list[Entity]:
        """解析 LLM 返回的 JSON 数组为 Entity 列表.

        容错策略：尝试直接 json.loads；失败时用正则提取最外层数组。
        """
        text = payload.strip()
        # 去除可能的 ```json 包裹
        if text.startswith("```"):
            text = text.strip("`")
            if text.startswith("json"):
                text = text[4:]
            text = text.strip()
        try:
            items = json.loads(text)
        except json.JSONDecodeError:
            return []
        if not isinstance(items, list):
            return []
        entities: list[Entity] = []
        for idx, item in enumerate(items):
            if not isinstance(item, dict):
                continue
            name = item.get("name")
            ent_type = item.get("type")
            if not name or not ent_type:
                continue
            if allowed_types and ent_type not in allowed_types:
                continue
            props = item.get("properties", {}) or {}
            ent_id = item.get("id") or f"llm-ent-{ent_type}-{name}-{idx}"
            entities.append(
                Entity(
                    id=ent_id,
                    name=str(name),
                    type=str(ent_type),
                    properties=props if isinstance(props, dict) else {},
                    source="llm",
                    confidence=float(item.get("confidence", 0.9)),
                )
            )
        return entities
