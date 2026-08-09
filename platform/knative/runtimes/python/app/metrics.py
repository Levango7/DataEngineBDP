"""invocation 计量模块 · Python 运行时 · 数据引擎大数据平台 T025。

本模块负责按 tenant 隔离记录函数 invocation 计量：
    1. Prometheus 指标：invocation_count / invocation_duration_seconds（按 tenant 标签）；
    2. Loki 日志：每次调用输出结构化 JSON 日志，由 Promtail 采集写入 Loki；
    3. Pushgateway：可选，将指标推送到 Prometheus Pushgateway（适用于短生命周期 Pod）。

设计原则：
    - tenant 隔离：所有指标与日志均带 tenant 标签，支持按租户聚合查询；
    - 低开销：指标使用 prometheus_client 默认 Registry，日志走 stdout 不阻塞；
    - 幂等：warmup() 可多次调用。
"""

from __future__ import annotations

import json
import logging
import os
import time
from typing import Optional

try:
    from prometheus_client import (
        CollectorRegistry,
        Counter,
        Histogram,
        generate_latest,
        REGISTRY,
    )
    _HAS_PROMETHEUS = True
except ImportError:  # prometheus_client 未安装时降级
    _HAS_PROMETHEUS = False

logger = logging.getLogger("function-runtime.metrics")


class InvocationRecorder:
    """invocation 计量记录器。

    按 tenant / function / status 标签记录调用次数与延迟，
    并输出结构化 JSON 日志供 Promtail 采集写入 Loki。

    Attributes:
        runtime: 运行时名称（python/java/go），写入指标标签。
        pushgatewayUrl: Prometheus Pushgateway 地址，空字符串表示不推送。
    """

    def __init__(self, runtime: str, pushgatewayUrl: str = "") -> None:
        self.runtime = runtime
        self.pushgatewayUrl = pushgatewayUrl
        self._registry = REGISTRY if _HAS_PROMETHEUS else None

        if _HAS_PROMETHEUS:
            # invocation 总数：按 tenant / function / status 隔离
            self.invocationCount = Counter(
                "serverless_invocation_count",
                "Serverless 函数调用总次数",
                ["tenant", "runtime", "function", "status"],
                registry=self._registry,
            )
            # invocation 延迟分布
            self.invocationDuration = Histogram(
                "serverless_invocation_duration_seconds",
                "Serverless 函数调用延迟（秒）",
                ["tenant", "runtime", "function"],
                buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10),
                registry=self._registry,
            )
        else:
            self.invocationCount = None
            self.invocationDuration = None

    def warmup(self) -> None:
        """预热：初始化 Prometheus 客户端连接。

        多次调用幂等，仅首次有实际开销。
        """
        if _HAS_PROMETHEUS and self.pushgatewayUrl:
            try:
                from prometheus_client import push_to_gateway
                # 推送一个空指标建立连接（验证 Pushgateway 可达）
                push_to_gateway(
                    self.pushgatewayUrl,
                    job=f"serverless-{self.runtime}-warmup",
                    registry=self._registry,
                )
                logger.info("Pushgateway 预热完成: %s", self.pushgatewayUrl)
            except Exception as exc:
                logger.warning("Pushgateway 预热失败（降级为 exposition 模式）: %s", exc)

    def record(
        self,
        tenantId: str,
        functionName: str,
        status: str,
        duration: float,
    ) -> None:
        """记录一次 invocation。

        Args:
            tenantId: 租户 ID（用于隔离）。
            functionName: 函数名。
            status: 调用状态（success/error）。
            duration: 调用耗时秒数。
        """
        # 1. Prometheus 指标
        if _HAS_PROMETHEUS:
            self.invocationCount.labels(
                tenant=tenantId, runtime=self.runtime,
                function=functionName, status=status,
            ).inc()
            self.invocationDuration.labels(
                tenant=tenantId, runtime=self.runtime,
                function=functionName,
            ).observe(duration)

        # 2. Loki 日志：结构化 JSON，由 Promtail 采集
        #    包含 tenant 标签，支持 LogQL: {tenant="xxx"} |= "invocation"
        logEntry = {
            "type": "invocation",
            "tenant": tenantId,
            "runtime": self.runtime,
            "function": functionName,
            "status": status,
            "duration_seconds": round(duration, 6),
            "timestamp": time.time(),
        }
        print(json.dumps(logEntry, ensure_ascii=False), flush=True)

        # 3. 可选：推送到 Pushgateway（适用于短生命周期 Pod）
        if _HAS_PROMETHEUS and self.pushgatewayUrl:
            try:
                from prometheus_client import push_to_gateway
                push_to_gateway(
                    self.pushgatewayUrl,
                    job=f"serverless-{self.runtime}-{tenantId}",
                    registry=self._registry,
                )
            except Exception as exc:
                logger.debug("Pushgateway 推送失败: %s", exc)

    def expose(self) -> tuple[bytes, str]:
        """暴露 Prometheus 指标文本格式。

        Returns:
            (指标文本, Content-Type)
        """
        if _HAS_PROMETHEUS:
            return generate_latest(self._registry), "text/plain; version=0.0.4; charset=utf-8"
        return b"# prometheus_client not installed\n", "text/plain; charset=utf-8"


def init_recorder(runtime: str, pushgatewayUrl: str = "") -> InvocationRecorder:
    """初始化 InvocationRecorder（模块级工厂函数）。"""
    return InvocationRecorder(runtime=runtime, pushgatewayUrl=pushgatewayUrl)