"""限流服务 - 令牌桶 + 滑动窗口配额.

对应详细设计 §5 网关拦截：
    APISIX 插件链依次做认证 → 租户隔离 → 限流 → 熔断 → 计量 → 转发。
"""
from __future__ import annotations

import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass

from openapi_catalog.repositories import (
    QuotaExceededError,
    RateLimitExceededError,
)


@dataclass
class _TokenBucket:
    """令牌桶."""

    capacity: float        # 桶容量（最大突发）
    rate: float            # 令牌生成速率（个/秒）
    tokens: float = 0.0    # 当前令牌数
    lastRefill: float = 0.0  # 上次补充时间

    def __post_init__(self) -> None:
        if self.tokens == 0.0:
            self.tokens = self.capacity
        if self.lastRefill == 0.0:
            self.lastRefill = time.monotonic()

    def consume(self, n: int = 1) -> bool:
        """尝试消费 n 个令牌，返回是否成功."""
        now = time.monotonic()
        elapsed = now - self.lastRefill
        self.tokens = min(self.capacity, self.tokens + elapsed * self.rate)
        self.lastRefill = now
        if self.tokens >= n:
            self.tokens -= n
            return True
        return False


class RateLimiter:
    """限流器（线程安全）.

    - 按 API 限流（令牌桶，rate 次/秒）
    - 按订阅配额限流（滑动窗口，quota 次/分钟）
    """

    def __init__(self) -> None:
        self._lock = threading.RLock()
        # apiId -> 令牌桶
        self._apiBuckets: dict[str, _TokenBucket] = {}
        # subscriptionId -> 滑动窗口（最近 60s 的调用时间戳）
        self._subWindows: dict[str, deque[float]] = defaultdict(deque)
        # subscriptionId -> QPS 令牌桶（按秒限流）
        self._subBuckets: dict[str, _TokenBucket] = {}
        # 配置缓存
        self._apiLimits: dict[str, int] = {}
        self._subQuotas: dict[str, int] = {}

    def configure_api(self, api_id: str, rate_per_second: int) -> None:
        """配置 API 限流（次/秒）."""
        with self._lock:
            self._apiLimits[api_id] = rate_per_second
            self._apiBuckets[api_id] = _TokenBucket(
                capacity=float(rate_per_second),
                rate=float(rate_per_second),
            )

    def configure_subscription(
        self, subscription_id: str, quota_per_minute: int
    ) -> None:
        """配置订阅配额（次/分钟）."""
        with self._lock:
            self._subQuotas[subscription_id] = quota_per_minute

    def configure_subscription_rate(
        self, subscription_id: str, qps: int, burst: int = 0
    ) -> None:
        """配置订阅级 QPS 限流（次/秒，令牌桶）.

        Args:
            subscription_id: 订阅 ID.
            qps: 每秒请求数.
            burst: 突发容量，0 表示与 qps 相同.
        """
        with self._lock:
            capacity = float(burst) if burst > 0 else float(qps)
            self._subBuckets[subscription_id] = _TokenBucket(
                capacity=capacity,
                rate=float(qps),
            )

    def check_api(self, api_id: str) -> None:
        """检查 API 限流.

        Raises:
            RateLimitExceededError: 超出限流.
        """
        with self._lock:
            bucket = self._apiBuckets.get(api_id)
            if bucket is None:
                # 未配置限流，放行
                return
            if not bucket.consume(1):
                limit = self._apiLimits.get(api_id, 0)
                raise RateLimitExceededError(api_id, limit)

    def check_subscription(self, subscription_id: str) -> None:
        """检查订阅配额与 QPS 限流.

        Raises:
            RateLimitExceededError: 超出 QPS 限流.
            QuotaExceededError: 超出配额.
        """
        with self._lock:
            # 1. QPS 令牌桶检查（按秒限流）
            bucket = self._subBuckets.get(subscription_id)
            if bucket is not None and not bucket.consume(1):
                raise RateLimitExceededError(subscription_id, int(bucket.rate))

            # 2. 滑动窗口配额检查（按分钟限流）
            quota = self._subQuotas.get(subscription_id)
            if quota is None:
                return
            window = self._subWindows[subscription_id]
            now = time.monotonic()
            # 清理 60s 之前的记录
            cutoff = now - 60.0
            while window and window[0] < cutoff:
                window.popleft()
            if len(window) >= quota:
                raise QuotaExceededError(subscription_id, quota)
            window.append(now)

    def reset(self) -> None:
        """重置所有限流状态（测试用）."""
        with self._lock:
            self._apiBuckets.clear()
            self._subWindows.clear()
            self._subBuckets.clear()
            self._apiLimits.clear()
            self._subQuotas.clear()