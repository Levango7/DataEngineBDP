"""MIRRORED FILE — 此文件在 llmops/ml-platform/nl2sql/evaluation/knowledge-engine/asset-exchange/open-api-catalog 七处保持逐字节一致。
修改任一副本必须同步其余六处，CI 由 scripts/check-mirrored-jwt-auth.sh 强制。

轻量 HS256 JWT 校验依赖（FastAPI），纯标准库实现，零第三方依赖，
与 Go 侧 golang-jwt/v5 HS256 签发格式兼容。

环境变量：
    AUTH_MODE            jwt=强制鉴权(生产)；none=放行并一次性告警(本地/测试)。缺省 none。
    JWT_SECRET           HS256 密钥，AUTH_MODE=jwt 时必填（首次请求时 fail-fast）
    JWT_EXPECTED_ISSUER  可选；设置后校验 iss claim 是否匹配

安全提示：在 K8s 环境（KUBERNETES_SERVICE_HOST 已设置）下若未显式设置 AUTH_MODE，
服务将拒绝启动（fail-fast）——生产部署必须显式 AUTH_MODE=jwt 并配置 JWT_SECRET；
仅限本地/测试（非 K8s）环境允许缺省 none 匿名放行。
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
import hashlib
import hmac
import json
import os
import sys
import time
from typing import Any, Optional

from fastapi import HTTPException, Request

_warnedOnce = False
_k8sFailfastShown = False


@dataclass(frozen=True)
class AuthContext:
    """鉴权通过后的请求身份上下文。"""

    userId: str
    tenantId: str
    role: str


def _b64urlDecode(segment: str) -> bytes:
    pad = "=" * (-len(segment) % 4)
    return base64.urlsafe_b64decode(segment + pad)


def _decodeHS256(token: str, secret: str) -> dict[str, Any]:
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("token 格式非法")
    signingInput = f"{parts[0]}.{parts[1]}".encode()
    expected = hmac.new(secret.encode(), signingInput, hashlib.sha256).digest()
    if not hmac.compare_digest(expected, _b64urlDecode(parts[2])):
        raise ValueError("签名校验失败")
    header = json.loads(_b64urlDecode(parts[0]))
    if header.get("alg") != "HS256":
        raise ValueError("仅接受 HS256")
    claims = json.loads(_b64urlDecode(parts[1]))
    exp = claims.get("exp")
    if isinstance(exp, (int, float)) and time.time() >= exp:
        raise ValueError("token 已过期")
    return claims


def loadAuthSettings() -> tuple[str, str, Optional[str]]:
    """返回 (mode, secret, expectedIssuer)。

    AUTH_MODE=jwt 而缺 JWT_SECRET 直接抛异常（fail-fast）。
    K8s 环境（KUBERNETES_SERVICE_HOST 已设置）下未显式设置 AUTH_MODE（缺省 none）时
    同样拒绝启动——生产集群内匿名 admin 放行属高危配置，必须显式 AUTH_MODE=jwt；
    仅限本地/测试（非 K8s）环境允许缺省 none。
    """
    mode = os.environ.get("AUTH_MODE", "none").strip().lower()
    secret = os.environ.get("JWT_SECRET", "")
    issuer = os.environ.get("JWT_EXPECTED_ISSUER") or None
    if mode not in ("jwt", "none"):
        raise RuntimeError(f"AUTH_MODE 非法: {mode}（仅支持 jwt|none）")
    if mode == "jwt" and not secret:
        raise RuntimeError("AUTH_MODE=jwt 必须配置 JWT_SECRET")
    if mode == "none" and os.environ.get("KUBERNETES_SERVICE_HOST") and "AUTH_MODE" not in os.environ:
        _failfastK8sAnonMode()
    return mode, secret, issuer


def _failfastK8sAnonMode() -> None:
    """K8s 环境 + 未显式 AUTH_MODE：拒绝启动（fail-fast），防止生产匿名 admin。"""
    global _k8sFailfastShown
    if not _k8sFailfastShown:
        _k8sFailfastShown = True
        print(
            "[ERROR][jwt_auth] 检测到 K8s 环境(KUBERNETES_SERVICE_HOST)但 AUTH_MODE 未显式设置："
            "接口将以匿名 admin 放行，属高危配置。生产部署必须设置 AUTH_MODE=jwt 并配置 JWT_SECRET；"
            "本地/测试（非 K8s）环境如需匿名放行请忽略此错误。",
            file=sys.stderr,
            flush=True,
        )
    raise RuntimeError(
        "AUTH_MODE 未显式设置且检测到 K8s 环境：拒绝以匿名 admin 模式启动。"
        "请设置 AUTH_MODE=jwt（生产）或显式 AUTH_MODE=none（仅限本地/测试）"
    )


def getAuthContext(request: Request) -> AuthContext:
    """FastAPI Depends 用：校验 Bearer token 并返回租户上下文。

    AUTH_MODE=none 时返回匿名 admin 上下文并仅在进程生命周期内告警一次，
    保持本地/测试旧行为；生产部署必须显式设置 AUTH_MODE=jwt。
    """
    global _warnedOnce
    mode, secret, expectedIssuer = loadAuthSettings()
    if mode == "none":
        if not _warnedOnce:
            print(
                "[WARN][jwt_auth] AUTH_MODE=none：接口处于匿名放行状态，仅限本地/测试环境",
                flush=True,
            )
            _warnedOnce = True
        return AuthContext(userId="anonymous", tenantId="", role="admin")

    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="缺少 Bearer token")
    try:
        claims = _decodeHS256(auth[len("Bearer ") :], secret)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail=str(exc)) from exc
    if expectedIssuer and claims.get("iss") != expectedIssuer:
        raise HTTPException(status_code=401, detail="issuer 不匹配")
    return AuthContext(
        userId=str(claims.get("sub", "")),
        tenantId=str(claims.get("tenantId", "")),
        role=str(claims.get("role", "user")),
    )


def requireAdmin(ctx: AuthContext) -> None:
    """角色门禁辅助：非 admin 抛 403。"""
    if ctx.role != "admin":
        raise HTTPException(status_code=403, detail="需要 admin 角色")


def effectiveTenant(ctx: AuthContext, requestedTenantId: Optional[str]) -> str:
    """租户来源裁决：admin 可指定任意租户，普通用户强制取 token 声明。"""
    if ctx.role == "admin" and requestedTenantId:
        return requestedTenantId
    return ctx.tenantId
