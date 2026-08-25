"""SQL 网关客户端单测."""

from __future__ import annotations

import httpx
from gateway_client import GatewayClient
from models import GatewayExecuteResult
import pytest


@pytest.mark.asyncio
class TestGatewayClient:
    async def test_execute_unreachable(self, gatewayClientUnreachable: GatewayClient) -> None:
        """网关不可达时返回结构化错误."""
        result = await gatewayClientUnreachable.execute("SELECT 1;")
        assert result.status == "UNREACHABLE"
        assert result.error is not None
        assert "网关不可达" in result.error or "网关" in result.error

    async def test_health_unreachable(self, gatewayClientUnreachable: GatewayClient) -> None:
        ok = await gatewayClientUnreachable.health()
        assert ok is False

    async def test_parse_response_success(self) -> None:
        data = {
            "queryId": "q-123",
            "status": "OK",
            "columns": ["id", "name"],
            "rows": [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}],
            "durationMs": 12.5,
            "engine": "trino",
        }
        result = GatewayClient._parseResponse(data)
        assert result.queryId == "q-123"
        assert result.status == "OK"
        assert result.columns == ["id", "name"]
        assert len(result.rows) == 2
        assert result.durationMs == 12.5
        assert result.engine == "trino"
        assert result.isSuccess

    async def test_parse_response_column_objects(self) -> None:
        data = {
            "status": "OK",
            "columns": [{"name": "id"}, {"name": "cnt"}],
            "rows": [],
        }
        result = GatewayClient._parseResponse(data)
        assert result.columns == ["id", "cnt"]

    async def test_parse_response_list_rows(self) -> None:
        data = {
            "status": "SIMULATED",
            "columns": ["id", "cnt"],
            "rows": [[1, 10], [2, 20]],
        }
        result = GatewayClient._parseResponse(data)
        assert len(result.rows) == 2
        assert result.rows[0] == {"id": 1, "cnt": 10}
        assert result.isSuccess

    async def test_parse_response_empty(self) -> None:
        result = GatewayClient._parseResponse({})
        assert result.status == "OK"
        assert result.columns == []
        assert result.rows == []

    async def test_result_is_success(self) -> None:
        r = GatewayExecuteResult(status="OK")
        assert r.isSuccess is True
        r2 = GatewayExecuteResult(status="ERROR", error="bad")
        assert r2.isSuccess is False
        r3 = GatewayExecuteResult(status="SIMULATED")
        assert r3.isSuccess is True


class _CapturePost:
    """拦截 httpx.AsyncClient.post，捕获请求 payload 供断言."""

    def __init__(self) -> None:
        self.payload: dict = {}

    async def __call__(self, url, **kwargs):
        self.payload = kwargs.get("json") or {}

        class _Resp:
            status_code = 200

            def raise_for_status(self) -> None:
                return None

            def json(self) -> dict:
                return {"status": "OK"}

        return _Resp()


@pytest.mark.asyncio
class TestLimitNoneVsZero:
    async def test_limit_none_falls_back_to_default(
        self, gatewayClient: GatewayClient, monkeypatch
    ) -> None:
        cap = _CapturePost()
        monkeypatch.setattr(httpx.AsyncClient, "post", cap)
        await gatewayClient.execute("SELECT 1;", limit=None)
        assert cap.payload["limit"] == gatewayClient.settings.defaultLimit

    async def test_limit_zero_preserved_not_swallowed(
        self, gatewayClient: GatewayClient, monkeypatch
    ) -> None:
        cap = _CapturePost()
        monkeypatch.setattr(httpx.AsyncClient, "post", cap)
        result = await gatewayClient.execute("SELECT 1;", limit=0)
        assert cap.payload["limit"] == 0
        assert gatewayClient.settings.defaultLimit != 0

    async def test_limit_one_passes_through(
        self, gatewayClient: GatewayClient, monkeypatch
    ) -> None:
        cap = _CapturePost()
        monkeypatch.setattr(httpx.AsyncClient, "post", cap)
        await gatewayClient.execute("SELECT 1;", limit=1)
        assert cap.payload["limit"] == 1
