"""路由模块."""
from openapi_catalog.api.routers import (
    apis,
    health,
    invoke,
    metrics_docs,
    subscriptions,
)

__all__ = ["apis", "health", "invoke", "metrics_docs", "subscriptions"]