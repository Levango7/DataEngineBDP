"""ML Platform 仓储层异常定义."""
from __future__ import annotations


class MlPlatformError(Exception):
    """ML Platform 基础异常."""


class ModelNotFoundError(MlPlatformError):
    """模型不存在."""

    def __init__(self, modelId: str):
        self.modelId = modelId
        super().__init__(f"模型不存在: {modelId}")


class ModelAlreadyExistsError(MlPlatformError):
    """模型已存在（同名）."""

    def __init__(self, name: str):
        self.name = name
        super().__init__(f"模型已存在: {name}")


class TrainingJobNotFoundError(MlPlatformError):
    """训练任务不存在."""

    def __init__(self, jobId: str):
        self.jobId = jobId
        super().__init__(f"训练任务不存在: {jobId}")


class TrainingFailedError(MlPlatformError):
    """训练失败."""

    def __init__(self, reason: str):
        self.reason = reason
        super().__init__(f"训练失败: {reason}")


class ExperimentNotFoundError(MlPlatformError):
    """实验不存在."""

    def __init__(self, experimentId: str):
        self.experimentId = experimentId
        super().__init__(f"实验不存在: {experimentId}")


class ExperimentAlreadyExistsError(MlPlatformError):
    """实验已存在（同名）."""

    def __init__(self, name: str):
        self.name = name
        super().__init__(f"实验已存在: {name}")


class FeatureGroupNotFoundError(MlPlatformError):
    """特征组不存在."""

    def __init__(self, groupName: str):
        self.groupName = groupName
        super().__init__(f"特征组不存在: {groupName}")


class FeatureGroupAlreadyExistsError(MlPlatformError):
    """特征组已存在（同名）."""

    def __init__(self, name: str):
        self.name = name
        super().__init__(f"特征组已存在: {name}")


class EntityNotFoundError(MlPlatformError):
    """实体在特征组中不存在."""

    def __init__(self, groupName: str, entityId: str):
        self.groupName = groupName
        self.entityId = entityId
        super().__init__(f"实体不存在: {groupName}/{entityId}")


class ValidationError(MlPlatformError):
    """业务校验失败."""


class BackendUnavailableError(MlPlatformError):
    """后端不可用（如 sklearn 未安装）."""