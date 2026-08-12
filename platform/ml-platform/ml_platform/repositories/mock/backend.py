"""Mock ML 后端 - 内存模拟实现.

用于测试与离线开发，返回固定/伪随机的训练/预测/评估结果。
线程安全说明：单进程内存态，配合 asyncio 单线程事件循环无需加锁。
"""

from __future__ import annotations

import hashlib
from typing import Any
import uuid

from ml_platform.interfaces.backend import MLBackend
from ml_platform.models import (
    AlgorithmType,
    EvalConfig,
    EvalResult,
    ModelInfo,
    ModelStatus,
    PredictionResult,
    TrainingConfig,
    TrainingResult,
    TrainingStatus,
    utcNow,
)
from ml_platform.repositories import (
    ModelNotFoundError,
)


def _deterministicPredict(modelId: str, samples: list[dict[str, Any]]) -> list[float]:
    """基于模型 ID 哈希生成确定性预测值（便于测试断言）."""
    seed = int(hashlib.md5(modelId.encode()).hexdigest()[:8], 16)
    predictions: list[float] = []
    for i, sample in enumerate(samples):
        # 用样本字段和的哈希扰动种子
        featSum = sum(float(v) for v in sample.values() if _isNumeric(v))
        val = ((seed + i + int(featSum * 1000)) % 1000) / 1000.0
        predictions.append(round(val, 4))
    return predictions


def _isNumeric(v: Any) -> bool:
    try:
        float(v)
        return True
    except (TypeError, ValueError):
        return False


def _defaultMetrics(algorithm: AlgorithmType) -> dict[str, float]:
    """按算法类型返回默认 mock 指标."""
    if algorithm == AlgorithmType.KMEANS:
        return {"inertia": 12.34, "silhouette": 0.56}
    if algorithm in (
        AlgorithmType.LOGISTIC_REGRESSION,
        AlgorithmType.RANDOM_FOREST,
        AlgorithmType.SVM,
    ):
        return {"accuracy": 0.875, "auc": 0.91, "f1": 0.86}
    # 回归
    return {"rmse": 0.234, "mae": 0.182, "r2": 0.78}


def _defaultEvalMetrics(algorithm: AlgorithmType, requested: list[str]) -> dict[str, float]:
    """按算法类型与请求指标返回 mock 评估指标."""
    catalog = {
        "accuracy": 0.88,
        "auc": 0.92,
        "f1": 0.87,
        "precision": 0.86,
        "recall": 0.88,
        "rmse": 0.21,
        "mae": 0.17,
        "r2": 0.80,
        "inertia": 11.11,
        "silhouette": 0.58,
    }
    if not requested:
        requested = list(_defaultMetrics(algorithm).keys())
    return {m: catalog.get(m, 0.5) for m in requested}


class MockMLBackend(MLBackend):
    """内存 Mock ML 后端.

    - 训练：返回固定指标，生成确定性 modelId
    - 预测：基于 modelId 哈希生成确定性预测值
    - 评估：返回固定指标
    - 模型管理：内存字典
    """

    def __init__(self) -> None:
        self._models: dict[str, ModelInfo] = {}
        # name -> model_id 索引
        self._nameIndex: dict[str, str] = {}

    # ---------- 训练 ----------

    async def train(self, config: TrainingConfig) -> TrainingResult:
        modelId = str(uuid.uuid4())
        now = utcNow()
        metrics = _defaultMetrics(config.algorithm)
        # 注册模型
        modelInfo = ModelInfo(
            id=modelId,
            name=config.outputModelName,
            algorithm=config.algorithm.value,
            experimentId=config.experimentId,
            version=1,
            status=ModelStatus.READY,
            artifactUri=f"mock-artifact:///{modelId}/model",
            metrics=metrics,
            params=config.params,
            tags={"dataset": config.dataset, "backend": "mock"},
            description=config.description,
        )
        modelInfo.createdAt = now
        modelInfo.updatedAt = now
        self._models[modelId] = modelInfo
        self._nameIndex[config.outputModelName] = modelId
        return TrainingResult(
            modelId=modelId,
            modelName=config.outputModelName,
            status=TrainingStatus.SUCCEEDED,
            metrics=metrics,
            artifactUri=modelInfo.artifactUri,
            durationMs=10,
        )

    # ---------- 预测 ----------

    async def predict(self, modelId: str, data: dict) -> PredictionResult:
        if modelId not in self._models:
            raise ModelNotFoundError(modelId)
        samples = _normalizeSamples(data)
        predictions = _deterministicPredict(modelId, samples)
        # 分类任务附概率
        model = self._models[modelId]
        probabilities = None
        if model.algorithm in (
            AlgorithmType.LOGISTIC_REGRESSION.value,
            AlgorithmType.RANDOM_FOREST.value,
            AlgorithmType.SVM.value,
        ):
            probabilities = [[1.0 - p, p] for p in predictions]
        return PredictionResult(
            modelId=modelId,
            predictions=predictions,
            probabilities=probabilities,
            metadata={
                "backend": "mock",
                "algorithm": model.algorithm,
                "sampleCount": len(samples),
            },
        )

    # ---------- 评估 ----------

    async def evaluate(self, modelId: str, evalConfig: EvalConfig) -> EvalResult:
        if modelId not in self._models:
            raise ModelNotFoundError(modelId)
        model = self._models[modelId]
        algorithm = AlgorithmType(model.algorithm)
        metrics = _defaultEvalMetrics(algorithm, evalConfig.metrics)
        return EvalResult(
            modelId=modelId,
            dataset=evalConfig.dataset,
            metrics=metrics,
            sampleSize=100,
        )

    # ---------- 模型管理 ----------

    async def get_model(self, modelId: str) -> ModelInfo:
        if modelId not in self._models:
            raise ModelNotFoundError(modelId)
        return self._models[modelId]

    async def list_models(self) -> list[ModelInfo]:
        return sorted(
            self._models.values(),
            key=lambda m: m.createdAt,
            reverse=True,
        )

    async def delete_model(self, modelId: str) -> None:
        if modelId not in self._models:
            raise ModelNotFoundError(modelId)
        m = self._models.pop(modelId)
        self._nameIndex.pop(m.name, None)

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._models.clear()
        self._nameIndex.clear()

    def __len__(self) -> int:
        return len(self._models)


def _normalizeSamples(data: dict[str, Any] | list[dict[str, Any]]) -> list[dict[str, Any]]:
    """把列优先 dict 或行优先 list 统一为行优先 list."""
    if isinstance(data, list):
        return data
    # 列优先：{feature: [v1, v2, ...]}
    columns = data
    if not columns:
        return []
    # 取任一列长度作为样本数
    n = len(next(iter(columns.values())))
    samples: list[dict[str, Any]] = []
    for i in range(n):
        samples.append({k: v[i] for k, v in columns.items()})
    return samples
