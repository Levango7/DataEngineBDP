"""API 调用服务 - 鉴权 + 限流 + 计量 + 转发.

对应详细设计 §5 调用链路与认证：
    认证方式：API Key（OAuth2 Client Credentials）或 JWT，均经 Keycloak 校验。
    网关拦截：APISIX 插件链依次做认证 → 租户隔离 → 限流 → 熔断 → 计量 → 转发。
    后端路由：按 API 配置路由到 Trino / Doris / 大模型 / 自定义函数。
    租户隔离：网关层强制注入 consumer_tenant_id，后端按此过滤，杜绝跨租户越权。
"""

from __future__ import annotations

import time
from typing import Any
import uuid

from openapi_catalog.models import (
    APIDefinition,
    APIStatus,
    CallResult,
)
from openapi_catalog.repositories import (
    APIStatusTransitionError,
    InvalidAPIKeyError,
    QuotaExceededError,
    RateLimitExceededError,
)
from openapi_catalog.repositories.mock import MockCatalogStore
from openapi_catalog.services.metering import MeteringService
from openapi_catalog.services.rate_limiter import RateLimiter
from openapi_catalog.services.subscription import SubscriptionService


class APICallService:
    """API 调用服务（鉴权 + 限流 + 计量 + 转发）."""

    def __init__(
        self,
        store: MockCatalogStore,
        subscription_service: SubscriptionService,
        rate_limiter: RateLimiter,
        metering_service: MeteringService,
    ) -> None:
        self.store = store
        self.subscriptionService = subscription_service
        self.rateLimiter = rate_limiter
        self.meteringService = metering_service

    async def call_api(
        self,
        api_id: str,
        access_key: str,
        secret_key: str | None = None,
        payload: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> CallResult:
        """调用 API（完整链路：鉴权 → 限流 → 转发 → 计量）.

        Args:
            api_id: API ID.
            access_key: Access Key（鉴权凭证）.
            secret_key: Secret Key（与 AK 成对鉴权）.
            payload: 请求体.
            headers: 请求头.

        Returns:
            调用结果.
        """
        call_id = str(uuid.uuid4())
        start_time = time.monotonic()

        # 1. 校验 API 存在且运行中
        api = await self.store.get_api(api_id)
        if api.status != APIStatus.RUNNING:
            raise APIStatusTransitionError(api_id, api.status.value, "call")

        # 2. 鉴权（认证 + 租户隔离）
        try:
            sub = await self.subscriptionService.authenticate(access_key, secret_key)
        except InvalidAPIKeyError:
            # 鉴权失败也记录计量
            latency = (time.monotonic() - start_time) * 1000
            await self.meteringService.record_call(
                api=api,
                subscription_id="unknown",
                consumer_tenant_id="unknown",
                latency_ms=latency,
                request_bytes=_estimate_size(payload) + _estimate_size(headers),
                response_bytes=0,
                status_code=401,
                error_message="鉴权失败: API Key 无效",
            )
            return CallResult(
                callId=call_id,
                statusCode=401,
                latencyMs=latency,
                error="鉴权失败: API Key 无效",
            )

        # 校验订阅属于该 API
        if sub.apiId != api_id:
            latency = (time.monotonic() - start_time) * 1000
            await self.meteringService.record_call(
                api=api,
                subscription_id=sub.id,
                consumer_tenant_id=sub.subscriberTenantId,
                latency_ms=latency,
                request_bytes=_estimate_size(payload) + _estimate_size(headers),
                response_bytes=0,
                status_code=403,
                error_message="订阅与 API 不匹配",
            )
            return CallResult(
                callId=call_id,
                statusCode=403,
                latencyMs=latency,
                error="订阅与 API 不匹配",
            )

        # 3. 限流（API 级 + 订阅级）
        try:
            self.rateLimiter.check_api(api_id)
            self.rateLimiter.check_subscription(sub.id)
        except (RateLimitExceededError, QuotaExceededError) as exc:
            latency = (time.monotonic() - start_time) * 1000
            await self.meteringService.record_call(
                api=api,
                subscription_id=sub.id,
                consumer_tenant_id=sub.subscriberTenantId,
                latency_ms=latency,
                request_bytes=_estimate_size(payload) + _estimate_size(headers),
                response_bytes=0,
                status_code=429,
                error_message=str(exc),
            )
            return CallResult(
                callId=call_id,
                statusCode=429,
                latencyMs=latency,
                error=str(exc),
            )

        # 4. 转发到后端（Mock 实现）
        result, status_code, error = await self._forward_to_upstream(api, payload, headers)

        # 5. 计量
        latency = (time.monotonic() - start_time) * 1000
        request_bytes = _estimate_size(payload) + _estimate_size(headers)
        response_bytes = _estimate_size(result)

        await self.meteringService.record_call(
            api=api,
            subscription_id=sub.id,
            consumer_tenant_id=sub.subscriberTenantId,
            latency_ms=latency,
            request_bytes=request_bytes,
            response_bytes=response_bytes,
            status_code=status_code,
            error_message=error,
        )

        # 6. 计算费用
        from openapi_catalog.models import CostStrategy

        if api.costStrategy == CostStrategy.BY_CALL:
            cost = api.costUnitPrice
        elif api.costStrategy == CostStrategy.BY_BYTES:
            cost = round(api.costUnitPrice * (request_bytes + response_bytes) / 1024.0, 6)
        else:
            cost = 0.0

        return CallResult(
            callId=call_id,
            statusCode=status_code,
            latencyMs=latency,
            result=result,
            error=error,
            costAmount=cost,
        )

    async def _forward_to_upstream(
        self,
        api: APIDefinition,
        payload: dict[str, Any] | None,
        headers: dict[str, str] | None,
    ) -> tuple[dict | None, int, str | None]:
        """转发到后端上游（Mock 实现）.

        真实环境会按 api.upstream.type 路由到 Trino/Doris/LLM/UDF/HTTP。
        此处返回 Mock 响应。
        """
        # 模拟后端处理延迟
        upstream_type = api.upstream.type
        # 不同类型后端模拟不同延迟
        if upstream_type == "llm":
            await _async_sleep(0.05)  # 大模型推理较慢
        elif upstream_type == "trino":
            await _async_sleep(0.02)  # Trino 查询
        elif upstream_type == "doris":
            await _async_sleep(0.01)  # Doris 在线查询
        else:
            await _async_sleep(0.005)

        # Mock 响应
        mock_result = {
            "apiId": api.id,
            "apiName": api.name,
            "version": api.version,
            "upstream": upstream_type,
            "payload": payload or {},
            "echo": True,
        }

        return mock_result, 200, None


def _estimate_size(obj: Any) -> int:
    """估算对象字节数."""
    if obj is None:
        return 0
    import json

    try:
        return len(json.dumps(obj, ensure_ascii=False, default=str).encode("utf-8"))
    except (TypeError, ValueError):
        return 0


async def _async_sleep(seconds: float) -> None:
    """异步 sleep."""
    import asyncio

    await asyncio.sleep(seconds)
