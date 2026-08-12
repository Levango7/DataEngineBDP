"""实体模型."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from pydantic import BaseModel, Field

if TYPE_CHECKING:
    from knowledge_engine.models.graph import Vertex


class Entity(BaseModel):
    """实体（Named Entity）.

    Attributes:
        id: 实体唯一 ID（写入图存储时作为 VID）。
        name: 实体表面名（如 "北京"）。
        type: 实体类型（如 "City" / "Person" / "Organization"）。
        properties: 实体附加属性。
        source: 来源标记（如原文片段或文档 ID）。
        confidence: 抽取置信度 [0,1]。
    """

    id: str = Field(..., description="实体唯一 ID")
    name: str = Field(..., min_length=1, description="实体表面名")
    type: str = Field(..., description="实体类型")
    properties: dict[str, Any] = Field(default_factory=dict, description="附加属性")
    source: str | None = Field(default=None, description="来源标记")
    confidence: float = Field(default=1.0, ge=0.0, le=1.0, description="抽取置信度")

    def to_vertex(self) -> "Vertex":
        """转换为图顶点（延迟导入避免循环）."""
        from knowledge_engine.models.graph import Vertex

        props = dict(self.properties)
        props.setdefault("name", self.name)
        return Vertex(id=self.id, label=self.type, properties=props)
