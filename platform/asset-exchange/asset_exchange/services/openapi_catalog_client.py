"""开放 API 目录客户端 - API 交付的真实凭证来源.

为什么需要它：交付方式里的 "API 交付"，其产物应当是**真的能用来调通接口的凭证**，
而不是一串自造的 `"ak-xxxx..."`。本仓里凭证的唯一权威来源是 open-api-catalog：
它在订阅被提供方审批通过时发放 AK/SK（services/subscription.py:72），
并把 accessKey 写入 APISIX 的 key-auth 插件（services/apisix_config.py:95）——
也就是说只有它发出来的 key 才能真正通过网关。历史实现直接拼字符串返回假 AK，
消费方拿到会 401，且交付状态还是 SUCCEEDED。

接口契约（open-api-catalog，默认端口 8090，前缀 /api/v1）：
    POST /api/v1/apis/{apiId}/subscribe     申请订阅（201，返回 APISubscription）
    GET  /api/v1/subscriptions              列出订阅（?apiId=&subscriberId=&subscriberTenantId=）
    GET  /api/v1/subscriptions/{id}         订阅详情（SubscriptionPublic：含 accessKey，secretKey 脱敏）

注意：secretKey 在响应层被有意脱敏，本客户端因此只取 accessKey；
调用签名所需的 SK 由消费方走带外通道获取，不在交付产物里回传。
"""

from __future__ import annotations

from dataclasses import dataclass
import logging
from typing import Any, Optional

import httpx

from asset_exchange.config.settings import Settings

logger = logging.getLogger(__name__)


class OpenApiCatalogError(Exception):
    """开放 API 目录调用异常."""

    def __init__(self, message: str, status_code: int = 500) -> None:
        super().__init__(message)
        self.status_code = status_code


@dataclass(frozen=True)
class ApiCredential:
    """API 交付凭证结果.

    Attributes:
        subscriptionId: API 目录侧订阅 ID（凭证引用，可据此复查/吊销）。
        status: 目录侧订阅状态（pending / active / ...）。
        accessKey: 真实 Access Key；提供方尚未审批时为 None。
        grantedQuota: 授予配额（次/分钟），0 表示未授予。
        invokePath: 被订阅 API 的调用路径（若目录侧返回）。
    """

    subscriptionId: str
    status: str
    accessKey: Optional[str]
    grantedQuota: int = 0
    invokePath: Optional[str] = None

    @property
    def issued(self) -> bool:
        """凭证是否已发放（决定交付能否置为成功）。"""
        return bool(self.accessKey)


class OpenApiCatalogClient:
    """开放 API 目录客户端（API 交付凭证来源）."""

    def __init__(self, settings: Settings) -> None:
        self._baseUrl = settings.openApiCatalogUrl.rstrip("/")
        self._timeout = settings.openApiCatalogTimeout

    # ---------- 对外主方法 ----------

    async def obtain_credential(
        self,
        api_id: str,
        subscriber_id: str,
        subscriber_tenant_id: Optional[str] = None,
        purpose: str = "数据资产订阅的 API 交付",
        quota_expect: int = 100,
        jwt_token: Optional[str] = None,
    ) -> ApiCredential:
        """取得该订阅方对该 API 的调用凭证.

        先查已有订阅（避免每次交付都新建一条），没有则提交订阅申请。
        未审批时 accessKey 为空——调用方必须据此把交付留在待凭证状态，
        不能伪造成功产物。

        Raises:
            OpenApiCatalogError: 目录服务不可用或返回非预期状态码。
        """
        existing = await self._find(api_id, subscriber_id, subscriber_tenant_id, jwt_token)
        if existing is None:
            existing = await self._subscribe(
                api_id,
                subscriber_id,
                subscriber_tenant_id or subscriber_id,
                purpose,
                quota_expect,
                jwt_token,
            )
        return self._to_credential(existing)

    # ---------- 内部调用 ----------

    async def _request(
        self,
        method: str,
        path: str,
        *,
        params: Optional[dict[str, Any]] = None,
        json_body: Optional[dict[str, Any]] = None,
        jwt_token: Optional[str] = None,
    ) -> Any:
        url = f"{self._baseUrl}{path}"
        headers = {"Content-Type": "application/json"}
        if jwt_token:
            headers["Authorization"] = f"Bearer {jwt_token}"
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                resp = await client.request(method, url, params=params, json=json_body, headers=headers)
        except httpx.HTTPError as exc:
            raise OpenApiCatalogError(f"开放 API 目录网络异常: {exc}") from exc

        if resp.status_code >= 400:
            raise OpenApiCatalogError(
                f"开放 API 目录返回 {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )
        return resp.json()

    async def _find(
        self,
        api_id: str,
        subscriber_id: str,
        subscriber_tenant_id: Optional[str],
        jwt_token: Optional[str],
    ) -> Optional[dict[str, Any]]:
        """按 apiId + 订阅方查已有订阅（幂等保护，避免重复申请）。"""
        params: dict[str, Any] = {"apiId": api_id, "subscriberId": subscriber_id}
        if subscriber_tenant_id:
            params["subscriberTenantId"] = subscriber_tenant_id
        rows = await self._request("GET", "/subscriptions", params=params, jwt_token=jwt_token)
        if isinstance(rows, list) and rows:
            return rows[0]
        return None

    async def _subscribe(
        self,
        api_id: str,
        subscriber_id: str,
        subscriber_tenant_id: str,
        purpose: str,
        quota_expect: int,
        jwt_token: Optional[str],
    ) -> dict[str, Any]:
        body = {
            "subscriberId": subscriber_id,
            "subscriberTenantId": subscriber_tenant_id,
            "purpose": purpose,
            "quotaExpect": quota_expect,
        }
        return await self._request("POST", f"/apis/{api_id}/subscribe", json_body=body, jwt_token=jwt_token)

    @staticmethod
    def _to_credential(sub: dict[str, Any]) -> ApiCredential:
        return ApiCredential(
            subscriptionId=str(sub.get("id") or ""),
            status=str(sub.get("status") or ""),
            accessKey=sub.get("accessKey"),
            grantedQuota=int(sub.get("grantedQuota") or 0),
            invokePath=sub.get("invokePath") or sub.get("apiPath"),
        )
