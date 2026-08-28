"""API 端点单元测试."""

from __future__ import annotations

from fastapi.testclient import TestClient
from test_jwt_auth import make_token


class TestHealth:
    """健康检查测试."""

    def test_health(self, client: TestClient) -> None:
        resp = client.get("/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok"
        assert body["store"] == "mock"
        assert body["extractor"] == "mock"
        assert body["version"] == "0.1.0"


class TestSpaces:
    """知识空间 API 测试."""

    def test_create_space(self, client: TestClient) -> None:
        resp = client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        assert resp.status_code == 201
        assert resp.json()["name"] == "kg1"

    def test_create_duplicate_space(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        resp = client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        assert resp.status_code == 409

    def test_list_spaces(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        client.post("/api/v1/spaces", json={"name": "kg2", "schema": {}})
        resp = client.get("/api/v1/spaces")
        assert resp.status_code == 200
        names = resp.json()
        assert "kg1" in names
        assert "kg2" in names

    def test_drop_space(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        resp = client.delete("/api/v1/spaces/kg1")
        assert resp.status_code == 204
        # 再删应 404
        resp = client.delete("/api/v1/spaces/kg1")
        assert resp.status_code == 404


class TestExtractAndBuild:
    """抽取与构建 API 测试."""

    def test_extract(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        resp = client.post(
            "/api/v1/spaces/kg1/extract",
            json={"text": "张三在北京工作"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert "entities" in body
        assert "relations" in body
        assert len(body["entities"]) > 0

    def test_extract_with_type_filter(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        resp = client.post(
            "/api/v1/spaces/kg1/extract",
            json={"text": "张三在北京工作", "entityTypes": ["Person"]},
        )
        assert resp.status_code == 200
        for ent in resp.json()["entities"]:
            assert ent["type"] == "Person"

    def test_build(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        resp = client.post(
            "/api/v1/spaces/kg1/build",
            json={"text": "张三在北京工作"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["space"] == "kg1"
        assert body["insertedVertices"] > 0
        assert body["insertedVertices"] == len(body["entities"])
        assert body["insertedEdges"] == len(body["relations"])

    def test_build_unknown_space(self, client: TestClient) -> None:
        resp = client.post(
            "/api/v1/spaces/nope/build",
            json={"text": "张三在北京工作"},
        )
        assert resp.status_code == 404


class TestInsertEntitiesAndEdges:
    """直接写入 API 测试."""

    def test_insert_entities(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        resp = client.post(
            "/api/v1/spaces/kg1/entities",
            json={
                "entities": [
                    {"id": "e1", "name": "张三", "type": "Person"},
                    {"id": "e2", "name": "北京", "type": "City"},
                ]
            },
        )
        assert resp.status_code == 200
        assert resp.json()["inserted"] == 2

    def test_insert_edges(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        resp = client.post(
            "/api/v1/spaces/kg1/edges",
            json={
                "edges": [
                    {"srcId": "a", "dstId": "b", "type": "r"},
                ]
            },
        )
        assert resp.status_code == 200
        assert resp.json()["inserted"] == 1


class TestQuery:
    """查询 API 测试."""

    def test_get_vertex(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        client.post(
            "/api/v1/spaces/kg1/entities",
            json={"entities": [{"id": "v1", "name": "x", "type": "Person"}]},
        )
        resp = client.get("/api/v1/spaces/kg1/vertices/v1")
        assert resp.status_code == 200
        body = resp.json()
        assert body["id"] == "v1"
        assert body["label"] == "Person"

    def test_get_vertex_not_found(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        resp = client.get("/api/v1/spaces/kg1/vertices/nope")
        assert resp.status_code == 404

    def test_get_neighbors(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        # 通过 build 写入边
        client.post(
            "/api/v1/spaces/kg1/build",
            json={"text": "张三在北京工作"},
        )
        # 取一个实体 ID 查询邻居
        extract = client.post("/api/v1/spaces/kg1/extract", json={"text": "张三在北京工作"}).json()
        # 找一个 Person 实体
        person = next(e for e in extract["entities"] if e["type"] == "Person")
        resp = client.get(f"/api/v1/spaces/kg1/vertices/{person['id']}/neighbors")
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    def test_query_match(self, client: TestClient, monkeypatch) -> None:
        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", "ke-unit-test-secret-key-32b!!")
        headers = {"Authorization": f"Bearer {make_token()}"}
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}}, headers=headers)
        client.post(
            "/api/v1/spaces/kg1/entities",
            json={"entities": [{"id": "v1", "name": "x", "type": "Person"}]},
            headers=headers,
        )
        resp = client.post(
            "/api/v1/spaces/kg1/query",
            json={"nql": "MATCH (v) RETURN v"},
            headers=headers,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["columns"] == ["v"]
        assert len(body["rows"]) >= 1

    def test_shortest_path(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        client.post(
            "/api/v1/spaces/kg1/entities",
            json={
                "entities": [
                    {"id": "a", "name": "A", "type": "P"},
                    {"id": "b", "name": "B", "type": "P"},
                ]
            },
        )
        client.post(
            "/api/v1/spaces/kg1/edges",
            json={"edges": [{"srcId": "a", "dstId": "b", "type": "r"}]},
        )
        resp = client.post(
            "/api/v1/spaces/kg1/shortest-path",
            json={"srcId": "a", "dstId": "b"},
        )
        assert resp.status_code == 200
        path = resp.json()
        assert [v["id"] for v in path] == ["a", "b"]

    def test_shortest_path_unreachable(self, client: TestClient) -> None:
        client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}})
        client.post(
            "/api/v1/spaces/kg1/entities",
            json={
                "entities": [
                    {"id": "a", "name": "A", "type": "P"},
                    {"id": "b", "name": "B", "type": "P"},
                ]
            },
        )
        resp = client.post(
            "/api/v1/spaces/kg1/shortest-path",
            json={"srcId": "a", "dstId": "b"},
        )
        assert resp.status_code == 200
        assert resp.json() == []


class TestOpenApi:
    """OpenAPI 文档可访问性测试."""

    def test_openapi(self, client: TestClient) -> None:
        resp = client.get("/openapi.json")
        assert resp.status_code == 200
        data = resp.json()
        assert data["info"]["title"] == "Knowledge Engineering Engine"

    def test_docs(self, client: TestClient) -> None:
        resp = client.get("/docs")
        assert resp.status_code == 200
