"""模型部署抽象接口（Model Deployer / Serving Endpoint）.

对齐设计：{ model, replica, gpu } → 部署端点（注册到 L4.5.6 大模型网关）
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from llmops.models.deployment import DeployConfig, Deployment


class ModelDeployer(ABC):
    """模型部署抽象接口.

    职责：部署、卸载、状态查询、列表。
    实现：MockModelDeployer（状态机模拟）/ 真实 K8s+vLLM 部署器。
    """

    @abstractmethod
    async def deploy_model(self, model_id: str, config: DeployConfig) -> str:
        """部署模型，返回 deployment_id.

        Args:
            model_id: 待部署模型 ID。
            config: 部署配置。

        Returns:
            部署 ID。
        """
        ...

    @abstractmethod
    async def undeploy_model(self, deployment_id: str) -> None:
        """卸载部署.

        Raises:
            DeploymentNotFoundError: 部署不存在。
        """
        ...

    @abstractmethod
    async def get_deployment_status(self, deployment_id: str) -> Deployment:
        """获取部署详情（含状态）.

        Raises:
            DeploymentNotFoundError: 部署不存在。
        """
        ...

    @abstractmethod
    async def list_deployments(self) -> list[Deployment]:
        """列出所有部署."""
        ...
