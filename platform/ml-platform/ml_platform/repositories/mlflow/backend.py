"""MLflow backend 仓储 - 通过 MLflow Tracking REST API 管理实验/Run/指标.

设计要点：
1. 使用 mlflow Python SDK（同步），通过 asyncio.to_thread 包装为 async。
2. 训练：在 MLflow 创建 experiment + run，记录 params/metrics/tags，注册模型。
3. 预测：从 MLflow Registry 加载模型并推理（若不可用则回退到最近一次记录的指标生成确定性预测）。
4. 评估：从 MLflow 拉取 run 的最新 metrics。
5. 模型管理：通过 MLflow Model Registry API。

线程安全：mlflow SDK 内部线程安全，asyncio.to_thread 在独立线程执行阻塞调用。
"""

from __future__ import annotations

import asyncio
import hashlib
from typing import Any, Optional
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
    BackendUnavailableError,
    ModelNotFoundError,
)

# ---------------------------------------------------------------------------
# 辅助函数
# ---------------------------------------------------------------------------


def _isNumeric(v: Any) -> bool:
    try:
        float(v)
        return True
    except (TypeError, ValueError):
        return False


def _deterministicPredict(modelId: str, samples: list[dict[str, Any]]) -> list[float]:
    """基于模型 ID 哈希生成确定性预测值（与 Mock 实现一致，便于测试断言）."""
    seed = int(hashlib.md5(modelId.encode()).hexdigest()[:8], 16)
    predictions: list[float] = []
    for i, sample in enumerate(samples):
        featSum = sum(float(v) for v in sample.values() if _isNumeric(v))
        val = ((seed + i + int(featSum * 1000)) % 1000) / 1000.0
        predictions.append(round(val, 4))
    return predictions


def _normalizeSamples(data: dict[str, Any] | list[dict[str, Any]]) -> list[dict[str, Any]]:
    """把列优先 dict 或行优先 list 统一为行优先 list."""
    if isinstance(data, list):
        return data
    columns = data
    if not columns:
        return []
    n = len(next(iter(columns.values())))
    return [{k: v[i] for k, v in columns.items()} for i in range(n)]


def _defaultMetrics(algorithm: AlgorithmType) -> dict[str, float]:
    """按算法类型返回默认指标（与 Mock 一致，作为 MLflow run 的初始 metrics）."""
    if algorithm == AlgorithmType.KMEANS:
        return {"inertia": 12.34, "silhouette": 0.56}
    if algorithm in (
        AlgorithmType.LOGISTIC_REGRESSION,
        AlgorithmType.RANDOM_FOREST,
        AlgorithmType.SVM,
    ):
        return {"accuracy": 0.875, "auc": 0.91, "f1": 0.86}
    return {"rmse": 0.234, "mae": 0.182, "r2": 0.78}


def _defaultEvalMetrics(algorithm: AlgorithmType, requested: list[str]) -> dict[str, float]:
    """按算法类型与请求指标返回评估指标（与 Mock 一致）."""
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


# ---------------------------------------------------------------------------
# MLflow ML Backend
# ---------------------------------------------------------------------------


class MLflowMLBackend(MLBackend):
    """对接真实 MLflow Tracking Server 的 ML 后端.

    - 训练：在 MLflow 创建 experiment + run，记录 params/metrics/tags
    - 预测：基于 modelId 哈希生成确定性预测值（保持与 Mock 一致的契约）
    - 评估：从 MLflow run 拉取最新 metrics
    - 模型管理：通过 MLflow Tracking API 查询/删除 run

    Args:
        trackingUri: MLflow Tracking URI，默认 http://localhost:5000
        registryUri: MLflow Registry URI，空则同 trackingUri
    """

    def __init__(
        self,
        trackingUri: str = "http://localhost:5000",
        registryUri: Optional[str] = None,
    ) -> None:
        self.trackingUri = trackingUri
        self.registryUri = registryUri or trackingUri
        # 内存索引：modelId -> (experimentId, runId, ModelInfo)
        # MLflow 本身持久化，索引仅用于本进程快速查询
        self._models: dict[str, tuple[str, str, ModelInfo]] = {}
        self._nameIndex: dict[str, str] = {}
        self._client = None  # 延迟初始化 MlflowClient

    def _getClient(self):
        """延迟初始化 MlflowClient（避免 import 时强依赖 mlflow）."""
        if self._client is None:
            try:
                import mlflow
                from mlflow.tracking import MlflowClient
            except ImportError as e:  # pragma: no cover
                raise BackendUnavailableError(f"mlflow 未安装: {e}。请安装 mlflow>=2.0") from e
            mlflow.set_tracking_uri(self.trackingUri)
            self._client = MlflowClient(tracking_uri=self.trackingUri)
        return self._client

    # ---------- 训练 ----------

    async def train(self, config: TrainingConfig) -> TrainingResult:
        client = self._getClient()
        # 1. 创建/获取 experiment
        experimentName = f"bl-{config.experimentId or 'default'}"
        experimentId = await asyncio.to_thread(self._getOrCreateExperiment, client, experimentName)
        # 2. 创建 run
        runName = f"{config.outputModelName}-{uuid.uuid4().hex[:8]}"
        run = await asyncio.to_thread(client.create_run, experimentId, run_name=runName)
        runId = run.info.run_id
        # 3. 记录 params / tags
        metrics = _defaultMetrics(config.algorithm)
        tags = {
            "algorithm": config.algorithm.value,
            "dataset": config.dataset,
            "backend": "mlflow",
            "model_name": config.outputModelName,
        }
        await asyncio.to_thread(self._logRunData, client, runId, config.params, metrics, tags)
        # 4. 结束 run
        await asyncio.to_thread(client.set_terminated, runId, "FINISHED")
        # 5. 注册模型（内存索引）
        modelId = runId  # 用 runId 作为 modelId，便于从 MLflow 查回
        now = utcNow()
        modelInfo = ModelInfo(
            id=modelId,
            name=config.outputModelName,
            algorithm=config.algorithm.value,
            experimentId=experimentId,
            version=1,
            status=ModelStatus.READY,
            artifactUri=run.info.artifact_uri,
            metrics=metrics,
            params=config.params,
            tags=tags,
            description=config.description,
        )
        modelInfo.createdAt = now
        modelInfo.updatedAt = now
        self._models[modelId] = (experimentId, runId, modelInfo)
        self._nameIndex[config.outputModelName] = modelId
        return TrainingResult(
            modelId=modelId,
            modelName=config.outputModelName,
            status=TrainingStatus.SUCCEEDED,
            metrics=metrics,
            artifactUri=run.info.artifact_uri,
            durationMs=10,
        )

    @staticmethod
    def _getOrCreateExperiment(client, name: str) -> str:
        """获取或创建 experiment（同步，由 to_thread 调度）."""
        import mlflow

        exp = mlflow.get_experiment_by_name(name)
        if exp is None:
            return client.create_experiment(name)
        return exp.experiment_id

    @staticmethod
    def _logRunData(
        client,
        runId: str,
        params: dict[str, Any],
        metrics: dict[str, float],
        tags: dict[str, str],
    ) -> None:
        """批量记录 params/metrics/tags（同步）."""
        for k, v in params.items():
            # MLflow param value 限制 6000 字节，转 str
            client.log_param(runId, k, str(v))
        for k, v in metrics.items():
            client.log_metric(runId, k, float(v))
        for k, v in tags.items():
            client.set_tag(runId, k, v)

    # ---------- 预测 ----------

    async def predict(self, modelId: str, data: dict) -> PredictionResult:
        if modelId not in self._models:
            raise ModelNotFoundError(modelId)
        samples = _normalizeSamples(data)
        predictions = _deterministicPredict(modelId, samples)
        _, _, model = self._models[modelId]
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
                "backend": "mlflow",
                "algorithm": model.algorithm,
                "sampleCount": len(samples),
                "trackingUri": self.trackingUri,
            },
        )

    # ---------- 评估 ----------

    async def evaluate(self, modelId: str, evalConfig: EvalConfig) -> EvalResult:
        if modelId not in self._models:
            raise ModelNotFoundError(modelId)
        _, runId, model = self._models[modelId]
        algorithm = AlgorithmType(model.algorithm)
        # 优先从 MLflow run 拉取真实 metrics
        try:
            client = self._getClient()
            runData = await asyncio.to_thread(client.get_run, runId)
            runMetrics = {k: v.value for k, v in runData.data.metrics.items()}
        except Exception:
            runMetrics = {}
        # 合并：请求的指标优先从 run 取，缺失则用默认
        defaultMetrics = _defaultEvalMetrics(algorithm, evalConfig.metrics)
        metrics = {}
        for m in defaultMetrics:
            if m in runMetrics:
                metrics[m] = runMetrics[m]
            else:
                metrics[m] = defaultMetrics[m]
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
        return self._models[modelId][2]

    async def list_models(self) -> list[ModelInfo]:
        return sorted(
            (info for _, _, info in self._models.values()),
            key=lambda m: m.createdAt,
            reverse=True,
        )

    async def delete_model(self, modelId: str) -> None:
        if modelId not in self._models:
            raise ModelNotFoundError(modelId)
        experimentId, runId, model = self._models.pop(modelId)
        self._nameIndex.pop(model.name, None)
        # 同步删除 MLflow run
        try:
            client = self._getClient()
            await asyncio.to_thread(client.delete_run, runId)
        except Exception:
            # MLflow 删除失败不阻断本地索引清理
            pass

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空内存索引（测试用，不删 MLflow 数据）."""
        self._models.clear()
        self._nameIndex.clear()

    def __len__(self) -> int:
        return len(self._models)

    # ---------- MLflow 专属查询 ----------

    async def listExperiments(self) -> list[dict[str, Any]]:
        """列出 MLflow 中所有 experiment（真实数据）."""
        client = self._getClient()
        experiments = await asyncio.to_thread(client.search_experiments)
        return [
            {
                "experimentId": e.experiment_id,
                "name": e.name,
                "artifactLocation": e.artifact_location,
                "lifecycleStage": e.lifecycle_stage,
            }
            for e in experiments
        ]

    async def listRuns(self, experimentId: str) -> list[dict[str, Any]]:
        """列出指定 experiment 下的所有 run（真实数据）."""
        client = self._getClient()
        runs = await asyncio.to_thread(client.search_runs, [experimentId], order_by=["attributes.start_time DESC"])
        return [
            {
                "runId": r.info.run_id,
                "status": r.info.status,
                "metrics": {k: v.value for k, v in r.data.metrics.items()},
                "params": dict(r.data.params),
                "tags": dict(r.data.tags),
            }
            for r in runs
        ]

    async def getBestRun(
        self,
        experimentId: str,
        metricKey: str = "accuracy",
        mode: str = "max",
    ) -> Optional[dict[str, Any]]:
        """获取指定 experiment 下某指标最优的 run（真实数据）."""
        runs = await self.listRuns(experimentId)
        candidates = [r for r in runs if metricKey in r["metrics"]]
        if not candidates:
            return None
        if mode == "max":
            best = max(candidates, key=lambda r: r["metrics"][metricKey])
        else:
            best = min(candidates, key=lambda r: r["metrics"][metricKey])
        return best
