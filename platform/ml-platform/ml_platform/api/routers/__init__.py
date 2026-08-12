"""API 路由汇总."""

from ml_platform.api.routers import (
    deps,
    experiments,
    features,
    health,
    models,
    training,
)

__all__ = [
    "deps",
    "health",
    "experiments",
    "training",
    "models",
    "features",
]
