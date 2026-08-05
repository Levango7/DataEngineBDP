"""关系模型."""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class Relation(BaseModel):
    """关系（实体之间的有向边）.

    Attributes:
        srcId: 起点实体 ID。
        dstId: 终点实体 ID。
        type: 关系类型（如 "located_in" / "works_for"）。
        properties: 关系附加属性。
        confidence: 抽取置信度 [0,1]。
    """

    srcId: str = Field(..., description="起点实体 ID")
    dstId: str = Field(..., description="终点实体 ID")
    type: str = Field(..., description="关系类型")
    properties: dict[str, Any] = Field(default_factory=dict, description="附加属性")
    confidence: float = Field(default=1.0, ge=0.0, le=1.0, description="抽取置信度")

    def to_edge(self) -> "Edge":
        """转换为图边（延迟导入避免循环）."""
        from knowledge_engine.models.graph import Edge

        return Edge(
            srcId=self.srcId,
            dstId=self.dstId,
            type=self.type,
            properties=dict(self.properties),
        )