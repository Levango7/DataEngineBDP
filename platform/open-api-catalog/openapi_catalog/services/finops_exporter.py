"""FinOps 计量导出服务 - 出账闭环分账环节.

出账闭环第三环节（分账/计量汇入）：open-api-catalog 将 AK/SK 计量数据
（API 调用量）聚合后汇入 finops，作为出账的输入数据。

数据流：open-api-catalog(计量汇入) → finops-billing(出账) → asset-exchange(结算)
"""

from __future__ import annotations

from datetime import datetime
import logging
from typing import Any, Optional

import httpx

from openapi_catalog.config.settings import Settings

logger = logging.getLogger(__name__)

#: 下游 finops-billing 接受的计量项字段（Java UsageRecord 契约）。
#: 字段名漂移会被下游以 400 拒绝（@JsonIgnoreProperties(ignoreUnknown = false)），
#: 这是有意的：历史上字段不一致时记录被静默丢弃，导致出账金额恒为 0。
FIN_OPS_USAGE_RECORD_FIELDS = (
    "resourceType",
    "usage",
    "unitPrice",
    "amount",
    "namespace",
    "gpuModel",
    "sourceRef",
)


def toFinopsUsageRecords(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """把 API 计量聚合项映射为 finops-billing 的计量项契约.

    输入为 ``usage_export.UsageItem`` 的字典形式（apiId/apiName/callCount/totalCost/
    totalTrafficBytes）；输出字段与 Java ``UsageRecord`` 一一对应：

    - ``resourceType`` 固定 ``API_CALL``（API 调用量维度）
    - ``usage`` = 调用次数；``amount`` = 上游已计价金额（元，权威值，下游不二次计价）
    - ``unitPrice`` = amount / callCount（仅用于对账展示，4 位小数）
    - ``sourceRef`` = API ID（账单行可追溯回具体 API）

    ``apiName`` / ``totalTrafficBytes`` 不进入计费契约，仍保留在 /export-usage 的响应中。

    调用次数为 0 的项会被跳过（无用量不产生账单行，也避免除零）。
    """
    records: list[dict[str, Any]] = []
    for item in items:
        callCount = item.get("callCount") or 0
        if callCount <= 0:
            continue
        totalCost = float(item.get("totalCost") or 0.0)
        records.append(
            {
                "resourceType": "API_CALL",
                "usage": float(callCount),
                "unitPrice": round(totalCost / callCount, 4),
                "amount": round(totalCost, 4),
                "namespace": None,
                "gpuModel": None,
                "sourceRef": item.get("apiId"),
            }
        )
    return records


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
            usageData: 计量项列表，字段须符合 FIN_OPS_USAGE_RECORD_FIELDS
                （用 toFinopsUsageRecords 从 API 计量聚合项转换而来）.
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
            # 用量明细（API 调用量计量数据）传入 payload，使计量数据实际导出到 finops，
            # 闭合出账闭环（open-api-catalog 计量汇入 → finops-billing 出账）。
            # camelCase 与下游 finops-billing BillingGenerateRequest 字段风格对齐；
            # None 归一化为空列表，避免 null 语义歧义。
            # 注意：tenantId 不放入 payload，下游从 JWT 的 TenantContext 获取租户
            # （不信任请求体，防止跨租户越权出账）。
            "usageData": usageData or [],
        }
        headers = self._buildHeaders(jwtToken)

        logger.info(
            "导出 API 用量到 finops: tenant=%s, period=%s, items=%d",
            tenantId,
            period,
            len(usageData or []),
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
            tenantId,
            result.get("id"),
            result.get("totalAmount"),
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
