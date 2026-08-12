"""服务层测试."""

from __future__ import annotations

import pytest

from ml_platform.models import (
    AlgorithmType,
    EvalConfig,
    ExperimentConfig,
    FeatureGroupConfig,
    TrainingConfig,
)
from ml_platform.repositories import (
    ExperimentNotFoundError,
    FeatureGroupNotFoundError,
    TrainingJobNotFoundError,
)

# ---------- TrainingService ----------


@pytest.mark.asyncio
async def test_training_service_creates_job(registry):
    config = TrainingConfig(
        algorithm=AlgorithmType.LOGISTIC_REGRESSION,
        dataset="ds-1",
        outputModelName="lr-1",
    )
    job = await registry.trainingService.createTrainingJob(config)
    assert job.status.value == "succeeded"
    assert job.result is not None
    assert job.result.modelName == "lr-1"


@pytest.mark.asyncio
async def test_training_service_with_experiment(registry):
    """训练完成后自动记录参数与指标到实验."""
    exp = await registry.experimentService.createExperiment(ExperimentConfig(name="exp-1"))
    config = TrainingConfig(
        algorithm=AlgorithmType.RANDOM_FOREST,
        experimentId=exp.id,
        dataset="ds-1",
        outputModelName="rf-1",
        params={"n_estimators": 50},
    )
    job = await registry.trainingService.createTrainingJob(config)
    assert job.status.value == "succeeded"
    # 实验应记录参数与指标
    updated = await registry.experimentService.getExperiment(exp.id)
    assert "algorithm" in updated.params
    assert updated.params["algorithm"] == "random_forest"
    assert "accuracy" in updated.metrics


@pytest.mark.asyncio
async def test_training_service_invalid_experiment(registry):
    config = TrainingConfig(
        algorithm=AlgorithmType.LOGISTIC_REGRESSION,
        experimentId="nonexistent",
        dataset="ds-1",
        outputModelName="lr-1",
    )
    with pytest.raises(ValueError, match="实验不存在"):
        await registry.trainingService.createTrainingJob(config)


@pytest.mark.asyncio
async def test_training_service_get_status(registry):
    config = TrainingConfig(
        algorithm=AlgorithmType.LINEAR_REGRESSION,
        dataset="ds-1",
        outputModelName="lr-1",
    )
    job = await registry.trainingService.createTrainingJob(config)
    fetched = await registry.trainingService.getTrainingStatus(job.id)
    assert fetched.id == job.id


@pytest.mark.asyncio
async def test_training_service_get_status_not_found(registry):
    with pytest.raises(TrainingJobNotFoundError):
        await registry.trainingService.getTrainingStatus("nonexistent")


@pytest.mark.asyncio
async def test_training_service_list_jobs(registry):
    await registry.trainingService.createTrainingJob(
        TrainingConfig(
            algorithm=AlgorithmType.LINEAR_REGRESSION,
            dataset="ds",
            outputModelName="m1",
        )
    )
    jobs = await registry.trainingService.listTrainingJobs()
    assert len(jobs) >= 1


# ---------- PredictionService ----------


@pytest.mark.asyncio
async def test_prediction_service(registry):
    job = await registry.trainingService.createTrainingJob(
        TrainingConfig(
            algorithm=AlgorithmType.LOGISTIC_REGRESSION,
            dataset="ds",
            outputModelName="lr-1",
        )
    )
    result = await registry.predictionService.predict(job.result.modelId, {"f1": [1.0, 2.0]})
    assert len(result.predictions) == 2


# ---------- EvaluationService ----------


@pytest.mark.asyncio
async def test_evaluation_service(registry):
    job = await registry.trainingService.createTrainingJob(
        TrainingConfig(
            algorithm=AlgorithmType.LOGISTIC_REGRESSION,
            dataset="ds",
            outputModelName="lr-1",
        )
    )
    result = await registry.evaluationService.evaluate(
        job.result.modelId,
        EvalConfig(dataset="eval-1", metrics=["accuracy"]),
    )
    assert "accuracy" in result.metrics


# ---------- FeatureService ----------


@pytest.mark.asyncio
async def test_feature_service_create(registry):
    group = await registry.featureService.createFeatureGroup(FeatureGroupConfig(name="g1", entityKey="user_id"))
    assert group.name == "g1"


@pytest.mark.asyncio
async def test_feature_service_put_get(registry):
    await registry.featureService.createFeatureGroup(FeatureGroupConfig(name="g1"))
    await registry.featureService.putFeatures("g1", "u1", {"age": 30})
    features = await registry.featureService.getFeatures("g1", "u1")
    assert features["age"] == 30


@pytest.mark.asyncio
async def test_feature_service_not_found(registry):
    with pytest.raises(FeatureGroupNotFoundError):
        await registry.featureService.getFeatureGroup("nonexistent")


# ---------- ExperimentService ----------


@pytest.mark.asyncio
async def test_experiment_service_create(registry):
    info = await registry.experimentService.createExperiment(ExperimentConfig(name="exp-1"))
    assert info.name == "exp-1"


@pytest.mark.asyncio
async def test_experiment_service_log_metrics(registry):
    info = await registry.experimentService.createExperiment(ExperimentConfig(name="exp-1"))
    updated = await registry.experimentService.logMetrics(info.id, {"accuracy": 0.9})
    assert updated.metrics["accuracy"] == 0.9


@pytest.mark.asyncio
async def test_experiment_service_log_params(registry):
    info = await registry.experimentService.createExperiment(ExperimentConfig(name="exp-1"))
    updated = await registry.experimentService.logParams(info.id, {"lr": 0.01})
    assert updated.params["lr"] == 0.01


@pytest.mark.asyncio
async def test_experiment_service_list(registry):
    await registry.experimentService.createExperiment(ExperimentConfig(name="exp-1"))
    await registry.experimentService.createExperiment(ExperimentConfig(name="exp-2"))
    experiments = await registry.experimentService.listExperiments()
    assert len(experiments) == 2


@pytest.mark.asyncio
async def test_experiment_service_delete(registry):
    info = await registry.experimentService.createExperiment(ExperimentConfig(name="exp-1"))
    await registry.experimentService.deleteExperiment(info.id)
    with pytest.raises(ExperimentNotFoundError):
        await registry.experimentService.getExperiment(info.id)
