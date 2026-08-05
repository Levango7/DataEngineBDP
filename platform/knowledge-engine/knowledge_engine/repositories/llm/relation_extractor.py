"""LLM 关系抽取器 - 调用大模型网关进行关系抽取.

设计原则：
    - 复用 LLMEntityExtractor 的网关调用模式。
    - Prompt 中携带已识别实体列表，让模型聚焦于关系判定。
"""
from __future__ import annotations

import json
from typing import Any

import httpx

from knowledge_engine.interfaces.relation_extractor import RelationExtractor
from knowledge_engine.models.entity import Entity
from knowledge_engine.models.relation import Relation
from knowledge_engine.repositories import ExtractorUnavailableError

_PROMPT_TEMPLATE = """请根据以下文本与已识别实体，抽取实体之间的关系。
仅返回 JSON 数组，每个元素形如：
{{"srcId": "起点实体ID", "dstId": "终点实体ID", "type": "关系类型", "properties": {{}}}}

已识别实体（id / name / type）：
{entities}

文本：
{text}

输出（仅 JSON 数组，不要其他文字）：
"""


class LLMRelationExtractor(RelationExtractor):
    """LLM 关系抽取器.

    Args:
        gateway_url: LLM 网关地址。
        model: 模型名。
        api_key: 网关 API Key（可选）。
        timeout: 请求超时（秒）。
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

    async def extract(
        self, text: str, entities: list[Entity]
    ) -> list[Relation]:
        if not entities:
            return []
        entities_text = "\n".join(
            f"- {e.id} / {e.name} / {e.type}" for e in entities
        )
        prompt = _PROMPT_TEMPLATE.format(entities=entities_text, text=text)
        payload = await self._call_llm(prompt)
        return self._parse_relations(payload, {e.id for e in entities})

    async def _call_llm(self, prompt: str) -> str:
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
    def _parse_relations(payload: str, valid_ids: set[str]) -> list[Relation]:
        text = payload.strip()
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
        relations: list[Relation] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            src_id = item.get("srcId")
            dst_id = item.get("dstId")
            rel_type = item.get("type")
            if not src_id or not dst_id or not rel_type:
                continue
            if src_id not in valid_ids or dst_id not in valid_ids:
                continue
            props = item.get("properties", {}) or {}
            relations.append(
                Relation(
                    srcId=str(src_id),
                    dstId=str(dst_id),
                    type=str(rel_type),
                    properties=props if isinstance(props, dict) else {},
                    confidence=float(item.get("confidence", 0.85)),
                )
            )
        return relations