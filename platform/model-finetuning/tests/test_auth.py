"""鉴权测试：finetune 任务端点需认证，创建/终止需管理员权限.

覆盖：
    - 无 token → 401
    - 无效 token → 401
    - 过期 token → 401
    - 有效 user token 读操作 → 200
    - user token 创建/终止（写操作）→ 403
    - admin token 创建 → 201
    - admin token 终止 → 200
    - 健康检查无需鉴权 → 200

来源：2026-09-10-jwt-auth-mirrored-file-cross-service-consistency-pattern
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import tempfile
import time
from pathlib import Path

# 隔离工作目录 + 强制 mock 模式（零外部依赖）
if "FINETUNE_WORK_DIR" not in os.environ:
    os.environ["FINETUNE_WORK_DIR"] = tempfile.mkdtemp(prefix="finetune-auth-")
os.environ.setdefault("FINETUNE_MOCK_MODE", "true")

_ROOT = str(Path(__file__).resolve().parents[1])
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from fastapi.testclient import TestClient  # noqa: E402
import main as finetune_main  # noqa: E402
import pytest  # noqa: E402

# 测试用 HS256 密钥
SECRET = "test-secret-for-finetune-auth"


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
    monkeypatch.delenv("KUBERNETES_SERVICE_HOST", raising=False)
    return SECRET


def _bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _client() -> TestClient:
    return TestClient(finetune_main.create_app())


def _task_body() -> dict:
    """最小合法 FinetuneTaskRequest body。"""
    return {
        "taskName": "auth-test-task",
        "baseModel": "meta-llama/Llama-2-7b-hf",
        "dataset": {"name": "demo-ds", "path": "/data/demo"},
        "config": {"method": "lora"},
        "tenantId": "tenant-auth",
    }


# ============================================================
# 无 token / 无效 token → 401
# ============================================================
def test_list_tasks_no_token_401(auth_jwt):
    resp = _client().get("/api/v1/finetune/tasks")
    assert resp.status_code == 401


def test_get_task_no_token_401(auth_jwt):
    resp = _client().get("/api/v1/finetune/tasks/nonexistent")
    assert resp.status_code == 401


def test_adapters_no_token_401(auth_jwt):
    resp = _client().get("/api/v1/finetune/adapters")
    assert resp.status_code == 401


def test_nodes_no_token_401(auth_jwt):
    resp = _client().get("/api/v1/finetune/nodes")
    assert resp.status_code == 401


def test_stats_no_token_401(auth_jwt):
    resp = _client().get("/api/v1/finetune/stats")
    assert resp.status_code == 401


def test_submit_no_token_401(auth_jwt):
    resp = _client().post("/api/v1/finetune/tasks", json=_task_body())
    assert resp.status_code == 401


def test_terminate_no_token_401(auth_jwt):
    resp = _client().delete("/api/v1/finetune/tasks/nonexistent")
    assert resp.status_code == 401


def test_logs_no_token_401(auth_jwt):
    resp = _client().get("/api/v1/finetune/tasks/nonexistent/logs")
    assert resp.status_code == 401


def test_list_tasks_invalid_token_401(auth_jwt):
    resp = _client().get("/api/v1/finetune/tasks", headers=_bearer("invalid.token.here"))
    assert resp.status_code == 401


def test_list_tasks_bad_signature_401(auth_jwt):
    bad = make_token(secret="wrong-secret")
    resp = _client().get("/api/v1/finetune/tasks", headers=_bearer(bad))
    assert resp.status_code == 401


def test_expired_token_401(auth_jwt):
    resp = _client().get("/api/v1/finetune/tasks", headers=_bearer(make_token(exp_delta=-10)))
    assert resp.status_code == 401


# ============================================================
# 有效 token：读操作 → 200
# ============================================================
def test_list_tasks_valid_user_200(auth_jwt):
    resp = _client().get("/api/v1/finetune/tasks", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 200


def test_adapters_valid_user_200(auth_jwt):
    resp = _client().get("/api/v1/finetune/adapters", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 200


def test_nodes_valid_user_200(auth_jwt):
    resp = _client().get("/api/v1/finetune/nodes", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 200


def test_stats_valid_user_200(auth_jwt):
    resp = _client().get("/api/v1/finetune/stats", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 200


# ============================================================
# RBAC：创建任务（写操作）需 admin
# ============================================================
def test_submit_user_role_403(auth_jwt):
    resp = _client().post("/api/v1/finetune/tasks", json=_task_body(), headers=_bearer(make_token(role="user")))
    assert resp.status_code == 403


def test_submit_admin_role_201(auth_jwt):
    resp = _client().post(
        "/api/v1/finetune/tasks", json=_task_body(), headers=_bearer(make_token(role="admin"))
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["taskName"] == "auth-test-task"


# ============================================================
# RBAC：终止任务（写操作）需 admin
# ============================================================
def test_terminate_user_role_403(auth_jwt):
    # 先用 admin 创建一个任务
    c = _client()
    created = c.post(
        "/api/v1/finetune/tasks", json=_task_body(), headers=_bearer(make_token(role="admin"))
    )
    assert created.status_code == 201, created.text
    task_id = created.json()["taskId"]
    # user 终止 → 403
    resp = c.delete(f"/api/v1/finetune/tasks/{task_id}", headers=_bearer(make_token(role="user")))
    assert resp.status_code == 403


def test_terminate_admin_role_200(auth_jwt):
    c = _client()
    created = c.post(
        "/api/v1/finetune/tasks", json=_task_body(), headers=_bearer(make_token(role="admin"))
    )
    assert created.status_code == 201, created.text
    task_id = created.json()["taskId"]
    resp = c.delete(f"/api/v1/finetune/tasks/{task_id}", headers=_bearer(make_token(role="admin")))
    assert resp.status_code == 200, resp.text


# ============================================================
# 健康检查无需鉴权
# ============================================================
def test_health_no_auth_200(auth_jwt):
    resp = _client().get("/api/v1/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"