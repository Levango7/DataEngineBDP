"""Mock 仓储."""

from openapi_catalog.repositories.mock.store import (
    MockCatalogStore,
    generate_ak_sk,
)

__all__ = ["MockCatalogStore", "generate_ak_sk"]
