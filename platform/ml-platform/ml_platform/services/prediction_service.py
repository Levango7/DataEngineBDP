"""预测服务业务逻辑."""

from __future__ import annotations

from typing import Any

from ml_platform.interfaces.backend import MLBackend
from ml_platform.models import PredictionResult


class PredictionService:
    """预测服务（编排 MLBackend）."""

    def __init__(self, backend: MLBackend) -> None:
        self._backend = backend

    async def predict(self, modelId: str, data: dict[str, Any]) -> PredictionResult:
        """调用模型预测."""
        return await self._backend.predict(modelId, data)
