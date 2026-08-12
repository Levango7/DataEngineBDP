"""API 端点测试."""

from __future__ import annotations

# ---------- health ----------


def testHealth(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["backend"] == "mock"
    assert body["featureStore"] == "mock"
    assert body["experimentStore"] == "mock"


# ---------- experiments ----------


def _createExperiment(client, name="exp-1"):
    resp = client.post(
        "/api/v1/experiments",
        json={
            "name": name,
            "workspaceId": "ws-1",
            "projectId": "proj-1",
            "description": "test experiment",
            "tags": {"owner": "ml-team"},
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def testCreateExperiment(client):
    eid = _createExperiment(client)
    resp = client.get(f"/api/v1/experiments/{eid}")
    body = resp.json()
    assert body["name"] == "exp-1"
    assert body["config"]["workspaceId"] == "ws-1"
    assert body["status"] == "active"


def testCreateExperimentDuplicate(client):
    _createExperiment(client, name="dup")
    resp = client.post("/api/v1/experiments", json={"name": "dup"})
    assert resp.status_code == 409


def testListExperiments(client):
    _createExperiment(client, name="e1")
    _createExperiment(client, name="e2")
    resp = client.get("/api/v1/experiments")
    assert resp.status_code == 200
    assert len(resp.json()) == 2


def testGetExperimentNotFound(client):
    resp = client.get("/api/v1/experiments/nonexistent")
    assert resp.status_code == 404


def testDeleteExperiment(client):
    eid = _createExperiment(client)
    resp = client.delete(f"/api/v1/experiments/{eid}")
    assert resp.status_code == 204
    resp = client.get(f"/api/v1/experiments/{eid}")
    assert resp.status_code == 404


def testLogMetrics(client):
    eid = _createExperiment(client)
    resp = client.post(
        f"/api/v1/experiments/{eid}/metrics",
        json={"metrics": {"accuracy": 0.9, "auc": 0.85}},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["metrics"]["accuracy"] == 0.9
    assert body["runCount"] == 1


def testLogParams(client):
    eid = _createExperiment(client)
    resp = client.post(
        f"/api/v1/experiments/{eid}/params",
        json={"params": {"lr": 0.01, "epochs": 10}},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["params"]["lr"] == 0.01


def testLogMetricsExperimentNotFound(client):
    resp = client.post(
        "/api/v1/experiments/nonexistent/metrics",
        json={"metrics": {"a": 1.0}},
    )
    assert resp.status_code == 404


# ---------- training ----------


def _createTrainingJob(client, name="lr-1"):
    resp = client.post(
        "/api/v1/training/jobs",
        json={
            "algorithm": "logistic_regression",
            "dataset": "ds-1",
            "outputModelName": name,
            "features": ["f1", "f2"],
            "params": {"C": 1.0},
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def testCreateTrainingJob(client):
    job = _createTrainingJob(client)
    assert job["status"] == "succeeded"
    assert job["result"]["modelName"] == "lr-1"
    assert "accuracy" in job["result"]["metrics"]


def testCreateTrainingJobWithExperiment(client):
    eid = _createExperiment(client)
    resp = client.post(
        "/api/v1/training/jobs",
        json={
            "algorithm": "random_forest",
            "experimentId": eid,
            "dataset": "ds-1",
            "outputModelName": "rf-1",
        },
    )
    assert resp.status_code == 201
    # 实验应记录参数与指标
    exp = client.get(f"/api/v1/experiments/{eid}").json()
    assert "algorithm" in exp["params"]
    assert "accuracy" in exp["metrics"]


def testCreateTrainingInvalidExperiment(client):
    resp = client.post(
        "/api/v1/training/jobs",
        json={
            "algorithm": "logistic_regression",
            "experimentId": "nonexistent",
            "dataset": "ds-1",
            "outputModelName": "lr-1",
        },
    )
    assert resp.status_code == 422


def testGetTrainingStatus(client):
    job = _createTrainingJob(client)
    resp = client.get(f"/api/v1/training/jobs/{job['id']}")
    assert resp.status_code == 200
    assert resp.json()["id"] == job["id"]


def testGetTrainingStatusNotFound(client):
    resp = client.get("/api/v1/training/jobs/nonexistent")
    assert resp.status_code == 404


def testListTrainingJobs(client):
    _createTrainingJob(client)
    resp = client.get("/api/v1/training/jobs")
    assert resp.status_code == 200
    assert len(resp.json()) >= 1


# ---------- models ----------


def _setupModel(client, name="lr-1"):
    job = _createTrainingJob(client, name=name)
    return job["result"]["modelId"]


def testListModels(client):
    _setupModel(client, name="m1")
    _setupModel(client, name="m2")
    resp = client.get("/api/v1/models")
    assert resp.status_code == 200
    assert len(resp.json()) == 2


def testGetModel(client):
    mid = _setupModel(client)
    resp = client.get(f"/api/v1/models/{mid}")
    assert resp.status_code == 200
    assert resp.json()["id"] == mid


def testGetModelNotFound(client):
    resp = client.get("/api/v1/models/nonexistent")
    assert resp.status_code == 404


def testDeleteModel(client):
    mid = _setupModel(client)
    resp = client.delete(f"/api/v1/models/{mid}")
    assert resp.status_code == 204
    resp = client.get(f"/api/v1/models/{mid}")
    assert resp.status_code == 404


def testPredict(client):
    mid = _setupModel(client)
    resp = client.post(
        f"/api/v1/models/{mid}/predict",
        json={"data": {"f1": [1.0, 2.0], "f2": [3.0, 4.0]}},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["modelId"] == mid
    assert len(body["predictions"]) == 2
    # 分类任务附概率
    assert body["probabilities"] is not None


def testPredictRowOriented(client):
    mid = _setupModel(client)
    resp = client.post(
        f"/api/v1/models/{mid}/predict",
        json={
            "data": [
                {"f1": 1.0, "f2": 3.0},
                {"f1": 2.0, "f2": 4.0},
            ]
        },
    )
    assert resp.status_code == 200
    assert len(resp.json()["predictions"]) == 2


def testPredictModelNotFound(client):
    resp = client.post(
        "/api/v1/models/nonexistent/predict",
        json={"data": {"f1": [1.0]}},
    )
    assert resp.status_code == 404


def testEvaluate(client):
    mid = _setupModel(client)
    resp = client.post(
        f"/api/v1/models/{mid}/evaluate",
        json={
            "dataset": "eval-1",
            "metrics": ["accuracy", "auc"],
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["modelId"] == mid
    assert "accuracy" in body["metrics"]
    assert "auc" in body["metrics"]


def testEvaluateModelNotFound(client):
    resp = client.post(
        "/api/v1/models/nonexistent/evaluate",
        json={"dataset": "eval-1"},
    )
    assert resp.status_code == 404


# ---------- feature groups ----------


def _createFeatureGroup(client, name="user_features"):
    resp = client.post(
        "/api/v1/feature-groups",
        json={
            "name": name,
            "entityKey": "user_id",
            "features": [
                {"name": "age", "dtype": "int"},
                {"name": "gender", "dtype": "string"},
            ],
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["name"]


def testCreateFeatureGroup(client):
    name = _createFeatureGroup(client)
    resp = client.get(f"/api/v1/feature-groups/{name}")
    body = resp.json()
    assert body["name"] == name
    assert body["config"]["entityKey"] == "user_id"
    assert len(body["config"]["features"]) == 2


def testCreateFeatureGroupDuplicate(client):
    _createFeatureGroup(client, name="dup")
    resp = client.post("/api/v1/feature-groups", json={"name": "dup"})
    assert resp.status_code == 409


def testListFeatureGroups(client):
    _createFeatureGroup(client, name="g1")
    _createFeatureGroup(client, name="g2")
    resp = client.get("/api/v1/feature-groups")
    assert resp.status_code == 200
    assert len(resp.json()) == 2


def testGetFeatureGroupNotFound(client):
    resp = client.get("/api/v1/feature-groups/nonexistent")
    assert resp.status_code == 404


def testPutAndGetFeatures(client):
    name = _createFeatureGroup(client)
    resp = client.put(
        f"/api/v1/feature-groups/{name}/features/user-1",
        json={"features": {"age": 30, "gender": "M"}},
    )
    assert resp.status_code == 204
    resp = client.get(f"/api/v1/feature-groups/{name}/features/user-1")
    assert resp.status_code == 200
    body = resp.json()
    assert body["features"]["age"] == 30
    assert body["features"]["gender"] == "M"


def testGetFeaturesEntityNotFound(client):
    name = _createFeatureGroup(client)
    resp = client.get(f"/api/v1/feature-groups/{name}/features/nonexistent")
    assert resp.status_code == 404


def testPutFeaturesGroupNotFound(client):
    resp = client.put(
        "/api/v1/feature-groups/nonexistent/features/u1",
        json={"features": {"a": 1}},
    )
    assert resp.status_code == 404


def testDeleteFeatures(client):
    name = _createFeatureGroup(client)
    client.put(
        f"/api/v1/feature-groups/{name}/features/u1",
        json={"features": {"a": 1}},
    )
    resp = client.delete(f"/api/v1/feature-groups/{name}/features/u1")
    assert resp.status_code == 204
    resp = client.get(f"/api/v1/feature-groups/{name}/features/u1")
    assert resp.status_code == 404


# ---------- end-to-end ML flow ----------


def testEndToEndMlFlow(client):
    """端到端 ML 流程：创建实验 → 特征准备 → 训练 → 评估 → 预测."""
    # 1. 创建实验
    eid = _createExperiment(client, name="e2e-exp")

    # 2. 特征准备
    fgName = _createFeatureGroup(client, name="e2e_features")
    client.put(
        f"/api/v1/feature-groups/{fgName}/features/u1",
        json={"features": {"f1": 1.0, "f2": 2.0}},
    )
    client.put(
        f"/api/v1/feature-groups/{fgName}/features/u2",
        json={"features": {"f1": 3.0, "f2": 4.0}},
    )

    # 3. 训练
    resp = client.post(
        "/api/v1/training/jobs",
        json={
            "algorithm": "random_forest",
            "experimentId": eid,
            "dataset": "e2e_features",
            "outputModelName": "e2e-model",
            "features": ["f1", "f2"],
            "params": {"n_estimators": 10},
        },
    )
    assert resp.status_code == 201
    job = resp.json()
    assert job["status"] == "succeeded"
    modelId = job["result"]["modelId"]

    # 4. 评估
    resp = client.post(
        f"/api/v1/models/{modelId}/evaluate",
        json={"dataset": "e2e-eval", "metrics": ["accuracy"]},
    )
    assert resp.status_code == 200

    # 5. 预测
    resp = client.post(
        f"/api/v1/models/{modelId}/predict",
        json={"data": {"f1": [1.0, 3.0], "f2": [2.0, 4.0]}},
    )
    assert resp.status_code == 200
    assert len(resp.json()["predictions"]) == 2

    # 实验应有参数与指标记录
    exp = client.get(f"/api/v1/experiments/{eid}").json()
    assert "algorithm" in exp["params"]
    assert "accuracy" in exp["metrics"]


# ---------- docs ----------


def testOpenapiDocsAccessible(client):
    """FastAPI 自动文档可访问."""
    resp = client.get("/docs")
    assert resp.status_code == 200

    resp = client.get("/openapi.json")
    assert resp.status_code == 200
    spec = resp.json()
    assert spec["info"]["title"] == "ML Platform"
    # 校验关键端点存在
    paths = spec["paths"]
    assert "/api/v1/experiments" in paths
    assert "/api/v1/training/jobs" in paths
    assert "/api/v1/models" in paths
    assert "/api/v1/feature-groups" in paths
    assert "/health" in paths
