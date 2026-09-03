"""请求依赖：鉴权上下文与租户裁决.

租户来源（设计 §3.3，M2 落地）：
    1. JWT claim ``tenantId``（平台标准 jwt_auth，生产 AUTH_MODE=jwt）
    2. ``X-Tenant-Id`` 头——仅 admin 角色允许覆盖（effectiveTenant 裁决，
       CONVENTIONS.md：头与 claim 不一致必须 403 的平台侧对应实现）
    3. AUTH_MODE=none 的匿名 admin（本地/测试）未带头时回退 default 租户

请求体中的任何租户/路径字段一律不作为租户来源。
"""

from __future__ import annotations

from typing import Optional

from fastapi import Depends, Header, HTTPException

from ..tenant import DEFAULT_TENANT_ID, TenantError, validate_tenant_id
from .jwt_auth import AuthContext, effectiveTenant, getAuthContext

__all__ = ["AuthContext", "getAuthContext", "getTenantId"]


def getTenantId(
    ctx: AuthContext = Depends(getAuthContext),
    x_tenant_id: Optional[str] = Header(default=None, alias="X-Tenant-Id"),
) -> str:
    """解析本次请求的租户 id；无法确定或非法时 403."""
    tenant = effectiveTenant(ctx, x_tenant_id)
    if not tenant:
        if ctx.role == "admin":
            tenant = DEFAULT_TENANT_ID
        else:
            raise HTTPException(
                status_code=403,
                detail="无法确定租户：token 无 tenantId 声明且未提供 X-Tenant-Id 头",
            )
    try:
        return validate_tenant_id(tenant)
    except TenantError as exc:
        raise HTTPException(status_code=403, detail=f"租户 id 非法: {exc}") from exc
