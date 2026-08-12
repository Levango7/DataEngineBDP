"""MLflow 部署实现 - 骨架.

实际部署由封装层（L0.11）调度 K8s + vLLM/TGI 推理后端，
部署完成后注册到 L4.5.6 大模型网关。
本模块为骨架，提供接口签名。
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
from llmops.repositories.mlflow.client import MLflowClient


class MLflowModelDeployer(ModelDeployer):
    """基于 K8s + vLLM 的部署管理（骨架）."""

    def __init__(self, client: MLflowClient) -> None:
        self._client = client
        self._deployments: dict[str, Deployment] = {}

    async def deploy_model(self, model_id: str, config: DeployConfig) -> str:
        deployment_id = str(uuid.uuid4())
        name = config.name or f"dep-{model_id[:8]}-{deployment_id[:8]}"
        # 骨架：实际应通过 K8s API 创建 vLLM Deployment + Service + HPA
        # 并向 L4.5.6 大模型网关注册路由
        deployment = Deployment(
            id=deployment_id,
            name=name,
            modelId=model_id,
            modelVersion=config.modelVersion or 1,
            config=config,
            status=DeploymentStatusInfo(status=DeploymentStatus.CREATING, readyReplica=0),
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
        # 骨架：实际应通过 K8s API 删除 Deployment + Service
        dep.status.status = DeploymentStatus.STOPPING
        dep.updatedAt = utc_now()

    async def get_deployment_status(self, deployment_id: str) -> Deployment:
        if deployment_id not in self._deployments:
            raise DeploymentNotFoundError(deployment_id)
        # 骨架：可在此通过 K8s API 拉取实际副本状态
        return self._deployments[deployment_id]

    async def list_deployments(self) -> list[Deployment]:
        return sorted(self._deployments.values(), key=lambda d: d.createdAt, reverse=True)
