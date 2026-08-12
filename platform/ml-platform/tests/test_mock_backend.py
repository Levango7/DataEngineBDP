"""Mock ML 后端测试."""

from __future__ import annotations

import pytest

from ml_platform.models import (
    AlgorithmType,
    EvalConfig,
    TrainingConfig,
)
from ml_platform.repositories import ModelNotFoundError


@pytest.mark.asyncio
async def test_train_returns_succeeded(mockBackend):
    config = TrainingConfig(
        algorithm=AlgorithmType.LOGISTIC_REGRESSION,
        dataset="ds-1",
        outputModelName="lr-1",
    )
    result = await mockBackend.train(config)
    assert result.status.value == "succeeded"
    assert result.modelName == "lr-1"
    assert "accuracy" in result.metrics
    assert result.artifactUri is not None


@pytest.mark.asyncio
async def test_train_registers_model(mockBackend):
    config = TrainingConfig(
        algorithm=AlgorithmType.RANDOM_FOREST,
        dataset="ds-1",
        outputModelName="rf-1",
        params={"n_estimators": 100},
    )
    result = await mockBackend.train(config)
    model = await mockBackend.get_model(result.modelId)
    assert model.name == "rf-1"
    assert model.algorithm == "random_forest"
    assert model.tags["backend"] == "mock"


@pytest.mark.asyncio
async def test_train_kmeans_metrics(mockBackend):
    config = TrainingConfig(
        algorithm=AlgorithmType.KMEANS,
        dataset="ds-1",
        outputModelName="km-1",
        params={"n_clusters": 3},
    )
    result = await mockBackend.train(config)
    assert "inertia" in result.metrics
    assert "silhouette" in result.metrics


@pytest.mark.asyncio
async def test_predict_returns_deterministic(mockBackend):
    config = TrainingConfig(
        algorithm=AlgorithmType.LOGISTIC_REGRESSION,
        dataset="ds-1",
        outputModelName="lr-1",
    )
    result = await mockBackend.train(config)
    # 同一模型对同一输入两次预测应一致
    data = {"f1": [1.0, 2.0], "f2": [3.0, 4.0]}
    p1 = await mockBackend.predict(result.modelId, data)
    p2 = await mockBackend.predict(result.modelId, data)
    assert p1.predictions == p2.predictions
    assert len(p1.predictions) == 2
    # 分类任务附概率
    assert p1.probabilities is not None
    assert len(p1.probabilities) == 2


@pytest.mark.asyncio
async def test_predict_row_oriented(mockBackend):
    config = TrainingConfig(
        algorithm=AlgorithmType.LINEAR_REGRESSION,
        dataset="ds-1",
        outputModelName="lr-1",
    )
    result = await mockBackend.train(config)
    data = [{"f1": 1.0, "f2": 3.0}, {"f1": 2.0, "f2": 4.0}]
    p = await mockBackend.predict(result.modelId, data)
    assert len(p.predictions) == 2


@pytest.mark.asyncio
async def test_predict_model_not_found(mockBackend):
    with pytest.raises(ModelNotFoundError):
        await mockBackend.predict("nonexistent", {"f1": [1.0]})


@pytest.mark.asyncio
async def test_evaluate(mockBackend):
    config = TrainingConfig(
        algorithm=AlgorithmType.LOGISTIC_REGRESSION,
        dataset="ds-1",
        outputModelName="lr-1",
    )
    result = await mockBackend.train(config)
    evalResult = await mockBackend.evaluate(
        result.modelId,
        EvalConfig(dataset="eval-1", metrics=["accuracy", "auc"]),
    )
    assert evalResult.modelId == result.modelId
    assert "accuracy" in evalResult.metrics
    assert "auc" in evalResult.metrics
    assert evalResult.sampleSize == 100


@pytest.mark.asyncio
async def test_evaluate_model_not_found(mockBackend):
    with pytest.raises(ModelNotFoundError):
        await mockBackend.evaluate("nonexistent", EvalConfig(dataset="eval-1"))


@pytest.mark.asyncio
async def test_list_models(mockBackend):
    await mockBackend.train(
        TrainingConfig(
            algorithm=AlgorithmType.LINEAR_REGRESSION,
            dataset="ds",
            outputModelName="m1",
        )
    )
    await mockBackend.train(
        TrainingConfig(
            algorithm=AlgorithmType.LINEAR_REGRESSION,
            dataset="ds",
            outputModelName="m2",
        )
    )
    models = await mockBackend.list_models()
    assert len(models) == 2


@pytest.mark.asyncio
async def test_delete_model(mockBackend):
    result = await mockBackend.train(
        TrainingConfig(
            algorithm=AlgorithmType.LINEAR_REGRESSION,
            dataset="ds",
            outputModelName="m1",
        )
    )
    await mockBackend.delete_model(result.modelId)
    with pytest.raises(ModelNotFoundError):
        await mockBackend.get_model(result.modelId)


@pytest.mark.asyncio
async def test_delete_model_not_found(mockBackend):
    with pytest.raises(ModelNotFoundError):
        await mockBackend.delete_model("nonexistent")
