"""租户计量导出器 · 数擎大数据平台 T025.

本模块提供按 tenant 隔离的 invocation 计量聚合与导出功能：
    1. 从 Prometheus 查询各租户的 invocation 指标；
    2. 聚合为租户级计量数据（调用次数 / 延迟分位数 / 错误率）；
    3. 输出为 JSON 供计费系统（T0xx FinOps）消费。

查询 PromQL（按租户隔离）：
    - 调用次数：sum by (tenant) (increase(serverless_invocation_count[1h]))
    - P99 延迟：histogram_quantile(0.99, sum by (le, tenant) (rate(..._bucket[1h])))
    - 错误率：sum by (tenant) (rate(...{status="error"}[1h])) / sum by (tenant) (rate(...[1h]))
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import requests

logger = logging.getLogger(__name__)


@dataclass
class TenantMetric:
    """单个租户的计量数据."""

    tenantId: str
    invocationCount: int = 0
    errorCount: int = 0
    errorRate: float = 0.0
    p50Latency: float = 0.0
    p99Latency: float = 0.0
    avgLatency: float = 0.0
    runtimeBreakdown: Dict[str, int] = field(default_factory=dict)
    functionBreakdown: Dict[str, int] = field(default_factory=dict)
    windowSeconds: int = 3600
    timestamp: float = field(default_factory=time.time)


class TenantMetricsExporter:
    """租户计量导出器.

    从 Prometheus 查询 invocation 指标，按 tenant 聚合后输出。

    Attributes:
        prometheusUrl: Prometheus API 地址。
        windowSeconds: 聚合窗口（秒），默认 1 小时。
    """

    # PromQL 查询模板（按 tenant 隔离）
    _QUERY_INVOCATION_COUNT = (
        "sum by (tenant) (increase(serverless_invocation_count[{window}]))"
    )
    _QUERY_ERROR_COUNT = (
        "sum by (tenant) (increase(serverless_invocation_count{status=\"error\"}[{window}]))"
    )
    _QUERY_P50_LATENCY = (
        "histogram_quantile(0.50, "
        "sum by (le, tenant) (rate(serverless_invocation_duration_seconds_bucket[{window}])))"
    )
    _QUERY_P99_LATENCY = (
        "histogram_quantile(0.99, "
        "sum by (le, tenant) (rate(serverless_invocation_duration_seconds_bucket[{window}])))"
    )
    _QUERY_RUNTIME_BREAKDOWN = (
        "sum by (tenant, runtime) (increase(serverless_invocation_count[{window}]))"
    )
    _QUERY_FUNCTION_BREAKDOWN = (
        "sum by (tenant, function) (increase(serverless_invocation_count[{window}]))"
    )

    def __init__(
        self,
        prometheusUrl: str = "http://prometheus:9090",
        windowSeconds: int = 3600,
    ) -> None:
        self.prometheusUrl = prometheusUrl.rstrip("/")
        self.windowSeconds = windowSeconds

    def _query(self, promql: str) -> Dict[str, float]:
        """执行 PromQL 即时查询，返回 {label_tenant: value} 映射.

        Args:
            promql: PromQL 查询表达式。

        Returns:
            租户 ID 到指标值的映射。
        """
        url = f"{self.prometheusUrl}/api/v1/query"
        try:
            resp = requests.get(
                url,
                params={"query": promql},
                timeout=15,
            )
            resp.raise_for_status()
            data = resp.json()
        except (requests.RequestException, ValueError) as exc:
            logger.error("Prometheus 查询失败: %s | query=%s", exc, promql)
            return {}

        if data.get("status") != "success":
            logger.error("Prometheus 查询返回非 success: %s", data)
            return {}

        result: Dict[str, float] = {}
        for item in data.get("data", {}).get("result", []):
            tenant = item.get("metric", {}).get("tenant", "unknown")
            try:
                value = float(item.get("value", [0, "0"])[1])
            except (ValueError, IndexError):
                value = 0.0
            result[tenant] = value
        return result

    def _queryMultiLabel(self, promql: str, label: str) -> Dict[str, Dict[str, float]]:
        """执行 PromQL 查询，返回 {tenant: {label: value}} 映射."""
        url = f"{self.prometheusUrl}/api/v1/query"
        try:
            resp = requests.get(url, params={"query": promql}, timeout=15)
            resp.raise_for_status()
            data = resp.json()
        except (requests.RequestException, ValueError) as exc:
            logger.error("Prometheus 查询失败: %s | query=%s", exc, promql)
            return {}

        result: Dict[str, Dict[str, float]] = {}
        for item in data.get("data", {}).get("result", []):
            metric = item.get("metric", {})
            tenant = metric.get("tenant", "unknown")
            labelValue = metric.get(label, "unknown")
            try:
                value = float(item.get("value", [0, "0"])[1])
            except (ValueError, IndexError):
                value = 0.0
            result.setdefault(tenant, {})[labelValue] = value
        return result

    def collect(self) -> List[TenantMetric]:
        """采集所有租户的计量数据.

        Returns:
            租户计量数据列表。
        """
        window = f"{self.windowSeconds}s"
        windowStr = self._formatWindow(window)

        # 查询各指标
        invocationCounts = self._query(
            self._QUERY_INVOCATION_COUNT.format(window=windowStr)
        )
        errorCounts = self._query(
            self._QUERY_ERROR_COUNT.format(window=windowStr)
        )
        p50Latencies = self._query(
            self._QUERY_P50_LATENCY.format(window=windowStr)
        )
        p99Latencies = self._query(
            self._QUERY_P99_LATENCY.format(window=windowStr)
        )
        runtimeBreakdown = self._queryMultiLabel(
            self._QUERY_RUNTIME_BREAKDOWN.format(window=windowStr),
            "runtime",
        )
        functionBreakdown = self._queryMultiLabel(
            self._QUERY_FUNCTION_BREAKDOWN.format(window=windowStr),
            "function",
        )

        # 聚合为 TenantMetric
        allTenants = set(invocationCounts.keys()) | set(errorCounts.keys())
        metrics: List[TenantMetric] = []
        for tenant in allTenants:
            invCount = int(invocationCounts.get(tenant, 0))
            errCount = int(errorCounts.get(tenant, 0))
            errorRate = (errCount / invCount) if invCount > 0 else 0.0

            metric = TenantMetric(
                tenantId=tenant,
                invocationCount=invCount,
                errorCount=errCount,
                errorRate=errorRate,
                p50Latency=p50Latencies.get(tenant, 0.0),
                p99Latency=p99Latencies.get(tenant, 0.0),
                avgLatency=p50Latencies.get(tenant, 0.0),
                runtimeBreakdown={
                    k: int(v) for k, v in runtimeBreakdown.get(tenant, {}).items()
                },
                functionBreakdown={
                    k: int(v) for k, v in functionBreakdown.get(tenant, {}).items()
                },
                windowSeconds=self.windowSeconds,
            )
            metrics.append(metric)

        logger.info("采集到 %d 个租户的计量数据", len(metrics))
        return metrics

    @staticmethod
    def _formatWindow(window: str) -> str:
        """将秒数窗口格式化为 PromQL 时间区间字符串.

        Args:
            window: 形如 "3600s" 的窗口字符串。

        Returns:
            PromQL 兼容的时间区间，如 "1h"。
        """
        try:
            seconds = int(window.rstrip("s"))
        except ValueError:
            return window
        if seconds >= 3600 and seconds % 3600 == 0:
            return f"{seconds // 3600}h"
        if seconds >= 60 and seconds % 60 == 0:
            return f"{seconds // 60}m"
        return f"{seconds}s"

    def exportJson(self) -> List[Dict[str, Any]]:
        """采集并导出为 JSON 格式（供计费系统消费）."""
        metrics = self.collect()
        return [
            {
                "tenantId": m.tenantId,
                "invocationCount": m.invocationCount,
                "errorCount": m.errorCount,
                "errorRate": m.errorRate,
                "p50Latency": m.p50Latency,
                "p99Latency": m.p99Latency,
                "avgLatency": m.avgLatency,
                "runtimeBreakdown": m.runtimeBreakdown,
                "functionBreakdown": m.functionBreakdown,
                "windowSeconds": m.windowSeconds,
                "timestamp": m.timestamp,
            }
            for m in metrics
        ]