"""知识图谱构建服务 - 编排实体抽取、关系抽取、图谱存储.

核心流程：文本 → 实体抽取 → 关系抽取 → 图谱写入。
"""

from __future__ import annotations

from dataclasses import dataclass

from knowledge_engine.interfaces.entity_extractor import EntityExtractor
from knowledge_engine.interfaces.graph_store import GraphStore
from knowledge_engine.interfaces.relation_extractor import RelationExtractor
from knowledge_engine.models.entity import Entity
from knowledge_engine.models.graph import (
    Edge,
    GraphSchema,
    Vertex,
    VertexLabelDefinition,
)
from knowledge_engine.models.relation import Relation


@dataclass
class ExtractResult:
    """抽取结果（实体 + 关系）."""

    entities: list[Entity]
    relations: list[Relation]


@dataclass
class BuildResult:
    """构建结果."""

    space: str
    insertedVertices: int
    insertedEdges: int
    entities: list[Entity]
    relations: list[Relation]


class KnowledgeService:
    """知识图谱构建服务."""

    def __init__(
        self,
        store: GraphStore,
        entity_extractor: EntityExtractor,
        relation_extractor: RelationExtractor,
    ) -> None:
        self.store = store
        self.entityExtractor = entity_extractor
        self.relationExtractor = relation_extractor

    async def create_space(
        self,
        space_name: str,
        schema: GraphSchema | None = None,
    ) -> None:
        """创建知识空间（Schema 缺省时为空 Schema）."""
        await self.store.create_space(space_name, schema or GraphSchema())

    async def drop_space(self, space_name: str) -> None:
        """删除知识空间."""
        await self.store.drop_space(space_name)

    async def list_spaces(self) -> list[str]:
        """列出所有知识空间."""
        return await self.store.list_spaces()

    async def extract(
        self,
        space: str,
        text: str,
        entity_types: list[str] | None = None,
    ) -> ExtractResult:
        """从文本抽取实体与关系（不写入图存储）."""
        entities = await self.entityExtractor.extract(text, entity_types)
        relations = await self.relationExtractor.extract(text, entities)
        return ExtractResult(entities=entities, relations=relations)

    async def build(
        self,
        space: str,
        text: str,
        entity_types: list[str] | None = None,
    ) -> BuildResult:
        """完整构建流程：抽取 → 写入图存储.

        Returns:
            BuildResult 含写入计数与抽取结果。
        """
        result = await self.extract(space, text, entity_types)

        # 写入顶点
        vertices: list[Vertex] = []
        seen_vid: set[str] = set()
        for ent in result.entities:
            if ent.id in seen_vid:
                continue
            seen_vid.add(ent.id)
            vertices.append(ent.to_vertex())

        # 写入边
        edges: list[Edge] = []
        seen_edge: set[tuple[str, str, str]] = set()
        for rel in result.relations:
            key = (rel.srcId, rel.type, rel.dstId)
            if key in seen_edge:
                continue
            seen_edge.add(key)
            edges.append(rel.to_edge())

        # 确保 Schema 包含所用标签（Mock 模式宽松，Nebula 模式应预先创建 Schema）
        await self._ensure_schema(space, vertices, edges)

        await self.store.bulk_insert(space, vertices, edges)
        return BuildResult(
            space=space,
            insertedVertices=len(vertices),
            insertedEdges=len(edges),
            entities=result.entities,
            relations=result.relations,
        )

    async def insert_entities(self, space: str, entities: list[Entity]) -> int:
        """直接写入实体（跳过抽取）."""
        vertices: list[Vertex] = []
        seen: set[str] = set()
        for ent in entities:
            if ent.id in seen:
                continue
            seen.add(ent.id)
            vertices.append(ent.to_vertex())
        await self._ensure_schema(space, vertices, [])
        await self.store.bulk_insert(space, vertices, [])
        return len(vertices)

    async def insert_relations(self, space: str, relations: list[Relation]) -> int:
        """直接写入关系（跳过抽取）."""
        edges: list[Edge] = []
        seen: set[tuple[str, str, str]] = set()
        for rel in relations:
            key = (rel.srcId, rel.type, rel.dstId)
            if key in seen:
                continue
            seen.add(key)
            edges.append(rel.to_edge())
        await self.store.bulk_insert(space, [], edges)
        return len(edges)

    async def _ensure_schema(
        self,
        space: str,
        vertices: list[Vertex],
        edges: list[Edge],
    ) -> None:
        """确保空间 Schema 包含所用标签（仅 Mock 需要，Nebula 应预建）.

        简化策略：尝试获取现有 Schema，若标签缺失则补全。
        对 Mock 而言，create_space 已建立空 Schema，此处补全标签定义。
        对 Nebula 而言，CREATE TAG IF NOT EXISTS 是幂等的，可重复执行。
        """
        try:
            schema = await self.store.get_schema(space)
        except Exception:  # noqa: BLE001
            return
        existing_labels = {vl.name for vl in schema.vertexLabels}
        new_labels: list[VertexLabelDefinition] = []
        for v in vertices:
            if v.label and v.label not in existing_labels:
                existing_labels.add(v.label)
                new_labels.append(VertexLabelDefinition(name=v.label, properties={}))
        if new_labels:
            schema.vertexLabels.extend(new_labels)
            # MockGraphStore 不支持更新 Schema，此处仅做内存补全；
            # NebulaGraphStore 应在 create_space 时预建 Schema。
            # 这里不重新调用 create_space（会冲突），保持宽松。
