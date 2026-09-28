"""FinOps 账单客户端 - 出账闭环对接.

出账闭环第二环节（结算）：asset-exchange 调用 finops billing 服务获取租户账单，
基于账单金额执行结算，形成 出账 → 结算 闭环。

接口契约（对齐 finops-billing BillingController）：
    GET /api/finops/v1/billing/{id}   查询账单详情
    GET /api/finops/v1/billing        查询当前租户全部账单
"""

from __future__ import annotations

import logging
from typing import Any, Optional

import httpx

from asset_exchange.config.settings import Settings

logger = logging.getLogger(__name__)


class FinOpsBillingClient:
    """FinOps 账单服务客户端.

    封装对 finops-billing 服务 REST API 的调用，获取租户级成本账单。
    出账闭环中 asset-exchange 作为结算方，需拉取 finops 生成的账单作为结算依据。
    """

    def __init__(self, settings: Settings) -> None:
        self._baseUrl = settings.finopsBillingUrl.rstrip("/")
        self._timeout = settings.finopsBillingTimeout

    async def getBilling(self, billingId: str, jwtToken: Optional[str] = None) -> dict[str, Any]:
        """查询账单详情.

        Args:
            billingId: 账单 ID.
            jwtToken: 透传给 finops 的 JWT（租户隔离）.

        Returns:
            账单详情 dict（含 id/tenantId/billingPeriod/items/totalAmount/status）.

        Raises:
            FinOpsBillingError: finops 服务返回非 200 或网络异常.
        """
        url = f"{self._baseUrl}/{billingId}"
        headers = self._buildHeaders(jwtToken)
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                resp = await client.get(url, headers=headers)
        except httpx.HTTPError as exc:
            raise FinOpsBillingError(f"FinOps 账单服务网络异常: {exc}") from exc

        if resp.status_code == 404:
            raise FinOpsBillingError(f"账单不存在: {billingId}", status_code=404)
        if resp.status_code != 200:
            raise FinOpsBillingError(
                f"FinOps 账单服务返回 {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )
        return resp.json()

    async def listBillings(self, jwtToken: Optional[str] = None) -> dict[str, Any]:
        """查询当前租户全部账单.

        Returns:
            {"tenant": ..., "count": N, "bills": [...]}.
        """
        url = self._baseUrl
        headers = self._buildHeaders(jwtToken)
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                resp = await client.get(url, headers=headers)
        except httpx.HTTPError as exc:
            raise FinOpsBillingError(f"FinOps 账单服务网络异常: {exc}") from exc
        if resp.status_code != 200:
            raise FinOpsBillingError(
                f"FinOps 账单服务返回 {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )
        return resp.json()

    def _buildHeaders(self, jwtToken: Optional[str]) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if jwtToken:
            headers["Authorization"] = f"Bearer {jwtToken}"
        return headers


class FinOpsBillingError(Exception):
    """FinOps 账单服务异常."""

    def __init__(self, message: str, status_code: int = 500) -> None:
        super().__init__(message)
        self.status_code = status_code
