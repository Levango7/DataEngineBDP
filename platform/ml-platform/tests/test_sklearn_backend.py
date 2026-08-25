"""Scikit-learn 后端测试：事件循环非阻塞 + /evaluate 数据加载."""

from __future__ import annotations

import asyncio
import base64
import json
import time

import pytest
from fastapi.testclient import TestClient

import ml_platform.repositories.sklearn.backend as sklearnBackendModule
from ml_platform.api.app import createApp
from ml_platform.config.settings import Settings
from ml_platform.models import EvalConfig, TrainingConfig, TrainingStatus
from ml_platform.repositories import ValidationError
from ml_platform.repositories.sklearn import SklearnMLBackend
from ml_platform.services.evaluation_service import EvaluationService
from ml_platform.services.experiment_service import ExperimentService
from ml_platform.services.feature_service import FeatureService
from ml_platform.services.prediction_service import PredictionService
from ml_platform.services.registry import ServiceRegistry
from ml_platform.services.training_service import TrainingService

SLOW_SECONDS = 0.8
PROBE_TIMEOUT = 0.4


def _trainConfig(algorithm="linear_regression", X=None, y=None):
    return TrainingConfig(
        algorithm=algorithm,
        dataset="ds-1",
        features=["f1"],
        outputModelName=f"model-{time.time_ns()}",
        params={
            "_inline_data": {
                "X": X if X is not None else [[0.0], [1.0], [2.0], [3.0]],
                "y": y if y is not None else [0.0, 1.0, 2.0, 3.0],
            }
        },
    )


def _inlineDataset(payload: dict) -> str:
    encoded = base64.b64encode(json.dumps(payload).encode("utf-8")).decode("utf-8")
    return f"inline:{encoded}"


class SlowFitEstimator:
    def fit(self, X, y=None):
        time.sleep(SLOW_SECONDS)
        return self

    def predict(self, X):
        return [0.0] * len(X)


class SlowPredictEstimator:
    def __init__(self, inner):
        self._inner = inner

    def predict(self, X):
        time.sleep(SLOW_SECONDS)
        return self._inner.predict(X)


# ---------- 事件循环不阻塞 ----------


@pytest.mark.asyncio
async def test_train_does_not_block_event_loop(monkeypatch):
    monkeypatch.setattr(
        sklearnBackendModule,
        "_buildEstimator",
        lambda algorithm, params, randomState: SlowFitEstimator(),
    )
    backend = SklearnMLBackend()

    async def probe():
        await asyncio.sleep(0.01)
        return "ok"

    trainTask = asyncio.create_task(backend.train(_trainConfig()))
    probeTask = asyncio.create_task(probe())
    loop = asyncio.get_running_loop()
    started = loop.time()
    assert await asyncio.wait_for(probeTask, timeout=PROBE_TIMEOUT) == "ok"
    probeElapsed = loop.time() - started
    result = await trainTask
    assert result.status == TrainingStatus.SUCCEEDED
    assert result.durationMs >= SLOW_SECONDS * 1000 * 0.9
    assert probeElapsed < SLOW_SECONDS * 0.9


@pytest.mark.asyncio
async def test_predict_does_not_block_event_loop():
    backend = SklearnMLBackend()
    result = await backend.train(_trainConfig())
    modelId = result.modelId
    backend._artifacts[modelId] = SlowPredictEstimator(backend._artifacts[modelId])

    async def probe():
        await asyncio.sleep(0.01)
        return "ok"

    predictTask = asyncio.create_task(backend.predict(modelId, {"f1": [1.0, 2.0]}))
    probeTask = asyncio.create_task(probe())
    loop = asyncio.get_running_loop()
    started = loop.time()
    assert await asyncio.wait_for(probeTask, timeout=PROBE_TIMEOUT) == "ok"
    probeElapsed = loop.time() - started
    prediction = await predictTask
    assert prediction.modelId == modelId
    assert len(prediction.predictions) == 2
    assert probeElapsed < SLOW_SECONDS * 0.9


# ---------- /evaluate happy path ----------


@pytest.mark.asyncio
async def test_evaluate_happy_path_inline_dataset():
    backend = SklearnMLBackend()
    trainResult = await backend.train(_trainConfig())
    evalResult = await backend.evaluate(
        trainResult.modelId,
        EvalConfig(
            dataset=_inlineDataset({"X": [[1.0], [2.0], [3.0]], "y": [1.0, 2.0, 3.0]}),
            metrics=["rmse", "r2"],
        ),
    )
    assert evalResult.modelId == trainResult.modelId
    assert set(evalResult.metrics) == {"rmse", "r2"}
    assert evalResult.metrics["rmse"] < 1e-6
    assert evalResult.sampleSize == 3


@pytest.mark.asyncio
async def test_evaluate_clustering_without_labels():
    backend = SklearnMLBackend()
    X = [[0.0], [0.5], [10.0], [10.5]]
    config = _trainConfig(algorithm="kmeans", X=X, y=None)
    config.params["n_clusters"] = 2
    trainResult = await backend.train(config)
    evalResult = await backend.evaluate(
        trainResult.modelId,
        EvalConfig(dataset=_inlineDataset({"X": X}), metrics=["silhouette"]),
    )
    assert evalResult.modelId == trainResult.modelId
    assert "silhouette" in evalResult.metrics
    assert evalResult.sampleSize == 4


@pytest.mark.asyncio
async def test_evaluate_supervised_requires_labels():
    backend = SklearnMLBackend()
    trainResult = await backend.train(_trainConfig())
    with pytest.raises(ValidationError):
        await backend.evaluate(
            trainResult.modelId,
            EvalConfig(dataset=_inlineDataset({"X": [[1.0], [2.0]]}), metrics=["rmse"]),
        )


def test_evaluate_endpoint_returns_200(mockFeatureStore, mockExperimentStore):
    settings = Settings(backendType="sklearn")
    backend = SklearnMLBackend()
    registry = ServiceRegistry(
        settings=settings,
        backend=backend,
        featureStore=mockFeatureStore,
        experimentStore=mockExperimentStore,
        trainingService=TrainingService(backend, mockExperimentStore),
        predictionService=PredictionService(backend),
        evaluationService=EvaluationService(backend),
        featureService=FeatureService(mockFeatureStore),
        experimentService=ExperimentService(mockExperimentStore),
    )
    client = TestClient(createApp(settings=settings, registry=registry))

    resp = client.post(
        "/api/v1/training/jobs",
        json={
            "algorithm": "linear_regression",
            "dataset": "ds-1",
            "outputModelName": "lr-eval",
            "features": ["f1"],
            "params": {"_inline_data": {"X": [[0.0], [1.0], [2.0], [3.0]], "y": [0.0, 1.0, 2.0, 3.0]}},
        },
    )
    assert resp.status_code == 201, resp.text
    modelId = resp.json()["result"]["modelId"]

    resp = client.post(
        f"/api/v1/models/{modelId}/evaluate",
        json={
            "dataset": _inlineDataset({"X": [[1.0], [2.0]], "y": [1.0, 2.0]}),
            "metrics": ["rmse", "r2"],
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["modelId"] == modelId
    assert set(body["metrics"]) == {"rmse", "r2"}
    assert body["sampleSize"] == 2
