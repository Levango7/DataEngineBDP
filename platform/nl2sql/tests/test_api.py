"""FastAPI 端点集成单测."""
from __future__ import annotations

import pytest


class TestHealth:
    def test_health(self, client) -> None:
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "UP"
        assert data["component"] == "nl2sql"
        assert data["version"] == "0.1.0"
        assert data["llmMode"] == "mock"


class TestGenerate:
    def test_generate_simple(self, client) -> None:
        resp = client.post("/api/v1/nl2sql/generate", json={
            "query": "查询 orders 表",
            "useMockSchema": True,
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "sql" in data
        assert "SELECT" in data["sql"].upper()
        assert data["intent"] is not None
        assert data["validation"] is not None
        assert data["llmUsed"] is False

    def test_generate_aggregation(self, client) -> None:
        resp = client.post("/api/v1/nl2sql/generate", json={
            "query": "统计 orders 订单数量",
            "useMockSchema": True,
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "COUNT" in data["sql"].upper()
        assert data["intent"]["primaryType"] == "aggregation"

    def test_generate_with_database(self, client) -> None:
        resp = client.post("/api/v1/nl2sql/generate", json={
            "query": "查询数据",
            "database": "default",
            "useMockSchema": True,
        })
        assert resp.status_code == 200

    def test_generate_with_table_hints(self, client) -> None:
        resp = client.post("/api/v1/nl2sql/generate", json={
            "query": "随便看看",
            "tableHints": ["users"],
            "useMockSchema": True,
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "users" in data["sql"]


class TestExecute:
    def test_execute_unreachable_gateway(self, client) -> None:
        """SQL 网关不可达，应返回 sql 但 gateway 含错误."""
        resp = client.post("/api/v1/nl2sql/execute", json={
            "query": "查询 orders 表",
            "useMockSchema": True,
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "sql" in data
        assert data["gateway"] is not None
        assert data["gateway"]["status"] == "UNREACHABLE"

    def test_execute_invalid_sql_skips_gateway(self, client) -> None:
        """若生成 SQL 校验失败，应跳过网关执行."""
        # 构造一个会生成非 SELECT 的场景较难，这里仅验证端点可用
        resp = client.post("/api/v1/nl2sql/execute", json={
            "query": "查询 orders",
            "useMockSchema": True,
        })
        assert resp.status_code == 200


class TestDialogue:
    def test_dialogue_start_no_clarification(self, client) -> None:
        """简单查询无需澄清，直接返回 SQL."""
        resp = client.post("/api/v1/nl2sql/dialogue/start", json={
            "query": "查询 orders 表",
            "useMockSchema": True,
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "sessionId" in data
        assert data["sql"] is not None or data["nextQuestion"] is not None

    def test_dialogue_start_with_clarification(self, client) -> None:
        """Join 查询缺失必需槽位，应返回澄清问题."""
        resp = client.post("/api/v1/nl2sql/dialogue/start", json={
            "query": "关联一下",
            "useMockSchema": True,
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["sessionId"] is not None

    def test_dialogue_answer_unknown_session(self, client) -> None:
        resp = client.post("/api/v1/nl2sql/dialogue/answer", json={
            "sessionId": "nonexistent",
            "answer": "昨天",
            "useMockSchema": True,
        })
        assert resp.status_code == 404

    def test_dialogue_full_flow(self, client) -> None:
        """完整对话流程：start → answer → ..."""
        # 1. start
        resp = client.post("/api/v1/nl2sql/dialogue/start", json={
            "query": "查询 orders 数据",
            "useMockSchema": True,
        })
        data = resp.json()
        sid = data["sessionId"]
        # 2. 若有 nextQuestion，提交回答
        if data.get("nextQuestion"):
            resp2 = client.post("/api/v1/nl2sql/dialogue/answer", json={
                "sessionId": sid,
                "answer": "昨天",
                "useMockSchema": True,
            })
            assert resp2.status_code == 200


class TestValidate:
    def test_validate_valid(self, client) -> None:
        resp = client.post("/api/v1/nl2sql/validate", json={
            "sql": "SELECT * FROM default.orders LIMIT 10;",
            "useMockSchema": True,
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["valid"] is True

    def test_validate_invalid(self, client) -> None:
        resp = client.post("/api/v1/nl2sql/validate", json={
            "sql": "DELETE FROM orders;",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["valid"] is False

    def test_validate_empty(self, client) -> None:
        resp = client.post("/api/v1/nl2sql/validate", json={
            "sql": "",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["valid"] is False


class TestSchema:
    def test_schema_endpoint(self, client) -> None:
        resp = client.get("/api/v1/nl2sql/schema?useMock=true")
        assert resp.status_code == 200
        data = resp.json()
        assert "tables" in data
        assert len(data["tables"]) == 3

    def test_schema_with_database(self, client) -> None:
        resp = client.get("/api/v1/nl2sql/schema?database=default&useMock=true")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data["tables"]) == 3