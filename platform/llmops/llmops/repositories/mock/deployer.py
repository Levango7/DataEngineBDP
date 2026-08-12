"""Mock 模型部署 - 状态机模拟部署流程.

状态转换：
    CREATING -> RUNNING（就绪）
    RUNNING -> STOPPING -> STOPPED（卸载）
    CREATING/RUNNING -> FAILED（异常）
"""

from __future__ import annotations

import uuid

from llmops.interfaces.deployer import ModelDeployer
from llmops.models.base import DeploymentStatus, utc_now
from llmops.models.deployment import (
    DeployConfig,
    Deployment,
    DeploymentStatusInfo,
)
from llmops.repositories import (
    DeploymentNotFoundError,
    DeploymentNotUndeployableError,
)


class MockModelDeployer(ModelDeployer):
    """内存态部署管理，状态机模拟."""

    def __init__(self) -> None:
        self._deployments: dict[str, Deployment] = {}

    # ---------- ModelDeployer ----------

    async def deploy_model(self, model_id: str, config: DeployConfig) -> str:
        deployment_id = str(uuid.uuid4())
        name = config.name or f"dep-{model_id[:8]}-{deployment_id[:8]}"
        deployment = Deployment(
            id=deployment_id,
            name=name,
            modelId=model_id,
            modelVersion=config.modelVersion or 1,
            config=config,
            status=DeploymentStatusInfo(
                status=DeploymentStatus.CREATING,
                readyReplica=0,
            ),
        )
        self._deployments[deployment_id] = deployment
        return deployment_id

    async def undeploy_model(self, deployment_id: str) -> None:
        dep = await self.get_deployment_status(deployment_id)
        if dep.status.status in {
            DeploymentStatus.STOPPED,
            DeploymentStatus.FAILED,
        }:
            raise DeploymentNotUndeployableError(deployment_id, dep.status.status)
        dep.status.status = DeploymentStatus.STOPPING
        dep.updatedAt = utc_now()

    async def get_deployment_status(self, deployment_id: str) -> Deployment:
        if deployment_id not in self._deployments:
            raise DeploymentNotFoundError(deployment_id)
        return self._deployments[deployment_id]

    async def list_deployments(self) -> list[Deployment]:
        return sorted(self._deployments.values(), key=lambda d: d.createdAt, reverse=True)

    # ---------- 状态机推进（测试与 Mock 模式专用） ----------

    async def advance(self, deployment_id: str) -> Deployment:
        """推进部署到下一就绪状态（Mock 专用）.

        转换规则：
            CREATING -> RUNNING（端点就绪，分配 URL）
            STOPPING -> STOPPED（卸载完成）
        """
        dep = await self.get_deployment_status(deployment_id)
        st = dep.status
        if st.status == DeploymentStatus.CREATING:
            st.status = DeploymentStatus.RUNNING
            st.readyReplica = dep.config.replica
            dep.endpointUrl = f"http://llmops-serving-{dep.id[:8]}:8000"
            dep.gatewayRoute = dep.name
            dep.startedAt = utc_now()
        elif st.status == DeploymentStatus.STOPPING:
            st.status = DeploymentStatus.STOPPED
            dep.stoppedAt = utc_now()
            dep.endpointUrl = None
        dep.updatedAt = utc_now()
        return dep

    async def mark_failed(self, deployment_id: str, error_message: str) -> Deployment:
        """标记部署失败（Mock 专用）."""
        dep = await self.get_deployment_status(deployment_id)
        dep.status.status = DeploymentStatus.FAILED
        dep.status.errorMessage = error_message
        dep.updatedAt = utc_now()
        return dep

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        self._deployments.clear()

    def __len__(self) -> int:
        return len(self._deployments)
