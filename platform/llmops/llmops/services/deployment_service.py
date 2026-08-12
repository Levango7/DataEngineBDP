"""部署管理业务逻辑."""

from __future__ import annotations

from typing import Optional

from llmops.interfaces.deployer import ModelDeployer
from llmops.interfaces.monitor import ModelMonitor
from llmops.interfaces.store import ModelStore
from llmops.models.base import ModelStatus
from llmops.models.deployment import DeployConfig, Deployment
from llmops.repositories import ModelNotFoundError


class DeploymentService:
    """部署管理服务（编排 Deployer + Store + Monitor）."""

    def __init__(
        self,
        deployer: ModelDeployer,
        store: ModelStore,
        monitor: Optional[ModelMonitor] = None,
    ) -> None:
        self._deployer = deployer
        self._store = store
        self._monitor = monitor

    async def deploy_model(self, model_id: str, config: DeployConfig) -> Deployment:
        # 业务校验：模型必须存在且有可用版本
        try:
            m = await self._store.get_model(model_id)
        except ModelNotFoundError:
            raise ValueError(f"模型不存在: {model_id}")
        if not m.versions:
            raise ValueError(f"模型 {model_id} 没有可用版本")
        # 若未指定版本，使用当前生产版本
        if config.modelVersion is None:
            if m.currentVersion is None:
                raise ValueError(f"模型 {model_id} 没有生产版本")
            config.modelVersion = m.currentVersion
        deployment_id = await self._deployer.deploy_model(model_id, config)
        # 注册到监控
        if self._monitor is not None and hasattr(self._monitor, "register_deployment"):
            self._monitor.register_deployment(deployment_id)
        # 更新模型状态
        await self._store.update_model(model_id, status=ModelStatus.DEPLOYED)
        return await self._deployer.get_deployment_status(deployment_id)

    async def undeploy_model(self, deployment_id: str) -> None:
        await self._deployer.undeploy_model(deployment_id)
        if self._monitor is not None and hasattr(self._monitor, "unregister_deployment"):
            self._monitor.unregister_deployment(deployment_id)

    async def get_deployment_status(self, deployment_id: str) -> Deployment:
        return await self._deployer.get_deployment_status(deployment_id)

    async def list_deployments(self) -> list[Deployment]:
        return await self._deployer.list_deployments()
