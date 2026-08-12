"""API 端点测试."""

from __future__ import annotations

# ---------- health ----------


def test_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["store"] == "mock"


# ---------- models ----------


def _register_model(client, name="qiong-7B"):
    """注册基座模型辅助函数，返回 model_id."""
    resp = client.post(
        "/api/v1/models",
        json={
            "name": name,
            "type": "base",
            "params": {"paramSize": "7B", "architecture": "qwen2"},
            "description": "基座模型",
            "tags": {"provider": "shuqing"},
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def test_register_model(client):
    mid = _register_model(client)
    resp = client.get(f"/api/v1/models/{mid}")
    body = resp.json()
    assert body["name"] == "qiong-7B"
    assert body["type"] == "base"
    assert body["status"] == "draft"
    assert body["tags"]["provider"] == "shuqing"


def test_register_model_duplicate(client):
    """同名注册返回 409."""
    _register_model(client, name="dup")
    resp = client.post("/api/v1/models", json={"name": "dup", "type": "base"})
    assert resp.status_code == 409


def test_register_ft_without_base(client):
    """微调模型未指定 baseModelId 返回 422."""
    resp = client.post("/api/v1/models", json={"name": "ft-bad", "type": "ft"})
    assert resp.status_code == 422


def test_list_models(client):
    _register_model(client, name="m1")
    _register_model(client, name="m2")
    resp = client.get("/api/v1/models")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 2


def test_list_models_filter_by_type(client):
    base_id = _register_model(client, name="base-x")
    client.post(
        "/api/v1/models",
        json={"name": "ft-x", "type": "ft", "baseModelId": base_id},
    )
    resp = client.get("/api/v1/models?type=ft")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 1
    assert body[0]["name"] == "ft-x"


def test_get_model(client):
    mid = _register_model(client)
    resp = client.get(f"/api/v1/models/{mid}")
    assert resp.status_code == 200
    assert resp.json()["id"] == mid


def test_get_model_not_found(client):
    resp = client.get("/api/v1/models/nonexistent")
    assert resp.status_code == 404


def test_delete_model(client):
    mid = _register_model(client)
    resp = client.delete(f"/api/v1/models/{mid}")
    assert resp.status_code == 204
    # 二次获取应 404
    resp = client.get(f"/api/v1/models/{mid}")
    assert resp.status_code == 404


def test_get_model_versions_empty(client):
    mid = _register_model(client)
    resp = client.get(f"/api/v1/models/{mid}/versions")
    assert resp.status_code == 200
    assert resp.json() == []


# ---------- training ----------


def _setup_base(client, name="qiong-7B"):
    resp = client.post("/api/v1/models", json={"name": name, "type": "base"})
    assert resp.status_code == 201
    return resp.json()["id"]


def _create_training_job(client):
    base_id = _setup_base(client)
    resp = client.post(
        "/api/v1/training/jobs",
        json={
            "baseModelId": base_id,
            "outputModelName": "ft-1",
            "dataset": "ds-1",
            "epochs": 2,
            "gpu": 1,
            "learningRate": 0.0001,
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def test_create_training_job(client):
    job_id = _create_training_job(client)
    resp = client.get(f"/api/v1/training/jobs/{job_id}")
    body = resp.json()
    assert body["status"]["status"] == "pending"


def test_create_training_invalid_base(client):
    resp = client.post(
        "/api/v1/training/jobs",
        json={
            "baseModelId": "nonexistent",
            "outputModelName": "ft-1",
            "dataset": "ds-1",
        },
    )
    assert resp.status_code == 422


def test_get_training_status(client):
    job_id = _create_training_job(client)
    resp = client.get(f"/api/v1/training/jobs/{job_id}")
    assert resp.status_code == 200
    assert resp.json()["id"] == job_id


def test_get_training_status_not_found(client):
    resp = client.get("/api/v1/training/jobs/nonexistent")
    assert resp.status_code == 404


def test_list_training_jobs(client):
    _create_training_job(client)
    resp = client.get("/api/v1/training/jobs")
    assert resp.status_code == 200
    assert len(resp.json()) >= 1


def test_cancel_training(client):
    job_id = _create_training_job(client)
    resp = client.delete(f"/api/v1/training/jobs/{job_id}")
    assert resp.status_code == 204
    body = client.get(f"/api/v1/training/jobs/{job_id}").json()
    assert body["status"]["status"] == "cancelled"


def test_evaluate_unfinished(client):
    """未完成的训练不可评估."""
    job_id = _create_training_job(client)
    resp = client.get(f"/api/v1/training/jobs/{job_id}/eval")
    assert resp.status_code == 409


# ---------- deployments ----------


def _setup_deployable_model(client, name="qiong-7B"):
    """注册一个带版本的模型，返回 model_id."""
    mid = _setup_base(client, name)
    # 通过 store 直接添加版本（API 未暴露 add version 端点）
    # 这里通过训练完成注册版本的方式：略，直接用 service
    # 测试中通过 client.app.state 访问 registry
    import asyncio

    from llmops.models.model import ModelVersion

    registry = client.app.state.registry
    asyncio.run(registry.store.add_model_version(mid, ModelVersion(version=1, modelId=mid)))
    return mid


def _deploy_model(client):
    mid = _setup_deployable_model(client)
    resp = client.post(
        "/api/v1/deployments",
        json={"modelId": mid, "modelVersion": 1, "replica": 2, "gpu": 1},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def test_deploy_model(client):
    dep_id = _deploy_model(client)
    resp = client.get(f"/api/v1/deployments/{dep_id}")
    body = resp.json()
    assert body["status"]["status"] == "creating"
    assert body["config"]["replica"] == 2


def test_deploy_nonexistent_model(client):
    resp = client.post(
        "/api/v1/deployments",
        json={"modelId": "no-such", "modelVersion": 1},
    )
    assert resp.status_code == 422


def test_list_deployments(client):
    _deploy_model(client)
    resp = client.get("/api/v1/deployments")
    assert resp.status_code == 200
    assert len(resp.json()) >= 1


def test_get_deployment_status(client):
    dep_id = _deploy_model(client)
    resp = client.get(f"/api/v1/deployments/{dep_id}")
    assert resp.status_code == 200
    assert resp.json()["id"] == dep_id


def test_get_deployment_not_found(client):
    resp = client.get("/api/v1/deployments/nonexistent")
    assert resp.status_code == 404


def test_undeploy(client):
    dep_id = _deploy_model(client)
    resp = client.delete(f"/api/v1/deployments/{dep_id}")
    assert resp.status_code == 204


# ---------- monitor ----------


def test_get_metrics(client):
    dep_id = _deploy_model(client)
    resp = client.get(f"/api/v1/deployments/{dep_id}/metrics")
    assert resp.status_code == 200
    body = resp.json()
    assert body["deploymentId"] == dep_id
    assert "accuracy" in body
    assert "qps" in body


def test_get_latency(client):
    dep_id = _deploy_model(client)
    resp = client.get(f"/api/v1/deployments/{dep_id}/latency")
    assert resp.status_code == 200
    body = resp.json()
    assert body["p50Ms"] <= body["p95Ms"]


def test_get_throughput(client):
    dep_id = _deploy_model(client)
    resp = client.get(f"/api/v1/deployments/{dep_id}/throughput")
    assert resp.status_code == 200


def test_get_error_rate(client):
    dep_id = _deploy_model(client)
    resp = client.get(f"/api/v1/deployments/{dep_id}/error-rate")
    assert resp.status_code == 200


def test_metrics_unknown_deployment(client):
    resp = client.get("/api/v1/deployments/no-such/metrics")
    assert resp.status_code == 404


# ---------- docs ----------


def test_openapi_docs_accessible(client):
    """FastAPI 自动文档可访问."""
    resp = client.get("/docs")
    assert resp.status_code == 200

    resp = client.get("/openapi.json")
    assert resp.status_code == 200
    spec = resp.json()
    assert spec["info"]["title"] == "LLMOps Platform"
    # 校验关键端点存在
    paths = spec["paths"]
    assert "/api/v1/models" in paths
    assert "/api/v1/training/jobs" in paths
    assert "/api/v1/deployments" in paths
    assert "/health" in paths
