"""健康检查器单元测试.

覆盖 ``app.core.health_checker.HealthChecker`` 全部 public 方法：
- ``check``（async）：mock 模式 / 无 endpoint / 真实模式成功 / 健康检查非 200 / 推理请求非 200 / 异常
- ``check_sync``：mock 模式 / 无 endpoint / 真实模式成功 / 异常
"""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from app.core.health_checker import HealthChecker
from app.models import DeploymentRecord, DeploymentStatus


def _make_record(
    healthy: bool = True,
    endpoint: str | None = "http://localhost:8000",
) -> DeploymentRecord:
    """构造测试用部署记录."""
    return DeploymentRecord(
        deploymentId="dep-1",
        modelName="m",
        version="0.1",
        runtime="vllm",
        port=8000,
        replicas=1,
        gpuCount=1,
        tenantId="t",
        status=DeploymentStatus.RUNNING,
        endpoint=endpoint,
        healthy=healthy,
    )


# ============================================================
# check() 异步
# ============================================================
class TestCheckAsync:
    """HealthChecker.check 异步方法测试."""

    @pytest.mark.asyncio
    async def test_check_mock_mode_healthy(self):
        """mock 模式应返回部署的 healthy 标志与固定延迟."""
        checker = HealthChecker(mock_mode=True, timeout=5)
        rec = _make_record(healthy=True)
        result = await checker.check(rec)
        assert result.deploymentId == "dep-1"
        assert result.healthy is True
        assert result.latencyMs == 5.0
        assert result.endpoint == "http://localhost:8000"

    @pytest.mark.asyncio
    async def test_check_mock_mode_unhealthy(self):
        """mock 模式 unhealthy=False."""
        checker = HealthChecker(mock_mode=True)
        rec = _make_record(healthy=False)
        result = await checker.check(rec)
        assert result.healthy is False

    @pytest.mark.asyncio
    async def test_check_no_endpoint(self):
        """真实模式无 endpoint 应返回 unhealthy + 错误信息."""
        checker = HealthChecker(mock_mode=False)
        rec = _make_record(endpoint=None)
        result = await checker.check(rec)
        assert result.healthy is False
        assert result.error == "无 endpoint"

    @pytest.mark.asyncio
    async def test_check_real_mode_success(self):
        """真实模式 /health 与推理请求均 200 应判健康."""
        checker = HealthChecker(mock_mode=False, timeout=5)
        rec = _make_record()

        # 构造 mock AsyncClient
        mock_health_resp = MagicMock()
        mock_health_resp.status_code = 200
        mock_infer_resp = MagicMock()
        mock_infer_resp.status_code = 200

        mock_client = AsyncMock()
        mock_client.get = AsyncMock(return_value=mock_health_resp)
        mock_client.post = AsyncMock(return_value=mock_infer_resp)
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)

        with patch("httpx.AsyncClient", return_value=mock_client):
            result = await checker.check(rec)
        assert result.healthy is True
        assert result.endpoint == "http://localhost:8000"
        assert result.latencyMs >= 0

    @pytest.mark.asyncio
    async def test_check_real_mode_health_endpoint_non_200(self):
        """真实模式 /health 非 200 应判不健康."""
        checker = HealthChecker(mock_mode=False, timeout=5)
        rec = _make_record()

        mock_health_resp = MagicMock()
        mock_health_resp.status_code = 503

        mock_client = AsyncMock()
        mock_client.get = AsyncMock(return_value=mock_health_resp)
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)

        with patch("httpx.AsyncClient", return_value=mock_client):
            result = await checker.check(rec)
        assert result.healthy is False
        assert "503" in result.error

    @pytest.mark.asyncio
    async def test_check_real_mode_infer_non_200(self):
        """真实模式推理请求非 200 应判不健康."""
        checker = HealthChecker(mock_mode=False, timeout=5)
        rec = _make_record()

        mock_health_resp = MagicMock()
        mock_health_resp.status_code = 200
        mock_infer_resp = MagicMock()
        mock_infer_resp.status_code = 500

        mock_client = AsyncMock()
        mock_client.get = AsyncMock(return_value=mock_health_resp)
        mock_client.post = AsyncMock(return_value=mock_infer_resp)
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)

        with patch("httpx.AsyncClient", return_value=mock_client):
            result = await checker.check(rec)
        assert result.healthy is False
        assert "500" in result.error

    @pytest.mark.asyncio
    async def test_check_real_mode_exception(self):
        """真实模式网络异常应返回 unhealthy + 错误信息."""
        checker = HealthChecker(mock_mode=False, timeout=5)
        rec = _make_record()

        mock_client = AsyncMock()
        mock_client.get = AsyncMock(
            side_effect=httpx.ConnectError("connection refused")
        )
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)

        with patch("httpx.AsyncClient", return_value=mock_client):
            result = await checker.check(rec)
        assert result.healthy is False
        assert "connection refused" in result.error


# ============================================================
# check_sync() 同步
# ============================================================
class TestCheckSync:
    """HealthChecker.check_sync 同步方法测试."""

    def test_check_sync_mock_mode(self):
        """mock 模式同步检查."""
        checker = HealthChecker(mock_mode=True)
        rec = _make_record(healthy=True)
        result = checker.check_sync(rec)
        assert result.healthy is True
        assert result.latencyMs == 5.0

    def test_check_sync_no_endpoint(self):
        """真实模式无 endpoint."""
        checker = HealthChecker(mock_mode=False)
        rec = _make_record(endpoint=None)
        result = checker.check_sync(rec)
        assert result.healthy is False
        assert result.error == "无 endpoint"

    def test_check_sync_real_mode_success(self):
        """真实模式同步成功."""
        checker = HealthChecker(mock_mode=False, timeout=5)
        rec = _make_record()

        mock_resp = MagicMock()
        mock_resp.status_code = 200

        mock_client = MagicMock()
        mock_client.get = MagicMock(return_value=mock_resp)
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=None)

        with patch("httpx.Client", return_value=mock_client):
            result = checker.check_sync(rec)
        assert result.healthy is True
        assert result.latencyMs >= 0

    def test_check_sync_real_mode_non_200(self):
        """真实模式同步 /health 非 200."""
        checker = HealthChecker(mock_mode=False, timeout=5)
        rec = _make_record()

        mock_resp = MagicMock()
        mock_resp.status_code = 503

        mock_client = MagicMock()
        mock_client.get = MagicMock(return_value=mock_resp)
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=None)

        with patch("httpx.Client", return_value=mock_client):
            result = checker.check_sync(rec)
        assert result.healthy is False

    def test_check_sync_real_mode_exception(self):
        """真实模式同步异常."""
        checker = HealthChecker(mock_mode=False, timeout=5)
        rec = _make_record()

        mock_client = MagicMock()
        mock_client.get = MagicMock(
            side_effect=httpx.ConnectError("conn refused")
        )
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=None)

        with patch("httpx.Client", return_value=mock_client):
            result = checker.check_sync(rec)
        assert result.healthy is False
        assert "conn refused" in result.error


# ============================================================
# 构造与配置
# ============================================================
class TestHealthCheckerConfig:
    """HealthChecker 配置测试."""

    def test_default_config(self):
        """默认配置."""
        checker = HealthChecker()
        assert checker.timeout == 10
        assert checker.mock_mode is True

    def test_custom_config(self):
        """自定义配置."""
        checker = HealthChecker(timeout=30, mock_mode=False)
        assert checker.timeout == 30
        assert checker.mock_mode is False