"""LLMOps 仓储层异常定义."""
from __future__ import annotations


class LlmopsError(Exception):
    """LLMOps 基础异常."""


class ModelNotFoundError(LlmopsError):
    """模型不存在."""

    def __init__(self, model_id: str):
        self.modelId = model_id
        super().__init__(f"模型不存在: {model_id}")


class VersionNotFoundError(LlmopsError):
    """模型版本不存在."""

    def __init__(self, model_id: str, version: int):
        self.modelId = model_id
        self.version = version
        super().__init__(f"模型版本不存在: {model_id}@v{version}")


class ModelAlreadyExistsError(LlmopsError):
    """模型已存在（同名）."""

    def __init__(self, name: str):
        self.name = name
        super().__init__(f"模型已存在: {name}")


class TrainingJobNotFoundError(LlmopsError):
    """训练任务不存在."""

    def __init__(self, job_id: str):
        self.jobId = job_id
        super().__init__(f"训练任务不存在: {job_id}")


class TrainingJobNotCancellableError(LlmopsError):
    """训练任务不可取消（已结束）."""

    def __init__(self, job_id: str, status: str):
        self.jobId = job_id
        self.status = status
        super().__init__(f"训练任务不可取消（当前状态 {status}）: {job_id}")


class TrainingJobNotFinishedError(LlmopsError):
    """训练任务尚未完成，无法评估."""

    def __init__(self, job_id: str, status: str):
        self.jobId = job_id
        self.status = status
        super().__init__(f"训练任务尚未完成（当前状态 {status}）: {job_id}")


class DeploymentNotFoundError(LlmopsError):
    """部署不存在."""

    def __init__(self, deployment_id: str):
        self.deploymentId = deployment_id
        super().__init__(f"部署不存在: {deployment_id}")


class DeploymentNotUndeployableError(LlmopsError):
    """部署不可卸载."""

    def __init__(self, deployment_id: str, status: str):
        self.deploymentId = deployment_id
        self.status = status
        super().__init__(f"部署不可卸载（当前状态 {status}）: {deployment_id}")


class ValidationError(LlmopsError):
    """业务校验失败."""


class StoreUnavailableError(LlmopsError):
    """存储后端不可用（如 MLflow 未配置）."""