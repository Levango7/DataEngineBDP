"""计量服务 - 调用计量记录与聚合.

对应详细设计 §6 计量计费：
    网关每次调用记录计量明细，按计费策略汇总到 §11.5，月度出账。
"""

from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timedelta
import re
import uuid

from openapi_catalog.models import (
    APIDefinition,
    APIMetrics,
    CallMetric,
    ConsumerMetrics,
    CostStrategy,
    MetricPoint,
)
from openapi_catalog.repositories import ValidationError
from openapi_catalog.repositories.mock import MockCatalogStore

# 合法 range 参数：<数字><s|m|h|d|w>
_RANGE_PATTERN = re.compile(r"^\d+[smhdw]$")
_RANGE_UNIT_SECONDS = {
    "s": 1,
    "m": 60,
    "h": 3600,
    "d": 86400,
    "w": 604800,
}


class MeteringService:
    """计量服务."""

    def __init__(self, store: MockCatalogStore) -> None:
        self.store = store

    async def record_call(
        self,
        api: APIDefinition,
        subscription_id: str,
        consumer_tenant_id: str,
        latency_ms: float,
        request_bytes: int,
        response_bytes: int,
        status_code: int,
        error_message: str | None = None,
    ) -> CallMetric:
        """记录一次调用计量.

        Args:
            api: API 定义.
            subscription_id: 订阅 ID.
            consumer_tenant_id: 消费者租户 ID.
            latency_ms: 延迟(ms).
            request_bytes: 请求字节数.
            response_bytes: 响应字节数.
            status_code: HTTP 状态码.
            error_message: 错误信息.

        Returns:
            计量记录.
        """
        cost = self._compute_cost(api, request_bytes, response_bytes)
        metric = CallMetric(
            callId=str(uuid.uuid4()),
            apiId=api.id,
            apiVersion=api.version,
            subscriptionId=subscription_id,
            consumerTenantId=consumer_tenant_id,
            providerTenantId=api.providerTenantId,
            latencyMs=latency_ms,
            requestBytes=request_bytes,
            responseBytes=response_bytes,
            statusCode=status_code,
            costStrategy=api.costStrategy,
            costAmount=cost,
            errorMessage=error_message,
        )
        return await self.store.save_metric(metric)

    def _compute_cost(
        self,
        api: APIDefinition,
        request_bytes: int,
        response_bytes: int,
    ) -> float:
        """计算单次调用费用.

        计费策略：
            - by_call: 每次固定单价
            - by_bytes: 按返回数据量计价（元/KB）
            - monthly_package: 包月配额内 0 元，超量走阶梯单价（此处简化为按次单价）
        """
        if api.costStrategy == CostStrategy.BY_CALL:
            return api.costUnitPrice
        elif api.costStrategy == CostStrategy.BY_BYTES:
            kb = (request_bytes + response_bytes) / 1024.0
            return round(api.costUnitPrice * kb, 6)
        elif api.costStrategy == CostStrategy.MONTHLY_PACKAGE:
            # 简化：包月配额内不计单次费用
            return 0.0
        return 0.0

    async def get_metrics(
        self,
        api_id: str,
        range_str: str = "7d",
        consumer_tenant_id: str | None = None,
    ) -> APIMetrics:
        """获取 API 聚合计量.

        Args:
            api_id: API ID.
            range_str: 时间范围（1h/24h/7d/30d）.
            consumer_tenant_id: 按消费者过滤.

        Returns:
            聚合计量.

        Raises:
            ValidationError: range 参数不合法.
        """
        if not _RANGE_PATTERN.fullmatch(range_str):
            raise ValidationError(f"无效的时间范围: {range_str}，须为 数字+单位（s/m/h/d/w），如 24h、7d")
        metrics = await self.store.list_metrics(api_id, range_str, consumer_tenant_id)

        if not metrics:
            return APIMetrics(apiId=api_id)

        call_count = len(metrics)
        error_count = sum(1 for m in metrics if m.statusCode >= 400)
        success_count = call_count - error_count
        total_latency = sum(m.latencyMs for m in metrics)
        avg_latency = total_latency / call_count if call_count else 0.0

        # P99 延迟
        latencies = sorted(m.latencyMs for m in metrics)
        p99_idx = max(0, int(len(latencies) * 0.99) - 1)
        p99_latency = latencies[p99_idx] if latencies else 0.0

        total_traffic = sum(m.requestBytes + m.responseBytes for m in metrics)
        total_cost = sum(m.costAmount for m in metrics)
        last_called = max(m.timestamp for m in metrics)

        # 按消费者聚合
        by_consumer_map: dict[str, ConsumerMetrics] = {}
        for m in metrics:
            key = m.consumerTenantId
            if key not in by_consumer_map:
                by_consumer_map[key] = ConsumerMetrics(
                    consumerTenantId=key,
                    subscriptionId=m.subscriptionId,
                )
            cm = by_consumer_map[key]
            cm.callCount += 1
            if m.statusCode >= 400:
                cm.errorCount += 1
            cm.totalCost += m.costAmount
        # 计算每个消费者的平均延迟
        consumer_latencies: dict[str, list[float]] = defaultdict(list)
        for m in metrics:
            consumer_latencies[m.consumerTenantId].append(m.latencyMs)
        for tenant, lats in consumer_latencies.items():
            if tenant in by_consumer_map:
                by_consumer_map[tenant].avgLatencyMs = sum(lats) / len(lats)

        # 时间序列（按小时聚合）
        timeseries = self._build_timeseries(metrics, range_str)

        return APIMetrics(
            apiId=api_id,
            callCount=call_count,
            successCount=success_count,
            errorCount=error_count,
            errorRate=error_count / call_count if call_count else 0.0,
            successRate=success_count / call_count if call_count else 1.0,
            avgLatencyMs=avg_latency,
            p99LatencyMs=p99_latency,
            totalTrafficBytes=total_traffic,
            totalCost=round(total_cost, 6),
            lastCalledAt=last_called,
            byConsumer=list(by_consumer_map.values()),
            timeseries=timeseries,
        )

    def _build_timeseries(self, metrics: list[CallMetric], range_str: str) -> list[MetricPoint]:
        """构建时间序列（按粒度聚合）."""
        if not metrics:
            return []

        from openapi_catalog.models.base import utc_now

        # 确定粒度
        if range_str.endswith("h"):
            hours = int(range_str[:-1])
            granularity = timedelta(minutes=10) if hours <= 1 else timedelta(hours=1)
        elif range_str.endswith("d"):
            days = int(range_str[:-1])
            granularity = timedelta(hours=1) if days <= 1 else timedelta(hours=6)
        else:
            granularity = timedelta(hours=1)

        now = utc_now()
        # 对齐到粒度边界
        start = now - self._parse_range(range_str)
        buckets: dict[datetime, list[CallMetric]] = defaultdict(list)
        for m in metrics:
            # 对齐到粒度
            delta = m.timestamp - start
            bucket_idx = int(delta.total_seconds() // granularity.total_seconds())
            bucket_time = start + bucket_idx * granularity
            buckets[bucket_time].append(m)

        result = []
        for ts in sorted(buckets.keys()):
            ms = buckets[ts]
            result.append(
                MetricPoint(
                    timestamp=ts,
                    callCount=len(ms),
                    errorCount=sum(1 for m in ms if m.statusCode >= 400),
                    avgLatencyMs=sum(m.latencyMs for m in ms) / len(ms),
                )
            )
        return result

    def _parse_range(self, range_str: str) -> timedelta:
        """解析时间范围字符串（非法输入回退为 7d）."""
        match = re.fullmatch(r"(\d+)([smhdw])", range_str)
        if match is None:
            return timedelta(days=7)
        return timedelta(seconds=int(match.group(1)) * _RANGE_UNIT_SECONDS[match.group(2)])
