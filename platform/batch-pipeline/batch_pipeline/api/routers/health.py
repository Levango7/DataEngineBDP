"""健康端点（CONVENTIONS.md：/health、/healthz、/readyz 鉴权匿名豁免）."""

from __future__ import annotations

from fastapi import APIRouter

from ...helpers import VERSION

router = APIRouter(tags=["health"])


@router.get("/health")
@router.get("/healthz")
@router.get("/readyz")
def health() -> dict[str, str]:
    return {"status": "UP", "service": "batch-pipeline", "version": VERSION}
