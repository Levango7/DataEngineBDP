"""FinOps 计量导出服务测试.

验证出账闭环第三环节（计量汇入）：open-api-catalog 将 API 调用量计量数据
（usageData）实际传入 finops-billing /generate 的 payload，使计量数据被导出。

回归用例：修复前 usageData 仅在日志中引用，未传入 payload，导致计量数据丢失、
出账闭环断裂。
"""

from __future__ import annotations

from datetime import datetime, timezone

import httpx
import pytest

from openapi_catalog.config.settings import Settings
from openapi_catalog.services.finops_exporter import (
    FinOpsExportError,
    FinOpsUsageExporter,
)


def _make_exporter() -> FinOpsUsageExporter:
    settings = Settings(storeType="mock")
    return FinOpsUsageExporter(settings)


def _make_fake_client(captured: dict, *, status_code: int = 201, resp_body: dict | None = None):
    """构造 mock httpx.AsyncClient，捕获 post 请求参数."""

    class _Resp:
        def __init__(self) -> None:
            self.status_code = status_code
            self.text = '{"ok": true}'

        def json(self) -> dict:
            return resp_body or {"id": "bill-001", "totalAmount": 12.34}

    class _Client:
        def __init__(self, *a, **kw) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def post(self, url, json=None, headers=None):
            captured["url"] = url
            captured["json"] = json
            captured["headers"] = headers
            return _Resp()

    return _Client


# ---------- payload 包含 usageData（核心回归用例） ----------


@pytest.mark.asyncio
async def test_payload_contains_usage_data(monkeypatch):
    """usageData 必须传入 payload，使计量数据实际导出到 finops."""
    captured: dict = {}
    monkeypatch.setattr(httpx, "AsyncClient", _make_fake_client(captured))

    exporter = _make_exporter()
    usage = [
        {"apiId": "api-1", "callCount": 100, "totalCost": 1.5},
        {"apiId": "api-2", "callCount": 50, "totalCost": 0.8},
    ]

    await exporter.exportUsage(
        tenantId="tenant-a",
        period="2026-08",
        usageData=usage,
        jwtToken="fake-jwt",
    )

    payload = captured["json"]
    assert "usageData" in payload, "payload 必须包含 usageData 字段（计量数据实际导出）"
    assert payload["usageData"] == usage, "usageData 应原样传入 payload"


@pytest.mark.asyncio
async def test_payload_usage_data_defaults_to_empty_list(monkeypatch):
    """usageData=None 时 payload 中应为空列表，避免 null 语义歧义."""
    captured: dict = {}
    monkeypatch.setattr(httpx, "AsyncClient", _make_fake_client(captured))

    exporter = _make_exporter()
    await exporter.exportUsage(tenantId="tenant-a", period="2026-08")

    payload = captured["json"]
    assert payload["usageData"] == [], "usageData=None 应归一化为空列表"


@pytest.mark.asyncio
async def test_payload_excludes_tenant_id(monkeypatch):
    """tenantId 不应放入 payload：下游从 JWT 的 TenantContext 获取（不信任请求体）."""
    captured: dict = {}
    monkeypatch.setattr(httpx, "AsyncClient", _make_fake_client(captured))

    exporter = _make_exporter()
    await exporter.exportUsage(
        tenantId="tenant-secret",
        period="2026-08",
        usageData=[{"apiId": "x", "callCount": 1, "totalCost": 0.01}],
        jwtToken="fake-jwt",
    )

    payload = captured["json"]
    assert "tenantId" not in payload, "tenantId 不应放入 payload（下游从 JWT 获取，防越权）"


# ---------- 其他 payload 字段保持不变 ----------


@pytest.mark.asyncio
async def test_payload_preserves_other_fields(monkeypatch):
    """修复不应破坏既有字段：billingPeriod/start/end/overwrite."""
    captured: dict = {}
    monkeypatch.setattr(httpx, "AsyncClient", _make_fake_client(captured))

    exporter = _make_exporter()
    start = datetime(2026, 8, 1, tzinfo=timezone.utc)
    end = datetime(2026, 9, 1, tzinfo=timezone.utc)

    await exporter.exportUsage(
        tenantId="tenant-a",
        period="2026-08",
        start=start,
        end=end,
        usageData=[],
    )

    payload = captured["json"]
    assert payload["billingPeriod"] == "2026-08"
    assert payload["start"] == start.isoformat()
    assert payload["end"] == end.isoformat()
    assert payload["overwrite"] is False


# ---------- JWT 透传 ----------


@pytest.mark.asyncio
async def test_jwt_token_passed_in_header(monkeypatch):
    """JWT 须透传到 Authorization header，供下游解析 TenantContext."""
    captured: dict = {}
    monkeypatch.setattr(httpx, "AsyncClient", _make_fake_client(captured))

    exporter = _make_exporter()
    await exporter.exportUsage(
        tenantId="tenant-a",
        period="2026-08",
        usageData=[],
        jwtToken="my-jwt-token",
    )

    assert captured["headers"]["Authorization"] == "Bearer my-jwt-token"


@pytest.mark.asyncio
async def test_no_jwt_no_authorization_header(monkeypatch):
    """无 JWT 时不带 Authorization header."""
    captured: dict = {}
    monkeypatch.setattr(httpx, "AsyncClient", _make_fake_client(captured))

    exporter = _make_exporter()
    await exporter.exportUsage(tenantId="tenant-a", period="2026-08", usageData=[])

    assert "Authorization" not in captured["headers"]


# ---------- 响应解析与异常 ----------


@pytest.mark.asyncio
async def test_export_success_returns_result(monkeypatch):
    """成功响应应正确解析返回."""
    captured: dict = {}
    body = {"id": "bill-999", "totalAmount": 42.0, "status": "GENERATED"}
    monkeypatch.setattr(httpx, "AsyncClient", _make_fake_client(captured, resp_body=body))

    exporter = _make_exporter()
    result = await exporter.exportUsage(tenantId="tenant-a", period="2026-08", usageData=[])

    assert result["id"] == "bill-999"
    assert result["totalAmount"] == 42.0


@pytest.mark.asyncio
async def test_export_non_2xx_raises_error(monkeypatch):
    """finops 返回非 2xx 应抛 FinOpsExportError."""

    class _Resp:
        status_code = 500
        text = "internal error"

    class _Client:
        def __init__(self, *a, **kw) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def post(self, url, json=None, headers=None):
            return _Resp()

    monkeypatch.setattr(httpx, "AsyncClient", _Client)

    exporter = _make_exporter()
    with pytest.raises(FinOpsExportError) as exc_info:
        await exporter.exportUsage(tenantId="tenant-a", period="2026-08", usageData=[])

    assert exc_info.value.status_code == 500


@pytest.mark.asyncio
async def test_export_network_error_raises_error(monkeypatch):
    """网络异常应抛 FinOpsExportError."""

    class _Boom:
        def __init__(self, *a, **kw) -> None:
            raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(httpx, "AsyncClient", _Boom)

    exporter = _make_exporter()
    with pytest.raises(FinOpsExportError):
        await exporter.exportUsage(tenantId="tenant-a", period="2026-08", usageData=[])
