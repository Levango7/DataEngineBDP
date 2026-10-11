"""前端契约（/llmops/*）测试——对齐 frontend/src/api/llmops.ts 的 9 个端点与形状。

台账 #1b / Q1：llmops 为该域单一权威后，网关 `/api/v1/llmops` 才可整体改指本服务。
"""

from __future__ import annotations

BASE = "/api/v1/llmops"


# ---------------------------------------------------------------- models
def test_frontend_list_models_shape(client):
    client.post("/api/v1/models", json={"name": "qiong-7B", "type": "base", "params": {"architecture": "qwen2"}})
    resp = client.get(f"{BASE}/models")
    assert resp.status_code == 200, resp.text
    items = resp.json()
    assert items, "模型列表不应为空"
    m = items[0]
    # 前端 ModelRegistry 形状
    for key in ("id", "name", "algorithm", "version", "status", "registeredAt"):
        assert key in m, f"缺少前端字段 {key}"


def test_frontend_register_model(client):
    resp = client.post(f"{BASE}/models", json={"name": "ft-x", "algorithm": "lora", "modelPath": "s3://m"})
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["name"] == "ft-x"
    assert body["modelPath"] == "s3://m"
    assert "id" in body and "registeredAt" in body


def test_frontend_register_model_requires_name(client):
    resp = client.post(f"{BASE}/models", json={"algorithm": "lora"})
    assert resp.status_code == 422


# ---------------------------------------------------------------- eval-metrics
def test_frontend_eval_metrics_crud_and_shape(client):
    created = client.post(
        f"{BASE}/eval-metrics",
        json={"modelName": "shuqing-7b", "evalType": "auto", "accuracy": 0.87, "dataset": "d1"},
    )
    assert created.status_code == 201, created.text
    body = created.json()
    for key in ("id", "modelName", "evalType", "accuracy", "dataset", "createdAt"):
        assert key in body, f"缺少前端字段 {key}"

    listed = client.get(f"{BASE}/eval-metrics", params={"modelName": "shuqing-7b"})
    assert listed.status_code == 200
    assert any(x["id"] == body["id"] for x in listed.json())


# ---------------------------------------------------------------- finetune
def test_frontend_finetune_submit_list_status(client):
    created = client.post(
        f"{BASE}/finetune",
        json={"modelName": "ft-y", "baseModel": "qiong-7B", "trainingData": "ds1", "epochs": 2},
    )
    assert created.status_code == 201, created.text
    task = created.json()
    for key in ("taskId", "modelName", "baseModel", "status", "progress", "submittedAt"):
        assert key in task, f"缺少前端字段 {key}"

    listed = client.get(f"{BASE}/finetune")
    assert listed.status_code == 200
    assert any(x["taskId"] == task["taskId"] for x in listed.json())

    status = client.get(f"{BASE}/finetune/{task['taskId']}")
    assert status.status_code == 200
    assert status.json()["taskId"] == task["taskId"]

    missing = client.get(f"{BASE}/finetune/does-not-exist")
    assert missing.status_code == 404


# ---------------------------------------------------------------- human-eval
def test_frontend_human_eval(client):
    resp = client.post(f"{BASE}/human-eval", json={"modelName": "ft-y"})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    for key in ("metricId", "modelName", "status"):
        assert key in body


# ---------------------------------------------------------------- inference-services
def test_frontend_inference_services_shape(client):
    resp = client.get(f"{BASE}/inference-services")
    assert resp.status_code == 200, resp.text
    assert isinstance(resp.json(), list)