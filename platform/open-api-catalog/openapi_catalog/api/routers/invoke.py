"""API 调用路由（鉴权 + 限流 + 计量 + 转发）.

对应详细设计 §7 接口契约：
    POST /api/l5/v1/apis/{apiId}/invoke { payload, headers } → { result }
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException
from openapi_catalog.api.routers.deps import get_registry, status_for_error
from openapi_catalog.models import CallResult
from openapi_catalog.repositories import CatalogError
from openapi_catalog.services.registry import ServiceRegistry
from pydantic import BaseModel, Field

router = APIRouter(prefix="/apis", tags=["invoke"])


class CallAPIRequest(BaseModel):
    """调用 API 请求."""

    payload: dict[str, Any] | None = Field(default=None, description="请求体")
    headers: dict[str, str] | None = Field(default=None, description="额外请求头")


@router.post(
    "/{api_id}/call",
    response_model=CallResult,
    summary="调用 API",
)
async def call_api(
    api_id: str,
    req: CallAPIRequest,
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
    x_api_secret: str | None = Header(default=None, alias="X-API-Secret"),
    authorization: str | None = Header(default=None),
    registry: ServiceRegistry = Depends(get_registry),
) -> CallResult:
    """调用 API（经网关：鉴权 → 限流 → 计量 → 转发）.

    鉴权方式：
    - X-API-Key + X-API-Secret headers（AK/SK 成对认证）
    - Authorization: Bearer <token> header（JWT 认证，从 token 提取 AK，仍需 X-API-Secret）
    """
    # 提取 access_key
    access_key = x_api_key
    if not access_key and authorization:
        # 支持 Bearer token 形式
        if authorization.startswith("Bearer "):
            access_key = authorization[7:]

    if not access_key:
        return CallResult(
            callId="",
            statusCode=401,
            latencyMs=0.0,
            error="缺少鉴权凭证: X-API-Key 或 Authorization",
        )

    if not x_api_secret:
        return CallResult(
            callId="",
            statusCode=401,
            latencyMs=0.0,
            error="缺少鉴权凭证: X-API-Secret",
        )

    try:
        return await registry.apiCallService.call_api(
            api_id=api_id,
            access_key=access_key,
            secret_key=x_api_secret,
            payload=req.payload,
            headers=req.headers,
        )
    except CatalogError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
