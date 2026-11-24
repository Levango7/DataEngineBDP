"""前端视图 PostgreSQL 持久化集成测试（A3）：必须连真实 PostgreSQL 才有意义。

本机不启 Docker；验证用 WSL 直装的 PostgreSQL 二进制，DSN 经 LLMOPS_TEST_POSTGRES_URL
注入。未配置时整文件 skip（CI 无 PG 服务时不动既有绿；真实链路在 A3 泳道单独跑）。

判据（对齐内存版契约，逐字段）：
- CRUD 形状与合并语义一致（upsert 字段级合并，不丢旧字段）；
- 重启持久性：新 store 实例 / 新 TestClient 应用读到同一份数据；
- 租户隔离：(tenant, id) 主键，跨租户不可见、不可改；
- 并发原子：并发 upsert 不丢字段、并发 scale 不出现半更新状态。
"""

from __future__ import annotations

import os
import threading
import uuid

import pytest
from fastapi.testclient import TestClient

from llmops.api.app import create_app
from llmops.config.settings import Settings
from llmops.services.postgres_frontend_store import PostgresFrontendContractStore

PG_URL = os.environ.get("LLMOPS_TEST_POSTGRES_URL", "").strip()
pytestmark = pytest.mark.skipif(not PG_URL, reason="需要真实 PostgreSQL：导出 LLMOPS_TEST_POSTGRES_URL 后运行")


def _settings() -> Settings:
    return Settings(
        storeType="mock",
        frontendStore="postgresql",
        frontendDatabaseUrl=PG_URL,
        environment="test",
    )


def _tenant(tag: str) -> str:
    return f"pgtest-{tag}-{uuid.uuid4().hex[:8]}"


def test_crud_and_merge_semantics_match_memory_contract() -> None:
    store = PostgresFrontendContractStore(PG_URL)
    try:
        tenant = _tenant("crud")
        # 评估指标：形状 + 精确过滤
        m = store.create_metric(tenant, {"modelName": "shuqing-7b", "evalType": "auto", "accuracy": 0.9})
        for key in ("id", "modelName", "evalType", "accuracy", "createdAt"):
            assert key in m
        assert [x["id"] for x in store.list_metrics(tenant)] == [m["id"]]
        assert [x["id"] for x in store.list_metrics(tenant, model_name="shuqing-7b")] == [m["id"]]
        assert store.list_metrics(tenant, model_name="no-such-model") == []

        # 微调视图：字段级合并，不丢旧字段
        v1 = store.upsert_finetune(tenant, "task-1", {"modelName": "ft", "status": "QUEUED", "progress": 0})
        v2 = store.upsert_finetune(tenant, "task-1", {"status": "RUNNING", "progress": 42})
        assert v2["progress"] == 42 and v2["status"] == "RUNNING"
        assert v2["modelName"] == "ft"  # 旧字段保留
        assert store.get_finetune(tenant, "task-1")["progress"] == 42
        assert store.get_finetune(tenant, "missing") is None
        assert [x["taskId"] for x in store.list_finetunes(tenant)] == ["task-1"]

        # 推理服务：deploy → scale → stop 全链（契约级状态机与内存版同形）
        svc = store.deploy_inference(tenant, {"modelName": "m1", "version": "v3", "replicas": 2, "serviceName": "svc"})
        assert svc["status"] == "RUNNING" and svc["replicas"] == 2 and svc["desiredReplicas"] == 2
        scaled = store.scale_inference(tenant, svc["id"], 5)
        assert scaled["replicas"] == 5 and scaled["desiredReplicas"] == 5
        zero = store.scale_inference(tenant, svc["id"], 0)
        assert zero["status"] == "STOPPED" and zero["replicas"] == 0
        stopped = store.stop_inference(tenant, svc["id"])
        assert stopped["status"] == "STOPPED" and stopped["desiredReplicas"] == 0
        assert store.scale_inference(tenant, "no-such-id", 1) is None
        assert store.stop_inference(tenant, "no-such-id") is None
        # 状态过滤大小写不敏感（与内存版 upper() 语义一致）
        assert [s["id"] for s in store.list_inference(tenant, status="running")] == []
        assert [s["id"] for s in store.list_inference(tenant, status="STOPPED")] == [svc["id"]]
    finally:
        store.close()


def test_data_survives_store_reopen() -> None:
    """重启持久性：关池后新实例（模拟进程重启）仍读到同一份数据."""
    tenant = _tenant("reopen")
    s1 = PostgresFrontendContractStore(PG_URL)
    m = s1.create_metric(tenant, {"modelName": "persist", "evalType": "auto"})
    f = s1.upsert_finetune(tenant, "task-r", {"modelName": "ft", "status": "DONE"})
    s1.close()

    s2 = PostgresFrontendContractStore(PG_URL)
    try:
        assert [x["id"] for x in s2.list_metrics(tenant)] == [m["id"]]
        assert s2.get_finetune(tenant, "task-r")["status"] == "DONE"
        assert f["taskId"] == s2.get_finetune(tenant, "task-r")["taskId"]
    finally:
        s2.close()


def test_two_app_instances_share_frontend_views(monkeypatch) -> None:
    """应用级验证：两个 TestClient 应用（模拟重启前后）共享同一 PostgreSQL 视图数据."""
    monkeypatch.setenv("AUTH_MODE", "none")
    app1 = create_app(settings=_settings())
    app2 = create_app(settings=_settings())
    try:
        c1 = TestClient(app1)
        c2 = TestClient(app2)
        r = c1.post(
            "/api/v1/llmops/eval-metrics",
            json={"modelName": "shared", "evalType": "auto", "accuracy": 0.81},
        )
        assert r.status_code == 201, r.text
        mid = r.json()["id"]

        r2 = c2.get("/api/v1/llmops/eval-metrics")
        assert r2.status_code == 200
        assert any(x["id"] == mid for x in r2.json()), "第二个应用实例未读到第一个实例写入的指标"
    finally:
        app1.state.frontend_store.close()
        app2.state.frontend_store.close()


def test_tenant_isolation() -> None:
    store = PostgresFrontendContractStore(PG_URL)
    try:
        a, b = _tenant("isolate-a"), _tenant("isolate-b")
        svc = store.deploy_inference(a, {"modelName": "m"})
        store.upsert_finetune(a, "task-a", {"status": "RUNNING"})
        store.create_metric(a, {"modelName": "m"})

        assert store.list_inference(b) == []
        assert store.list_finetunes(b) == []
        assert store.list_metrics(b) == []
        assert store.get_finetune(b, "task-a") is None
        assert store.scale_inference(b, svc["id"], 3) is None
        assert store.stop_inference(b, svc["id"]) is None
    finally:
        store.close()


def test_concurrent_upsert_and_scale_are_atomic() -> None:
    store = PostgresFrontendContractStore(PG_URL)
    try:
        tenant = _tenant("conc")
        task_id = "task-conc"
        errors: list[Exception] = []

        def upsert(i: int) -> None:
            try:
                for _ in range(5):
                    store.upsert_finetune(tenant, task_id, {f"key_{i}": i})
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)

        def scale(i: int) -> None:
            try:
                for _ in range(5):
                    store.scale_inference(tenant, svc_id, i % 4)
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)

        svc_id = store.deploy_inference(tenant, {"modelName": "m", "replicas": 1})["id"]
        threads = [threading.Thread(target=upsert, args=(i,)) for i in range(8)]
        threads += [threading.Thread(target=scale, args=(i,)) for i in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=60)

        assert not errors, f"并发写出现异常: {errors[:3]}"
        final = store.get_finetune(tenant, task_id)
        assert final is not None
        assert all(final.get(f"key_{i}") == i for i in range(8)), "并发 upsert 丢字段"

        inv = next(s for s in store.list_inference(tenant) if s["id"] == svc_id)
        assert inv["replicas"] == inv["desiredReplicas"]
        assert (inv["status"] == "STOPPED") == (inv["replicas"] == 0), "并发 scale 出现半更新状态"
    finally:
        store.close()
