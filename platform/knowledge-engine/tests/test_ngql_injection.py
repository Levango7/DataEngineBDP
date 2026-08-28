"""nGQL 注入防护测试：标识符白名单、字面量转义、会话串行化、路由 400 映射."""

from __future__ import annotations

import threading
import time

from fastapi.testclient import TestClient
import pytest

from knowledge_engine.repositories.mock import MockGraphStore
from knowledge_engine.repositories.nebula.graph_store import NebulaGraphStore


def bare_store() -> NebulaGraphStore:
    """绕过 __init__（跳过 SDK 连接）构造仅含校验/执行所需属性的实例."""
    store = NebulaGraphStore.__new__(NebulaGraphStore)
    store._session = None
    store._executeLock = threading.Lock()
    return store


class TestIdentifierValidation:
    @pytest.mark.parametrize("value", ["kg1", "_space", "a.b-c_d", "A9", "x" * 128])
    def test_accepts_valid(self, value: str) -> None:
        assert NebulaGraphStore._validate_identifier(value) == value

    @pytest.mark.parametrize(
        "value",
        [
            "",
            "kg1; DROP SPACE kg2",
            "`kg`;",
            'kg" ',
            "has space",
            "-leading",
            ".leading",
            "中文",
            "x" * 129,
            None,
            123,
        ],
    )
    def test_rejects_invalid(self, value) -> None:
        with pytest.raises(ValueError):
            NebulaGraphStore._validate_identifier(value)

    @pytest.mark.asyncio
    async def test_insert_vertex_rejects_injection_vid(self) -> None:
        store = bare_store()
        with pytest.raises(ValueError):
            await store.insert_vertex("kg", "Person", 'v1"); DROP SPACE kg; #', {})

    @pytest.mark.asyncio
    async def test_insert_edge_rejects_injection_space(self) -> None:
        store = bare_store()
        with pytest.raises(ValueError):
            await store.insert_edge("kg`; DROP SPACE kg2", "r", "a", "b", {})

    @pytest.mark.asyncio
    async def test_drop_space_rejects_injection_name(self) -> None:
        store = bare_store()
        with pytest.raises(ValueError):
            await store.drop_space("kg1; USE `kg2`")

    @pytest.mark.asyncio
    async def test_validation_precedes_execution_without_session(self) -> None:
        store = bare_store()
        assert store._session is None
        with pytest.raises(ValueError):
            await store.get_vertex("bad space", "v1")


class TestValueEscaping:
    def test_escapes_double_quote(self) -> None:
        assert NebulaGraphStore._nebula_value('say "hi"') == '"say \\"hi\\""'

    def test_escapes_backslash_before_quote(self) -> None:
        assert NebulaGraphStore._nebula_value('a\\"') == '"a\\\\\\""'

    def test_escapes_trailing_backslash(self) -> None:
        assert NebulaGraphStore._nebula_value("a\\") == '"a\\\\"'

    def test_quote_injection_neutralized(self) -> None:
        payload = 'v1"; INSERT VERTEX `P`(name) VALUES "pwned":("x"); #'
        rendered = NebulaGraphStore._nebula_value(payload)
        assert rendered.startswith('"v1\\";')
        assert '\\"' in rendered

    def test_non_string_value_escaped(self) -> None:
        rendered = NebulaGraphStore._nebula_value(('x"y'))
        assert rendered == '"x\\"y"'


class _OverlappingSession:
    """记录最大并发 execute 重叠数的假 session."""

    def __init__(self) -> None:
        self._counter_lock = threading.Lock()
        self.active = 0
        self.maxActive = 0
        self.calls = 0

    def execute(self, nql: str):
        with self._counter_lock:
            self.active += 1
            self.maxActive = max(self.maxActive, self.active)
            self.calls += 1
        time.sleep(0.002)
        with self._counter_lock:
            self.active -= 1

        class _Resp:
            def is_succeeded(self) -> bool:
                return True

        return _Resp()


class TestSessionSerialization:
    def test_concurrent_execute_fully_serialized(self) -> None:
        store = bare_store()
        session = _OverlappingSession()
        store._session = session

        def worker(i: int) -> None:
            for _ in range(5):
                store._execute(f'USE `sp{i}`; INSERT VERTEX `_t`() VALUES "{i}":();')

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(6)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert session.calls == 30
        assert session.maxActive == 1


class TestRouteErrorMapping:
    def test_store_value_error_maps_to_400_on_get_vertex(self, client: TestClient, registry, monkeypatch) -> None:
        async def boom(space: str, vid: str):
            raise ValueError("非法图标识符")

        monkeypatch.setattr(registry.queryService.store, "get_vertex", boom)
        resp = client.get("/api/v1/spaces/kg1/vertices/bad%20vid")
        assert resp.status_code == 400

    def test_raw_query_invalid_space_maps_to_400(self, app, client: TestClient, registry, monkeypatch) -> None:
        from test_jwt_auth import auth_headers, make_token

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", "ke-unit-test-secret-key-32b!!")

        async def boom(space: str, nql: str):
            raise ValueError("非法图标识符")

        monkeypatch.setattr(registry.queryService.store, "query", boom)
        headers = auth_headers(make_token())
        resp = client.post("/api/v1/spaces/bad%20space/query", json={"nql": "MATCH (v) RETURN v"}, headers=headers)
        assert resp.status_code == 400


class TestMockStoreUnaffected:
    @pytest.mark.asyncio
    async def test_existing_mock_flows_pass(self, mock_store: MockGraphStore) -> None:
        from knowledge_engine.models.graph import GraphSchema

        await mock_store.create_space("kg", GraphSchema())
        await mock_store.insert_vertex("kg", "P", "a", {"name": "x"})
        v = await mock_store.get_vertex("kg", "a")
        assert v.id == "a"
