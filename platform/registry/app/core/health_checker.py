"""健康检查器.

负责部署后模型可调用性验证：
- HTTP 探测推理服务端点
- 简单推理请求验证
- 延迟测量
"""

from __future__ import annotations

import logging
import time
from typing import Optional

import httpx

from app.models import DeploymentRecord, HealthCheckResult

logger = logging.getLogger(__name__)


class HealthChecker:
    """部署健康检查器."""

    def __init__(self, timeout: int = 10, mock_mode: bool = True):
        self.timeout = timeout
        self.mock_mode = mock_mode

    async def check(self, deployment: DeploymentRecord) -> HealthCheckResult:
        """检查部署健康状态.

        Args:
            deployment: 部署记录.

        Returns:
            健康检查结果.
        """
        if self.mock_mode:
            return HealthCheckResult(
                deploymentId=deployment.deploymentId,
                healthy=deployment.healthy,
                endpoint=deployment.endpoint,
                latencyMs=5.0,
            )

        if not deployment.endpoint:
            return HealthCheckResult(
                deploymentId=deployment.deploymentId,
                healthy=False,
                error="无 endpoint",
            )

        # 真实模式：HTTP 探测
        start = time.time()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                # 1. 探测 /health
                resp = await client.get(
                    deployment.endpoint.rstrip("/") + "/health"
                )
                if resp.status_code != 200:
                    return HealthCheckResult(
                        deploymentId=deployment.deploymentId,
                        healthy=False,
                        endpoint=deployment.endpoint,
                        error=f"健康检查返回 {resp.status_code}",
                    )

                # 2. 简单推理请求
                infer_resp = await client.post(
                    deployment.endpoint.rstrip("/") + "/v1/chat/completions",
                    json={
                        "model": deployment.modelName,
                        "messages": [{"role": "user", "content": "ping"}],
                        "max_tokens": 1,
                    },
                )
                latency = (time.time() - start) * 1000
                healthy = infer_resp.status_code == 200
                return HealthCheckResult(
                    deploymentId=deployment.deploymentId,
                    healthy=healthy,
                    endpoint=deployment.endpoint,
                    latencyMs=round(latency, 2),
                    error=None if healthy else (
                        f"推理请求返回 {infer_resp.status_code}"
                    ),
                )
        except Exception as e:  # noqa: BLE001
            logger.warning(f"健康检查异常: {e}")
            return HealthCheckResult(
                deploymentId=deployment.deploymentId,
                healthy=False,
                endpoint=deployment.endpoint,
                error=str(e),
            )

    def check_sync(self, deployment: DeploymentRecord) -> HealthCheckResult:
        """同步健康检查（用于非异步上下文）."""
        if self.mock_mode:
            return HealthCheckResult(
                deploymentId=deployment.deploymentId,
                healthy=deployment.healthy,
                endpoint=deployment.endpoint,
                latencyMs=5.0,
            )

        if not deployment.endpoint:
            return HealthCheckResult(
                deploymentId=deployment.deploymentId,
                healthy=False,
                error="无 endpoint",
            )

        start = time.time()
        try:
            with httpx.Client(timeout=self.timeout) as client:
                resp = client.get(
                    deployment.endpoint.rstrip("/") + "/health"
                )
                latency = (time.time() - start) * 1000
                return HealthCheckResult(
                    deploymentId=deployment.deploymentId,
                    healthy=resp.status_code == 200,
                    endpoint=deployment.endpoint,
                    latencyMs=round(latency, 2),
                )
        except Exception as e:  # noqa: BLE001
            return HealthCheckResult(
                deploymentId=deployment.deploymentId,
                healthy=False,
                endpoint=deployment.endpoint,
                error=str(e),
            )