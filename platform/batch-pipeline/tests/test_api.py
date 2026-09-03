"""M2 API 服务壳测试.

覆盖：health 匿名可达（含 jwt 模式）、AUTH_MODE=jwt 鉴权矩阵（401 / 租户裁决）、
提交-轮询-落盘 E2E（租户分区 + manifest tenant_id）、跨租户隔离、请求体覆盖
消毒（tenant / run_dir / storage 不可逃逸）、同 id 在途 409、磁盘 manifest 回读。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import tempfile
import time
import uuid
from typing import Any, Optional

import pytest
from fastapi.testclient import TestClient

import batch_pipeline.api.runner as runner_mod
from batch_pipeline.api.app import create_app
from batch_pipeline.api.runner import BatchRunner
from batch_pipeline.api.settings import Settings
from batch_pipeline.helpers import abs_path, csv_write, json_load, json_save

PREFIX = "/api/v1"
JWT_SECRET = "unit-test-secret"

# 与 conftest.py 同源的小数据（局部复制，避免测试模块间耦合）
SAMPLE_ORDERS: list[dict[str, str]] = [
    {
        "order_id": "ORD-00000001",
        "customer_id": "CUS-000001",
        "product_id": "PRD-000001",
        "order_date": "2026-01-15",
        "created_ts": "2026-01-15T10:00:00",
        "region": "华东",
        "channel": "web",
        "quantity": "5",
        "unit_price": "100.00",
        "status": "completed",
    },
    {
        "order_id": "ORD-00000002",
        "customer_id": "CUS-000002",
        "product_id": "PRD-000002",
        "order_date": "2026-02-20",
        "created_ts": "2026-02-20T11:30:00",
        "region": "华北",
        "channel": "app",
        "quantity": "3",
        "unit_price": "50.50",
        "status": "pending",
    },
    {
        "order_id": "ORD-00000003",
        "customer_id": "CUS-000001",
        "product_id": "PRD-000001",
        "order_date": "2026-03-10",
        "created_ts": "2026-03-10T09:15:00",
        "region": "华南",
        "channel": "store",
        "quantity": "10",
        "unit_price": "25.00",
        "status": "cancelled",
    },
]
SAMPLE_CUSTOMERS: list[dict[str, str]] = [
    {"customer_id": "CUS-000001", "tier": "gold", "city": "上海", "join_date": "2022-06-19"},
    {"customer_id": "CUS-000002", "tier": "silver", "city": "北京", "join_date": "2023-01-15"},
]
SAMPLE_PRODUCTS: list[dict[str, str]] = [
    {"product_id": "PRD-000001", "name": "数码-商品001", "category": "数码", "cost": "100.00"},
    {"product_id": "PRD-000002", "name": "服饰-商品002", "category": "服饰", "cost": "50.00"},
]
ORDER_FIELDS = list(SAMPLE_ORDERS[0].keys())
CUSTOMER_FIELDS = list(SAMPLE_CUSTOMERS[0].keys())
PRODUCT_FIELDS = list(SAMPLE_PRODUCTS[0].keys())


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _makeToken(
    secret: str,
    *,
    sub: str = "u1",
    tenant: str = "",
    role: str = "admin",
    exp: Optional[int] = None,
) -> str:
    """按平台 jwt_auth 的 HS256 格式伪造测试 token（与 Go 签发兼容）."""
    header = _b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload: dict[str, Any] = {
        "sub": sub,
        "role": role,
        "exp": exp if exp is not None else int(time.time()) + 600,
    }
    if tenant:
        payload["tenantId"] = tenant
    signing_input = f"{header}.{_b64url(json.dumps(payload).encode())}"
    sig = _b64url(hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest())
    return f"{signing_input}.{sig}"


def _waitTerminal(
    client: TestClient, batch_id: str, headers: Optional[dict] = None, timeout: float = 240.0
) -> dict[str, Any]:
    deadline = time.time() + timeout
    last: dict[str, Any] = {}
    while time.time() < deadline:
        r = client.get(f"{PREFIX}/batches/{batch_id}", headers=headers or {})
        assert r.status_code == 200, f"{r.status_code} {r.text}"
        last = r.json()
        if last.get("status") in ("success", "failed"):
            return last
        time.sleep(0.2)
    pytest.fail(f"批次 {batch_id} 在 {timeout}s 内未到达终态: {last}")


@pytest.fixture
def api_env(_same_drive_tmp_root, monkeypatch):
    """AUTH_MODE=none 的 API 测试环境：tmp 数据源 + tmp runRoot + 独立基础配置.

    run_dir 指向 tmp（同盘），批次落盘在 <tmp>/run/<tenant>/<batch>/ 下，与
    项目 ROOT/run 完全隔离，无需按 batch_id 前缀清理。
    """
    monkeypatch.setenv("AUTH_MODE", "none")
    work = tempfile.mkdtemp(prefix="batch_pipeline_api_", dir=_same_drive_tmp_root)
    data_dir = os.path.join(work, "data", "raw")
    csv_write(os.path.join(data_dir, "orders.csv"), ORDER_FIELDS, SAMPLE_ORDERS)
    csv_write(os.path.join(data_dir, "customers.csv"), CUSTOMER_FIELDS, SAMPLE_CUSTOMERS)
    csv_write(os.path.join(data_dir, "products.csv"), PRODUCT_FIELDS, SAMPLE_PRODUCTS)
    cfg = json_load(abs_path("config/pipeline_small.json"))
    cfg["source"]["files"] = {
        "orders": os.path.join(data_dir, "orders.csv"),
        "customers": os.path.join(data_dir, "customers.csv"),
        "products": os.path.join(data_dir, "products.csv"),
    }
    cfg["generator"]["enabled"] = False
    run_root = os.path.join(work, "run")
    cfg["pipeline"]["run_dir"] = run_root
    cfg["incremental"]["state_dir"] = os.path.join(work, "state")
    cfg_path = os.path.join(work, "pipeline_api.json")
    json_save(cfg_path, cfg)
    settings = Settings(runRoot=run_root, configPath=cfg_path)
    client = TestClient(create_app(settings, BatchRunner()))
    return {"client": client, "run_root": run_root, "work": work}


class TestHealth:
    def test_health_anonymous_even_in_jwt_mode(self, monkeypatch):
        """/health /healthz /readyz 不挂鉴权依赖：jwt 模式下匿名可达（探针约定）."""
        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", JWT_SECRET)
        settings = Settings(runRoot="run", configPath=abs_path("config/pipeline.json"))
        client = TestClient(create_app(settings, BatchRunner()))
        for path in ("/health", "/healthz", "/readyz"):
            r = client.get(PREFIX + path)
            assert r.status_code == 200, path
            body = r.json()
            assert body["status"] == "UP"
            assert body["service"] == "batch-pipeline"


class TestAuthJwt:
    @pytest.fixture
    def jwt_client(self, monkeypatch):
        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", JWT_SECRET)
        settings = Settings(runRoot="run", configPath=abs_path("config/pipeline.json"))
        return TestClient(create_app(settings, BatchRunner()))

    def test_missing_token_401(self, jwt_client):
        r = jwt_client.get(PREFIX + "/batches")
        assert r.status_code == 401

    def test_garbage_token_401(self, jwt_client):
        r = jwt_client.get(PREFIX + "/batches", headers={"Authorization": "Bearer not-a-jwt"})
        assert r.status_code == 401

    def test_wrong_secret_401(self, jwt_client):
        token = _makeToken("other-secret")
        r = jwt_client.get(PREFIX + "/batches", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 401

    def test_expired_token_401(self, jwt_client):
        token = _makeToken(JWT_SECRET, exp=int(time.time()) - 10)
        r = jwt_client.get(PREFIX + "/batches", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 401

    def test_admin_x_tenant_header_allowed(self, jwt_client):
        token = _makeToken(JWT_SECRET, tenant="tok-tenant", role="admin")
        r = jwt_client.get(
            PREFIX + "/batches",
            headers={"Authorization": f"Bearer {token}", "X-Tenant-Id": "acme"},
        )
        assert r.status_code == 200
        assert r.json()["tenant_id"] == "acme"

    def test_user_forced_to_token_claim(self, jwt_client):
        """普通用户带 X-Tenant-Id 也强制取 token 声明（越权裁决）."""
        token = _makeToken(JWT_SECRET, tenant="acme", role="user")
        r = jwt_client.get(
            PREFIX + "/batches",
            headers={"Authorization": f"Bearer {token}", "X-Tenant-Id": "other"},
        )
        assert r.status_code == 200
        assert r.json()["tenant_id"] == "acme"

    def test_user_without_tenant_claim_403(self, jwt_client):
        token = _makeToken(JWT_SECRET, tenant="", role="user")
        r = jwt_client.get(PREFIX + "/batches", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 403

    def test_invalid_tenant_id_403(self, jwt_client):
        token = _makeToken(JWT_SECRET, role="admin")
        for bad in ("ACME", "../evil", "a b"):
            r = jwt_client.get(
                PREFIX + "/batches",
                headers={"Authorization": f"Bearer {token}", "X-Tenant-Id": bad},
            )
            assert r.status_code == 403, bad


class TestSubmitE2E:
    def test_submit_poll_success_manifest_quality(self, api_env):
        """提交→轮询→success：manifest/metrics 落在 runRoot/<tenant>/<batch>/ 且带 tenant_id."""
        client = api_env["client"]
        run_root = api_env["run_root"]
        batch_id = "api-e2e-" + uuid.uuid4().hex[:6]
        r = client.post(PREFIX + "/batches", json={"batch_id": batch_id})
        assert r.status_code == 202
        body = r.json()
        assert body["batch_id"] == batch_id
        assert body["tenant_id"] == "default"
        assert body["status"] in ("queued", "running")

        final = _waitTerminal(client, batch_id)
        assert final["status"] == "success", final.get("error")
        assert len(final["stages"]) == 5
        assert all(s["status"] == "success" for s in final["stages"])

        batch_dir = os.path.join(run_root, "default", batch_id)
        manifest = json_load(os.path.join(batch_dir, "manifest.json"))
        assert manifest["status"] == "success"
        assert manifest["tenant_id"] == "default"
        metrics = json_load(os.path.join(batch_dir, "metrics.json"))
        assert metrics["tenant_id"] == "default"

        rq = client.get(f"{PREFIX}/batches/{batch_id}/quality")
        assert rq.status_code == 200
        assert isinstance(rq.json()["quality"], dict)

    def test_tenant_partition_and_isolation(self, api_env):
        """X-Tenant-Id 提交落 acme 分区；default 租户查不到（列表/状态/质量全 404 或为空）."""
        client = api_env["client"]
        run_root = api_env["run_root"]
        batch_id = "api-tnt-" + uuid.uuid4().hex[:6]
        acme_headers = {"X-Tenant-Id": "acme"}
        r = client.post(PREFIX + "/batches", json={"batch_id": batch_id}, headers=acme_headers)
        assert r.status_code == 202
        assert r.json()["tenant_id"] == "acme"

        final = _waitTerminal(client, batch_id, headers=acme_headers)
        assert final["status"] == "success", final.get("error")
        manifest = json_load(os.path.join(run_root, "acme", batch_id, "manifest.json"))
        assert manifest["tenant_id"] == "acme"

        # default 租户视角：列表不含、状态/质量 404
        r_list = client.get(PREFIX + "/batches")
        assert all(b["batch_id"] != batch_id for b in r_list.json()["batches"])
        assert client.get(f"{PREFIX}/batches/{batch_id}").status_code == 404
        assert client.get(f"{PREFIX}/batches/{batch_id}/quality").status_code == 404
        # 同租户可见
        assert client.get(f"{PREFIX}/batches/{batch_id}", headers=acme_headers).status_code == 200

    def test_request_body_cannot_escape_tenant_partition(self, api_env):
        """请求体的 tenant / run_dir / storage 覆盖被剔除：批次仍落服务端 acme 分区."""
        client = api_env["client"]
        run_root = api_env["run_root"]
        work = api_env["work"]
        hostile_run = os.path.join(work, "hostile-run")
        batch_id = "api-ovr-" + uuid.uuid4().hex[:6]
        acme_headers = {"X-Tenant-Id": "acme"}
        body = {
            "batch_id": batch_id,
            "config": {
                "tenant": {"enabled": True, "id": "evil"},
                "pipeline": {"run_dir": hostile_run},
                "storage": {"bucket": "evil-bucket", "prefix": "evil", "endpoint": "http://x:9000"},
                # 允许的业务字段确认消毒不误伤（一级合并生效）
                "openlineage": {"namespace": "biz-ns"},
            },
        }
        r = client.post(PREFIX + "/batches", json=body, headers=acme_headers)
        assert r.status_code == 202
        final = _waitTerminal(client, batch_id, headers=acme_headers)
        assert final["status"] == "success", final.get("error")

        manifest_path = os.path.join(run_root, "acme", batch_id, "manifest.json")
        assert os.path.isfile(manifest_path)
        manifest = json_load(manifest_path)
        assert manifest["tenant_id"] == "acme"
        assert not os.path.exists(hostile_run)

    def test_in_flight_same_id_conflict_409(self, api_env, monkeypatch):
        """同一 batch_id 在途重复提交 → 409；执行完成后允许复用 id."""
        real_run = runner_mod.run_pipeline

        def slow_run(cfg, batch_id, fail_at=""):
            time.sleep(2.0)
            return real_run(cfg, batch_id, fail_at)

        monkeypatch.setattr(runner_mod, "run_pipeline", slow_run)
        client = api_env["client"]
        batch_id = "api-dup-" + uuid.uuid4().hex[:6]
        assert client.post(PREFIX + "/batches", json={"batch_id": batch_id}).status_code == 202
        assert client.post(PREFIX + "/batches", json={"batch_id": batch_id}).status_code == 409
        final = _waitTerminal(client, batch_id)
        assert final["status"] == "success", final.get("error")

    def test_invalid_batch_id_400(self, api_env):
        client = api_env["client"]
        for bad in ("../evil", "..", "a/b", "a\\b"):
            r = client.post(PREFIX + "/batches", json={"batch_id": bad})
            assert r.status_code == 400, bad

    def test_unknown_batch_404(self, api_env):
        client = api_env["client"]
        assert client.get(PREFIX + "/batches/no-such-batch").status_code == 404
        assert client.get(PREFIX + "/batches/no-such-batch/quality").status_code == 404


class TestListAndDiskFallback:
    def test_disk_manifest_fallback_listing(self, api_env):
        """注册表为内存态：磁盘上已有 manifest 的历史批次也要出现在列表里."""
        client = api_env["client"]
        run_root = api_env["run_root"]
        batch_dir = os.path.join(run_root, "acme", "hist-001")
        os.makedirs(batch_dir)
        json_save(
            os.path.join(batch_dir, "manifest.json"),
            {
                "status": "success",
                "started_at": "2026-09-01T00:00:00",
                "finished_at": "2026-09-01T00:05:00",
            },
        )
        r = client.get(PREFIX + "/batches", headers={"X-Tenant-Id": "acme"})
        assert r.status_code == 200
        items = {b["batch_id"]: b for b in r.json()["batches"]}
        assert items["hist-001"]["status"] == "success"
        # 其他租户的磁盘批次不可见
        r_other = client.get(PREFIX + "/batches")
        assert all(b["batch_id"] != "hist-001" for b in r_other.json()["batches"])
