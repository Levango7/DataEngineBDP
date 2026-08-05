"""Mock 仓储实现 - 内存存储，用于开发与测试."""
from __future__ import annotations

import secrets
import threading
import time
from collections import defaultdict, deque
from datetime import datetime, timedelta

from openapi_catalog.models import (
    APIDefinition,
    APIFilter,
    APIStatus,
    APISubscription,
    SubscriptionFilter,
    SubscriptionStatus,
    CallMetric,
)
from openapi_catalog.repositories import (
    APIAlreadyExistsError,
    APINotFoundError,
    SubscriptionAlreadyExistsError,
    SubscriptionNotFoundError,
)


class MockCatalogStore:
    """内存存储实现（线程安全）."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._apis: dict[str, APIDefinition] = {}
        self._subscriptions: dict[str, APISubscription] = {}
        self._metrics: deque[CallMetric] = deque(maxlen=10000)

    # ---------- API ----------

    async def save_api(self, api: APIDefinition) -> APIDefinition:
        """保存 API 定义."""
        with self._lock:
            # 检查同名同版本
            for existing in self._apis.values():
                if (
                    existing.name == api.name
                    and existing.version == api.version
                    and existing.id != api.id
                ):
                    raise APIAlreadyExistsError(api.name, api.version)
            self._apis[api.id] = api
            return api

    async def get_api(self, api_id: str) -> APIDefinition:
        """获取 API."""
        with self._lock:
            if api_id not in self._apis:
                raise APINotFoundError(api_id)
            return self._apis[api_id]

    async def list_apis(self, filter_: APIFilter) -> list[APIDefinition]:
        """列出 API."""
        with self._lock:
            items = list(self._apis.values())

        result = []
        for api in items:
            if filter_.name and filter_.name.lower() not in api.name.lower():
                continue
            if filter_.category and api.category != filter_.category:
                continue
            if filter_.tag and filter_.tag not in api.tags:
                continue
            if filter_.status and api.status != filter_.status:
                continue
            if filter_.providerTenantId and api.providerTenantId != filter_.providerTenantId:
                continue
            if filter_.keyword:
                kw = filter_.keyword.lower()
                desc = (api.description or "").lower()
                if kw not in api.name.lower() and kw not in desc and not any(
                    kw in t.lower() for t in api.tags
                ):
                    continue
            result.append(api)

        # 排序：按创建时间倒序
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result[filter_.offset : filter_.offset + filter_.limit]

    async def delete_api(self, api_id: str) -> None:
        """删除 API."""
        with self._lock:
            if api_id not in self._apis:
                raise APINotFoundError(api_id)
            del self._apis[api_id]
            # 级联清理订阅
            to_remove = [
                sid
                for sid, sub in self._subscriptions.items()
                if sub.apiId == api_id
            ]
            for sid in to_remove:
                del self._subscriptions[sid]

    # ---------- Subscription ----------

    async def save_subscription(self, sub: APISubscription) -> APISubscription:
        """保存订阅."""
        with self._lock:
            # 检查重复订阅（仅当状态非 REJECTED/REVOKED 时）
            for existing in self._subscriptions.values():
                if (
                    existing.apiId == sub.apiId
                    and existing.subscriberId == sub.subscriberId
                    and existing.status
                    in (SubscriptionStatus.PENDING, SubscriptionStatus.ACTIVE, SubscriptionStatus.APPROVED)
                    and existing.id != sub.id
                ):
                    raise SubscriptionAlreadyExistsError(sub.apiId, sub.subscriberId)
            self._subscriptions[sub.id] = sub
            return sub

    async def get_subscription(self, subscription_id: str) -> APISubscription:
        """获取订阅."""
        with self._lock:
            if subscription_id not in self._subscriptions:
                raise SubscriptionNotFoundError(subscription_id)
            return self._subscriptions[subscription_id]

    async def list_subscriptions(
        self, filter_: SubscriptionFilter
    ) -> list[APISubscription]:
        """列出订阅."""
        with self._lock:
            items = list(self._subscriptions.values())

        result = []
        for sub in items:
            if filter_.apiId and sub.apiId != filter_.apiId:
                continue
            if filter_.subscriberId and sub.subscriberId != filter_.subscriberId:
                continue
            if filter_.subscriberTenantId and sub.subscriberTenantId != filter_.subscriberTenantId:
                continue
            if filter_.status and sub.status != filter_.status:
                continue
            result.append(sub)

        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result[filter_.offset : filter_.offset + filter_.limit]

    async def find_subscription_by_key(
        self, access_key: str
    ) -> APISubscription | None:
        """根据 Access Key 查找订阅."""
        with self._lock:
            for sub in self._subscriptions.values():
                if sub.accessKey == access_key:
                    return sub
        return None

    # ---------- Metrics ----------

    async def save_metric(self, metric: CallMetric) -> CallMetric:
        """保存调用计量."""
        with self._lock:
            self._metrics.append(metric)
            # 同步更新 API 聚合统计
            if metric.apiId in self._apis:
                api = self._apis[metric.apiId]
                api.callCount += 1
                api.totalLatencyMs += metric.latencyMs
                api.totalTrafficBytes += metric.requestBytes + metric.responseBytes
                if metric.statusCode >= 400:
                    api.errorCount += 1
            # 同步更新订阅统计
            for sub in self._subscriptions.values():
                if sub.id == metric.subscriptionId:
                    sub.callCount += 1
                    if metric.statusCode >= 400:
                        sub.errorCount += 1
                    sub.lastCalledAt = metric.timestamp.isoformat()
                    break
            return metric

    async def list_metrics(
        self,
        api_id: str,
        range_str: str = "7d",
        consumer_tenant_id: str | None = None,
    ) -> list[CallMetric]:
        """列出计量记录."""
        from openapi_catalog.models.base import utc_now
        # 解析时间范围
        now = utc_now()
        if range_str.endswith("h"):
            hours = int(range_str[:-1])
            since = now - timedelta(hours=hours)
        elif range_str.endswith("d"):
            days = int(range_str[:-1])
            since = now - timedelta(days=days)
        else:
            since = now - timedelta(days=7)

        with self._lock:
            return [
                m
                for m in self._metrics
                if m.apiId == api_id
                and m.timestamp >= since
                and (
                    consumer_tenant_id is None
                    or m.consumerTenantId == consumer_tenant_id
                )
            ]

    async def clear(self) -> None:
        """清空所有数据（测试用）."""
        with self._lock:
            self._apis.clear()
            self._subscriptions.clear()
            self._metrics.clear()


def generate_ak_sk() -> tuple[str, str]:
    """生成 AK/SK."""
    ak = "AK" + secrets.token_hex(12)
    sk = "SK" + secrets.token_hex(24)
    return ak, sk