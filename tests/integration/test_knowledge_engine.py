"""Knowledge Engine（知识工程引擎, L4.5.2）集成测试.

被测组件：platform/knowledge-engine（FastAPI, 默认端口 8080，测试使用 8092 避免冲突）
启动方式：cd platform/knowledge-engine && KE_PORT=8092 python main.py

健康检查：GET /health → {"status": "ok", "store": "mock|nebula", "extractor": "mock|llm", "version": "0.1.0"}
主要端点：
    POST   /api/v1/spaces                              创建知识空间
    GET    /api/v1/spaces                              列出知识空间
    DELETE /api/v1/spaces/{name}                       删除知识空间
    POST   /api/v1/spaces/{name}/entities              插入实体
    POST   /api/v1/spaces/{name}/edges                 插入关系
    POST   /api/v1/spaces/{name}/extract               从文本抽取知识（不写入）
    POST   /api/v1/spaces/{name}/build                 从文本构建知识图谱
    GET    /api/v1/spaces/{name}/vertices/{vid}        查询顶点
    GET    /api/v1/spaces/{name}/vertices/{vid}/neighbors  查询邻居
    POST   /api/v1/spaces/{name}/query                 原生图查询
    POST   /api/v1/spaces/{name}/shortest-path         最短路径查询
"""
from __future__ import annotations

import uuid

import httpx
import pytest

# 注意：knowledge-engine 健康检查路径为 /health（无 /api/v1 前缀）
HEALTH_PATH = "/health"
SPACES_PATH = "/api/v1/spaces"
DEFAULT_TIMEOUT = 10.0


# ---------------------------------------------------------------------------
# 健康检查 & 基础冒烟
# ---------------------------------------------------------------------------
def test_health_check(knowledge_engine_url):
    """健康检查返回 200 且 status=ok.

    端点：GET /health
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(knowledge_engine_url + HEALTH_PATH)
    assert resp.status_code == 200
    body = resp.json()
    # knowledge-engine 使用 status=ok（与其他组件的 UP 不同）
    assert body["status"] == "ok"
    assert "store" in body
    assert "extractor" in body


def test_openapi_schema(knowledge_engine_url):
    """OpenAPI schema 可访问."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(knowledge_engine_url + "/openapi.json")
    assert resp.status_code == 200
    schema = resp.json()
    assert schema["info"]["title"] == "Knowledge Engineering Engine"


def test_list_spaces(knowledge_engine_url):
    """列出知识空间返回 200 且为列表.

    端点：GET /api/v1/spaces
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(knowledge_engine_url + SPACES_PATH)
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


# ---------------------------------------------------------------------------
# 知识空间 CRUD + 知询流程
# ---------------------------------------------------------------------------
def _space_name() -> str:
    """生成唯一知识空间名（限制长度避免超限）."""
    return f"it_ke_{uuid.uuid4().hex[:8]}"


def test_create_space(knowledge_engine_url):
    """创建知识空间返回 201，含 name.

    端点：POST /api/v1/spaces
    """
    name = _space_name()
    payload = {"name": name, "schema": {"vertexLabels": [], "edgeLabels": []}}
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.post(knowledge_engine_url + SPACES_PATH, json=payload)
    assert resp.status_code == 201, resp.text
    space = resp.json()
    assert space["name"] == name

    # 清理
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        client.delete(knowledge_engine_url + f"{SPACES_PATH}/{name}")


def test_delete_space(knowledge_engine_url):
    """删除知识空间返回 200/204.

    端点：DELETE /api/v1/spaces/{name}
    """
    name = _space_name()
    payload = {"name": name, "schema": {"vertexLabels": [], "edgeLabels": []}}
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        client.post(knowledge_engine_url + SPACES_PATH, json=payload)
        resp = client.delete(knowledge_engine_url + f"{SPACES_PATH}/{name}")
    assert resp.status_code in (200, 204)


def test_extract_from_text(knowledge_engine_url):
    """从文本抽取知识（不写入）返回 200，含实体/关系.

    端点：POST /api/v1/spaces/{name}/extract
    """
    name = _space_name()
    payload = {"name": name, "schema": {"vertexLabels": [], "edgeLabels": []}}
    text_payload = {"text": "华为公司总部位于深圳，任正非是创始人。"}
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        client.post(knowledge_engine_url + SPACES_PATH, json=payload)
        try:
            resp = client.post(
                knowledge_engine_url + f"{SPACES_PATH}/{name}/extract",
                json=text_payload,
            )
        finally:
            client.delete(knowledge_engine_url + f"{SPACES_PATH}/{name}")

    assert resp.status_code == 200, resp.text
    result = resp.json()
    # 抽取结果应包含实体或关系字段
    assert "entities" in result or "vertices" in result


def test_build_and_query_flow(knowledge_engine_url):
    """端到端流程：建空间 → 插入实体 → 查询顶点 → 最短路径 → 删空间."""
    name = _space_name()
    schema = {
        "vertexLabels": [{"name": "Company"}, {"name": "Person"}],
        "edgeLabels": [{"name": "located_in"}, {"name": "founder_of"}],
    }
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        # 1. 建空间
        created = client.post(
            knowledge_engine_url + SPACES_PATH, json={"name": name, "schema": schema}
        )
        assert created.status_code == 201, created.text

        try:
            # 2. 插入实体
            entities = {
                "entities": [
                    {"id": "c1", "name": "华为", "type": "Company"},
                    {"id": "p1", "name": "任正非", "type": "Person"},
                ]
            }
            ins = client.post(
                knowledge_engine_url + f"{SPACES_PATH}/{name}/entities",
                json=entities,
            )
            assert ins.status_code in (200, 201), ins.text

            # 3. 查询顶点
            vtx = client.get(
                knowledge_engine_url + f"{SPACES_PATH}/{name}/vertices/c1"
            )
            assert vtx.status_code == 200, vtx.text

            # 4. 原生图查询（AUTH_MODE=none 时安全策略禁止原生 nGQL，返回 403）
            q = client.post(
                knowledge_engine_url + f"{SPACES_PATH}/{name}/query",
                json={"nql": "MATCH (n) RETURN n LIMIT 10"},
            )
            assert q.status_code in (200, 403), q.text
        finally:
            # 5. 删空间
            client.delete(knowledge_engine_url + f"{SPACES_PATH}/{name}")