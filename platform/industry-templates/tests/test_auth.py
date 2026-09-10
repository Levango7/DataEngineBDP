"""鉴权测试：模板与分类路由需认证，部署需管理员权限.

覆盖：
    - 无 token 访问受保护端点 → 401
    - 无效 token → 401
    - 过期 token → 401
    - 有效 user token 读操作 → 200
    - user token 部署（写操作）→ 403
    - admin token 部署 → 201
    - 健康检查无需鉴权 → 200

来源：2026-09-10-jwt-auth-mirrored-file-cross-service-consistency-pattern
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

import pytest

# 测试用 HS256 密钥
SECRET = "test-secret-for-industry-templates-auth"


def _b64(obj) -> str:
    """JSON → 紧凑 base64url（无 padding）。"""
    return base64.urlsafe_b64encode(json.dumps(obj, separators=(",", ":")).encode()).rstrip(b"=").decode()


def make_token(
    *,
    sub: str = "u1",
    tenantId: str = "t1",
    role: str = "user",
    secret: str = SECRET,
    exp_delta: int = 3600,
) -> str:
    """生成 HS256 JWT（与 jwt_auth.py 校验逻辑对齐）。"""
    header = {"alg": "HS256", "typ": "JWT"}
    now = int(time.time())
    payload = {"sub": sub, "tenantId": tenantId, "role": role, "iat": now, "exp": now + exp_delta}
    signing_input = f"{_b64(header)}.{_b64(payload)}".encode()
    sig = hmac.new(secret.encode(), signing_input, hashlib.sha256).digest()
    return f"{signing_input.decode()}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"


@pytest.fixture
def auth_jwt(monkeypatch):
    """启用 JWT 强制鉴权模式（AUTH_MODE=jwt）。"""
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    # 清除 K8s 环境变量，避免 fail-fast
    monkeypatch.delenv("KUBERNETES_SERVICE_HOST", raising=False)
    return SECRET


def _bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


# ============================================================
# 无 token / 无效 token → 401
# ============================================================
def test_list_templates_no_token_401(auth_jwt, client):
    resp = client.get("/api/v1/templates")
    assert resp.status_code == 401


def test_get_template_no_token_401(auth_jwt, client):
    resp = client.get("/api/v1/templates/fin-risk-scorecard")
    assert resp.status_code == 401


def test_categories_no_token_401(auth_jwt, client):
    resp = client.get("/api/v1/templates/categories")
    assert resp.status_code == 401


def test_deploy_no_token_401(auth_jwt, client):
    resp = client.post(
        "/api/v1/templates/fin-risk-scorecard/deploy",
        json={"tenantId": "t1", "releaseName": "r1", "values": {}},
    )
    assert resp.status_code == 401


def test_list_templates_invalid_token_401(auth_jwt, client):
    resp = client.get("/api/v1/templates", headers=_bearer("invalid.token.here"))
    assert resp.status_code == 401


def test_list_templates_bad_signature_401(auth_jwt, client):
    # 用错误密钥签发 → 签名校验失败
    bad = make_token(secret="wrong-secret")
    resp = client.get("/api/v1/templates", headers=_bearer(bad))
    assert resp.status_code == 401


def test_expired_token_401(auth_jwt, client):
    resp = client.get("/api/v1/templates", headers=_bearer(make_token(exp_delta=-10)))
    assert resp.status_code == 401


# ============================================================
# 有效 token：读操作 → 200
# ============================================================
def test_list_templates_valid_user_200(auth_jwt, client):
    resp = client.get("/api/v1/templates", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 200


def test_get_template_valid_user_200(auth_jwt, client):
    resp = client.get("/api/v1/templates/fin-risk-scorecard", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 200


def test_preview_valid_user_200(auth_jwt, client):
    resp = client.get("/api/v1/templates/fin-risk-scorecard/preview", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 200


def test_categories_valid_user_200(auth_jwt, client):
    resp = client.get("/api/v1/templates/categories", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 200


def test_list_deployments_valid_user_200(auth_jwt, client):
    resp = client.get(
        "/api/v1/templates/fin-risk-scorecard/deployments",
        headers=_bearer(make_token(role="user")),
    )
    assert resp.status_code == 200


# ============================================================
# RBAC：部署（写操作）需 admin
# ============================================================
def test_deploy_user_role_403(auth_jwt, client):
    resp = client.post(
        "/api/v1/templates/fin-risk-scorecard/deploy",
        json={"tenantId": "t1", "releaseName": "r1", "values": {}},
        headers=_bearer(make_token(role="user")),
    )
    assert resp.status_code == 403


def test_deploy_admin_role_201(auth_jwt, client):
    resp = client.post(
        "/api/v1/templates/fin-risk-scorecard/deploy",
        json={
            "tenantId": "t1",
            "releaseName": "r1",
            "values": {
                "datasource.order_db": "jdbc:mysql://order:3306/order",
                "datasource.user_db": "jdbc:mysql://user:3306/user",
            },
        },
        headers=_bearer(make_token(role="admin")),
    )
    assert resp.status_code == 201, resp.text


# ============================================================
# 健康检查无需鉴权
# ============================================================
def test_health_no_auth_200(auth_jwt, client):
    resp = client.get("/api/v1/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"