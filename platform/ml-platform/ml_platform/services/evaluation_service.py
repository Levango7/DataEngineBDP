"""模型评估服务业务逻辑."""
from __future__ import annotations

from ml_platform.interfaces.backend import MLBackend
from ml_platform.models import EvalConfig, EvalResult


class EvaluationService:
    """模型评估服务（编排 MLBackend）."""

    def __init__(self, backend: MLBackend) -> None:
        self._backend = backend

    async def evaluate(
        self, modelId: str, evalConfig: EvalConfig
    ) -> EvalResult:
        """评估模型."""
        return await self._backend.evaluate(modelId, evalConfig)