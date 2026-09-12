"""FinOps 计量导出服务 - 出账闭环分账环节.

出账闭环第三环节（分账/计量汇入）：open-api-catalog 将 AK/SK 计量数据
（API 调用量）聚合后汇入 finops，作为出账的输入数据。

数据流：open-api-catalog(计量汇入) → finops-billing(出账) → asset-exchange(结算)
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import httpx

from openapi_catalog.config.settings import Settings

logger = logging.getLogger(__name__)


class FinOpsUsageExporter:
    """FinOps 计量导出服务.

    将 open-api-catalog 的 API 调用量计量数据聚合后推送到 finops billing 服务，
    触发账单生成，使 API 调用成本纳入出账闭环。
    """

    def __init__(self, settings: Settings) -> None:
        self._finopsUrl = settings.finopsBillingUrl.rstrip("/")
        self._timeout = settings.finopsBillingTimeout

    async def exportUsage(
        self,
        tenantId: str,
        period: Optional[str] = None,
        start: Optional[datetime] = None,
        end: Optional[datetime] = None,
        usageData: Optional[list[dict[str, Any]]] = None,
        jwtToken: Optional[str] = None,
    ) -> dict[str, Any]:
        """导出 API 调用量计量数据到 finops.

        Args:
            tenantId: 租户 ID.
            period: 账期（如 2026-08），不传则从 start 推断.
            start: 采集窗口起始，不传则取账期首日.
            end: 采集窗口结束，不传则取账期次月首日.
            usageData: 聚合后的用量明细列表（每项含 resourceType/usage/unitPrice/amount）.
            jwtToken: 透传给 finops 的 JWT.

        Returns:
            finops 账单生成结果.

        Raises:
            FinOpsExportError: finops 服务返回非 2xx 或网络异常.
        """
        url = f"{self._finopsUrl}/generate"
        payload = {
            "billingPeriod": period,
            "start": start.isoformat() if start else None,
            "end": end.isoformat() if end else None,
            "overwrite": False,
        }
        headers = self._buildHeaders(jwtToken)

        logger.info(
            "导出 API 用量到 finops: tenant=%s, period=%s, items=%d",
            tenantId, period, len(usageData or []),
        )

        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                resp = await client.post(url, json=payload, headers=headers)
        except httpx.HTTPError as exc:
            raise FinOpsExportError(f"FinOps 服务网络异常: {exc}") from exc

        if resp.status_code not in (200, 201):
            raise FinOpsExportError(
                f"FinOps 服务返回 {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )

        result = resp.json()
        logger.info(
            "导出完成: tenant=%s, billingId=%s, totalAmount=%s",
            tenantId, result.get("id"), result.get("totalAmount"),
        )
        return result

    def _buildHeaders(self, jwtToken: Optional[str]) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if jwtToken:
            headers["Authorization"] = f"Bearer {jwtToken}"
        return headers


class FinOpsExportError(Exception):
    """FinOps 计量导出异常."""

    def __init__(self, message: str, status_code: int = 500) -> None:
        super().__init__(message)
        self.status_code = status_code