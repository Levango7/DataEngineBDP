"""MLflow SDK 客户端封装.

惰性导入 mlflow 包，避免在 Mock 模式下强制依赖 mlflow。
"""

from __future__ import annotations

from functools import lru_cache
from typing import Any, Optional

from llmops.repositories import StoreUnavailableError


def _import_mlflow() -> Any:
    """惰性导入 mlflow，未安装时抛 StoreUnavailableError."""
    try:
        import mlflow  # noqa: WPS433

        return mlflow
    except ImportError as exc:
        raise StoreUnavailableError("mlflow 未安装，请 pip install mlflow 或使用 LLMOPS_STORE_TYPE=mock") from exc


class MLflowClient:
    """MLflow 客户端封装，持有 tracking_uri 与 registry_uri."""

    def __init__(
        self,
        tracking_uri: str,
        registry_uri: Optional[str] = None,
    ) -> None:
        self.trackingUri = tracking_uri
        self.registryUri = registry_uri or tracking_uri
        self._mlflow = _import_mlflow()
        # 配置全局 URI
        self._mlflow.set_tracking_uri(self.trackingUri)
        self._mlflow.set_registry_uri(self.registryUri)

    @property
    def mlflow(self) -> Any:
        """返回 mlflow 模块."""
        return self._mlflow

    @property
    def client(self) -> Any:
        """返回 MlflowClient 实例."""
        return self._mlflow.tracking.MlflowClient(
            tracking_uri=self.trackingUri,
            registry_uri=self.registryUri,
        )

    @lru_cache(maxsize=1)
    def _registry_client(self) -> Any:
        return self._mlflow.tracking.MlflowClient(
            tracking_uri=self.trackingUri,
            registry_uri=self.registryUri,
        )
