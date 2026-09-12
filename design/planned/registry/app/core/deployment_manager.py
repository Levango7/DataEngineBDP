"""部署管理器.

负责管理模型部署到推理服务：
- 创建部署：启动 Docker 容器（简化实现）
- 查询部署状态
- 停止部署
- 更新部署

Mock 模式下不实际启动容器，仅记录部署元数据。
"""

from __future__ import annotations

from datetime import datetime, timezone
import logging
import threading
from typing import Optional
import uuid

from app.models import (
    DeploymentRecord,
    DeploymentStatus,
    DeployRequest,
)

logger = logging.getLogger(__name__)


class DeploymentManager:
    """部署管理器.

    线程安全：通过 _lock 保护部署字典。
    Mock 模式：不实际启动 Docker 容器。
    """

    def __init__(self, mock_mode: bool = True):
        self.mock_mode = mock_mode
        # deploymentId → DeploymentRecord
        self._deployments: dict[str, DeploymentRecord] = {}
        self._lock = threading.RLock()
        logger.info(f"DeploymentManager 初始化完成，mock_mode={mock_mode}")

    # ============================================================
    # 创建部署
    # ============================================================
    def deploy(self, request: DeployRequest) -> DeploymentRecord:
        """创建部署.

        Args:
            request: 部署请求.

        Returns:
            部署记录.
        """
        deployment_id = f"dep-{uuid.uuid4().hex[:12]}"
        record = DeploymentRecord(
            deploymentId=deployment_id,
            modelName=request.modelName,
            version=request.version,
            runtime=request.runtime,
            port=request.port,
            replicas=request.replicas,
            gpuCount=request.gpuCount,
            tenantId=request.tenantId,
            env=request.env,
        )

        with self._lock:
            self._deployments[deployment_id] = record

        if self.mock_mode:
            # Mock 模式：直接标记为 running（不实际启动容器），并显式标注 mock=true 供调用方识别
            record.status = DeploymentStatus.RUNNING
            record.endpoint = f"http://localhost:{request.port}"
            record.containerId = f"mock-container-{deployment_id}"
            record.healthy = True
            record.mock = True
            record.touch()
        else:
            # 真实模式：启动 Docker 容器
            try:
                self._start_container(record)
                record.status = DeploymentStatus.RUNNING
                record.healthy = True
                record.touch()
            except Exception as e:  # noqa: BLE001
                record.status = DeploymentStatus.FAILED
                record.error = str(e)
                record.finishedAt = datetime.now(timezone.utc)
                record.touch()
                logger.error(f"部署 {deployment_id} 启动失败: {e}")

        logger.info(f"部署已创建: id={deployment_id}, model={request.modelName}, " f"status={record.status}")
        return record

    def _start_container(self, record: DeploymentRecord) -> None:
        """启动 Docker 容器（真实模式）.

        简化实现：使用 docker run 启动 vllm/triton 容器。
        生产环境应使用 K8s Deployment 或 Triton InferenceServer。
        """
        import subprocess

        image_map = {
            "vllm": "vllm/vllm-openai:latest",
            "triton": "nvcr.io/nvidia/tritonserver:24.05-py3",
            "simple": "python:3.11-slim",
        }
        image = image_map.get(record.runtime, image_map["simple"])
        cmd = [
            "docker",
            "run",
            "-d",
            "--name",
            f"model-deploy-{record.deploymentId}",
            "-p",
            f"{record.port}:8000",
            "--gpus",
            f"device=all" if record.gpuCount > 0 else "none",
            "-e",
            f"MODEL_NAME={record.modelName}",
            "-e",
            f"MODEL_VERSION={record.version}",
            image,
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if result.returncode != 0:
            raise RuntimeError(f"docker run 失败: {result.stderr}")
        record.containerId = result.stdout.strip()
        record.endpoint = f"http://localhost:{record.port}"

    # ============================================================
    # 查询部署
    # ============================================================
    def get_deployment(
        self,
        deployment_id: str,
    ) -> Optional[DeploymentRecord]:
        """获取部署详情."""
        with self._lock:
            return self._deployments.get(deployment_id)

    def list_deployments(
        self,
        status: Optional[DeploymentStatus] = None,
        tenantId: Optional[str] = None,
    ) -> list[DeploymentRecord]:
        """查询部署列表."""
        with self._lock:
            deployments = list(self._deployments.values())
            if status is not None:
                deployments = [d for d in deployments if d.status == status]
            if tenantId is not None:
                deployments = [d for d in deployments if d.tenantId == tenantId]
            deployments.sort(key=lambda d: d.createdAt, reverse=True)
            return deployments

    # ============================================================
    # 停止部署
    # ============================================================
    def stop_deployment(
        self,
        deployment_id: str,
    ) -> Optional[DeploymentRecord]:
        """停止部署."""
        with self._lock:
            record = self._deployments.get(deployment_id)
            if record is None:
                return None
            if record.is_terminal():
                return record

        if not self.mock_mode and record.containerId:
            try:
                import subprocess

                subprocess.run(
                    ["docker", "stop", record.containerId],
                    capture_output=True,
                    timeout=30,
                )
            except Exception as e:  # noqa: BLE001
                logger.warning(f"停止容器失败: {e}")

        with self._lock:
            record.status = DeploymentStatus.STOPPED
            record.finishedAt = datetime.now(timezone.utc)
            record.healthy = False
            record.touch()
        logger.info(f"部署已停止: id={deployment_id}")
        return record

    # ============================================================
    # 更新部署
    # ============================================================
    def update_deployment(
        self,
        deployment_id: str,
        replicas: int = 0,
        gpu_count: int = 0,
    ) -> Optional[DeploymentRecord]:
        """更新部署配置（扩缩容）."""
        with self._lock:
            record = self._deployments.get(deployment_id)
            if record is None:
                return None
            record.status = DeploymentStatus.UPDATING
            record.touch()

        # 模拟更新过程
        if replicas > 0:
            record.replicas = replicas
        if gpu_count > 0:
            record.gpuCount = gpu_count

        with self._lock:
            record.status = DeploymentStatus.RUNNING
            record.touch()
        logger.info(f"部署已更新: id={deployment_id}, " f"replicas={record.replicas}, gpu={record.gpuCount}")
        return record

    # ============================================================
    # 删除部署记录
    # ============================================================
    def delete_deployment(
        self,
        deployment_id: str,
    ) -> bool:
        """删除部署记录."""
        with self._lock:
            if deployment_id not in self._deployments:
                return False
            record = self._deployments[deployment_id]
            if not record.is_terminal():
                # 先停止
                self.stop_deployment(deployment_id)
            del self._deployments[deployment_id]
        return True

    # ============================================================
    # 统计
    # ============================================================
    def stats(self) -> dict:
        """返回统计信息."""
        with self._lock:
            total = len(self._deployments)
            by_status: dict[str, int] = {}
            for d in self._deployments.values():
                key = d.status.value
                by_status[key] = by_status.get(key, 0) + 1
            return {
                "totalDeployments": total,
                "byStatus": by_status,
            }
