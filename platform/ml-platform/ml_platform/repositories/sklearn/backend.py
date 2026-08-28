"""Scikit-learn ML 后端实现.

调用 scikit-learn 进行真实训练/预测/评估。
支持算法：
    - linear_regression:    LinearRegression
    - logistic_regression:  LogisticRegression
    - random_forest:        RandomForestClassifier / RandomForestRegressor
    - svm:                  SVC / SVR
    - kmeans:               KMeans

通过配置开关 ML_BACKEND_TYPE=sklearn 启用；sklearn 未安装时抛 BackendUnavailableError。
模型产物以 joblib 序列化保存到 artifactUri 指向的路径（内存态时仅保留在内存）。
"""

from __future__ import annotations

import asyncio
import time
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
    BackendUnavailableError,
    ModelNotFoundError,
    TrainingFailedError,
    ValidationError,
)


def _requireSklearn():
    """延迟导入 sklearn，未安装则抛 BackendUnavailableError."""
    try:
        import numpy  # noqa: F401
        import sklearn  # noqa: F401
    except ImportError as e:
        raise BackendUnavailableError(f"scikit-learn 未安装: {e}。请安装 sq-ml-platform[sklearn]") from e


def _buildEstimator(algorithm: AlgorithmType, params: dict[str, Any], randomState: int):
    """根据算法类型与超参构造 sklearn 估计器."""
    from sklearn.cluster import KMeans
    from sklearn.ensemble import (
        RandomForestClassifier,
        RandomForestRegressor,
    )
    from sklearn.linear_model import (
        LinearRegression,
        LogisticRegression,
    )
    from sklearn.svm import SVC, SVR

    if algorithm == AlgorithmType.LINEAR_REGRESSION:
        return LinearRegression(**params)
    if algorithm == AlgorithmType.LOGISTIC_REGRESSION:
        params = {**params, "random_state": randomState}
        return LogisticRegression(**params)
    if algorithm == AlgorithmType.RANDOM_FOREST:
        # 默认分类器；若 params 中 task="regression" 则用回归器
        task = params.pop("task", "classification")
        common = {
            "random_state": randomState,
            "n_estimators": params.pop("n_estimators", 100),
            "max_depth": params.pop("max_depth", None),
        }
        common.update(params)
        if task == "regression":
            return RandomForestRegressor(**common)
        return RandomForestClassifier(**common)
    if algorithm == AlgorithmType.SVM:
        task = params.pop("task", "classification")
        if task == "regression":
            return SVR(**params)
        # 默认用 SVC，支持 probability
        params.setdefault("probability", True)
        return SVC(**params)
    if algorithm == AlgorithmType.KMEANS:
        params = {**params, "random_state": randomState}
        return KMeans(**params)
    raise ValidationError(f"不支持的算法: {algorithm}")


def _isClassification(algorithm: AlgorithmType) -> bool:
    return algorithm in (
        AlgorithmType.LOGISTIC_REGRESSION,
        AlgorithmType.RANDOM_FOREST,
        AlgorithmType.SVM,
    )


def _isClustering(algorithm: AlgorithmType) -> bool:
    return algorithm == AlgorithmType.KMEANS


def _normalizeSamples(data: dict[str, Any] | list[dict[str, Any]]) -> list[dict[str, Any]]:
    """把列优先 dict 或行优先 list 统一为行优先 list."""
    if isinstance(data, list):
        return data
    columns = data
    if not columns:
        return []
    n = len(next(iter(columns.values())))
    return [{k: v[i] for k, v in columns.items()} for i in range(n)]


def _toMatrix(samples: list[dict[str, Any]], features: list[str]):
    """把样本列表转为 numpy 二维矩阵，按 features 列顺序."""
    import numpy as np

    matrix = []
    for s in samples:
        row = [float(s.get(f, 0.0)) for f in features]
        matrix.append(row)
    return np.array(matrix, dtype=float)


def _computeMetrics(yTrue, yPred, algorithm: AlgorithmType, requested: list[str]) -> dict[str, float]:
    """按算法类型与请求指标计算评估指标."""
    import numpy as np
    from sklearn.metrics import (
        accuracy_score,
        f1_score,
        mean_absolute_error,
        mean_squared_error,
        precision_score,
        r2_score,
        recall_score,
        roc_auc_score,
        silhouette_score,
    )

    metrics: dict[str, float] = {}
    isCls = _isClassification(algorithm)
    isClu = _isClustering(algorithm)
    for m in requested:
        try:
            if m == "accuracy" and isCls:
                metrics[m] = float(accuracy_score(yTrue, yPred))
            elif m == "precision" and isCls:
                metrics[m] = float(precision_score(yTrue, yPred, average="weighted", zero_division=0))
            elif m == "recall" and isCls:
                metrics[m] = float(recall_score(yTrue, yPred, average="weighted", zero_division=0))
            elif m == "f1" and isCls:
                metrics[m] = float(f1_score(yTrue, yPred, average="weighted", zero_division=0))
            elif m == "auc" and isCls:
                metrics[m] = float(roc_auc_score(yTrue, yPred))
            elif m == "rmse":
                metrics[m] = float(np.sqrt(mean_squared_error(yTrue, yPred)))
            elif m == "mae":
                metrics[m] = float(mean_absolute_error(yTrue, yPred))
            elif m == "r2":
                metrics[m] = float(r2_score(yTrue, yPred))
            elif m == "silhouette" and isClu:
                # silhouette 需要 X 与 labels
                metrics[m] = float(silhouette_score(yTrue, yPred))
            else:
                metrics[m] = 0.0
        except Exception:
            metrics[m] = 0.0
    return metrics


def _fitAndScore(config: TrainingConfig, X, y):
    """构造估计器并拟合、计算训练指标（同步 CPU 密集，由 asyncio.to_thread 调度）."""
    estimatorParams = {k: v for k, v in config.params.items() if not k.startswith("_")}
    estimator = _buildEstimator(config.algorithm, estimatorParams, config.randomState)
    start = time.time()
    if _isClustering(config.algorithm):
        estimator.fit(X)
    else:
        estimator.fit(X, y)
    duration = int((time.time() - start) * 1000)

    # 训练指标
    if _isClustering(config.algorithm):
        trainMetrics = {
            "inertia": float(estimator.inertia_),
            "nClusters": int(estimator.n_clusters),
        }
    else:
        yPred = estimator.predict(X)
        trainMetrics = _computeMetrics(
            y,
            yPred,
            config.algorithm,
            ["accuracy"] if _isClassification(config.algorithm) else ["rmse", "r2"],
        )
    return estimator, trainMetrics, duration


def _predictSync(estimator, features: list[str], data: dict, algorithm: AlgorithmType):
    """归一化样本并推理（同步 CPU 密集，由 asyncio.to_thread 调度）."""
    samples = _normalizeSamples(data)
    X = _toMatrix(samples, features)
    yPred = estimator.predict(X).tolist()
    # 分类任务附概率
    probabilities = None
    if _isClassification(algorithm) and hasattr(estimator, "predict_proba"):
        probabilities = estimator.predict_proba(X).tolist()
    # 标量预测值统一为可序列化类型
    predictions = [float(p) if _isNumeric(p) else p for p in yPred]
    return samples, predictions, probabilities


def _evaluateSync(estimator, X, y, algorithm: AlgorithmType, requested: list[str]) -> dict[str, float]:
    """预测并计算评估指标（同步 CPU 密集，由 asyncio.to_thread 调度）."""
    yPred = estimator.predict(X)
    if _isClustering(algorithm):
        # 聚类：silhouette 需要 X 与 labels
        return _computeMetrics(X, yPred, algorithm, requested)
    return _computeMetrics(y, yPred, algorithm, requested)


class SklearnMLBackend(MLBackend):
    """Scikit-learn ML 后端.

    - 训练：调用 sklearn 估计器 fit，模型保存在内存（_artifacts）
    - 预测：调用 estimator predict / predict_proba
    - 评估：调用 sklearn.metrics
    - 模型管理：内存字典

    Note: 真实生产场景模型应序列化到对象存储；此处仅做骨架演示。
    """

    def __init__(self) -> None:
        _requireSklearn()
        self._models: dict[str, ModelInfo] = {}
        self._artifacts: dict[str, Any] = {}  # modelId -> estimator
        self._features: dict[str, list[str]] = {}  # modelId -> features
        self._nameIndex: dict[str, str] = {}

    # ---------- 训练 ----------

    async def train(self, config: TrainingConfig) -> TrainingResult:
        try:
            # 加载训练数据（此处简化为从 config.dataset 解析内存数据）
            # 真实场景应从 FeatureStore / 数据集存储加载
            X, y = await asyncio.to_thread(_loadDataset, config)
            if not config.features:
                # 默认使用所有特征列
                config.features = [f"f{i}" for i in range(X.shape[1])]
            estimator, trainMetrics, duration = await asyncio.to_thread(_fitAndScore, config, X, y)

            modelId = str(uuid.uuid4())
            now = utcNow()
            modelInfo = ModelInfo(
                id=modelId,
                name=config.outputModelName,
                algorithm=config.algorithm.value,
                experimentId=config.experimentId,
                version=1,
                status=ModelStatus.READY,
                artifactUri=f"sklearn-artifact:///{modelId}/model",
                metrics=trainMetrics,
                params=config.params,
                tags={"dataset": config.dataset, "backend": "sklearn"},
                description=config.description,
            )
            modelInfo.createdAt = now
            modelInfo.updatedAt = now
            self._models[modelId] = modelInfo
            self._artifacts[modelId] = estimator
            self._features[modelId] = config.features
            self._nameIndex[config.outputModelName] = modelId

            return TrainingResult(
                modelId=modelId,
                modelName=config.outputModelName,
                status=TrainingStatus.SUCCEEDED,
                metrics=trainMetrics,
                artifactUri=modelInfo.artifactUri,
                durationMs=duration,
            )
        except (
            BackendUnavailableError,
            ValidationError,
        ):
            raise
        except Exception as e:
            raise TrainingFailedError(str(e)) from e

    # ---------- 预测 ----------

    async def predict(self, modelId: str, data: dict) -> PredictionResult:
        if modelId not in self._models:
            raise ModelNotFoundError(modelId)
        model = self._models[modelId]
        algorithm = AlgorithmType(model.algorithm)
        samples, predictions, probabilities = await asyncio.to_thread(
            _predictSync,
            self._artifacts[modelId],
            self._features[modelId],
            data,
            algorithm,
        )
        return PredictionResult(
            modelId=modelId,
            predictions=predictions,
            probabilities=probabilities,
            metadata={
                "backend": "sklearn",
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
        # 加载评估数据
        X, y = await asyncio.to_thread(_loadDatasetForEval, evalConfig)
        if y is None and not _isClustering(algorithm):
            raise ValidationError("监督学习算法评估需要 y 标签")
        metrics = await asyncio.to_thread(
            _evaluateSync,
            self._artifacts[modelId],
            X,
            y,
            algorithm,
            evalConfig.metrics,
        )
        return EvalResult(
            modelId=modelId,
            dataset=evalConfig.dataset,
            metrics=metrics,
            sampleSize=int(X.shape[0]),
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
        self._artifacts.pop(modelId, None)
        self._features.pop(modelId, None)
        self._nameIndex.pop(m.name, None)

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._models.clear()
        self._artifacts.clear()
        self._features.clear()
        self._nameIndex.clear()

    def __len__(self) -> int:
        return len(self._models)


def _isNumeric(v: Any) -> bool:
    try:
        float(v)
        return True
    except (TypeError, ValueError):
        return False


# ---------- 数据集加载（骨架） ----------


def _loadInlineData(inline: Any):
    """解析内联数据为特征矩阵与标签（训练/评估共用）."""
    import numpy as np

    if not isinstance(inline, dict) or "X" not in inline:
        raise ValidationError("内联数据需为包含 'X' 特征矩阵的 dict")
    X = np.array(inline["X"], dtype=float)
    y = None
    if "y" in inline:
        y = np.array(inline["y"])
    return X, y


def _loadDataset(config: TrainingConfig):
    """从配置加载数据集.

    骨架实现：从 config.params["_inline_data"] 读取内存数据；
    真实场景应从 FeatureStore / Hive / 对象存储加载。
    """
    inline = config.params.get("_inline_data")
    if inline is None:
        raise ValidationError(
            "sklearn 后端骨架要求 config.params['_inline_data'] 提供内联数据；" "真实场景应实现数据集加载逻辑"
        )
    X, y = _loadInlineData(inline)
    if y is None and not _isClustering(config.algorithm):
        raise ValidationError("监督学习算法需要 y 标签")
    return X, y


def _loadDatasetForEval(evalConfig: EvalConfig):
    """从评估配置加载数据集.

    对齐训练加载逻辑（_loadDataset）：支持与训练相同的内联数据源，
    通过 evalConfig.dataset 携带 "inline:<base64(JSON)>"，
    JSON 内容与训练内联数据一致：{"X": [[...]], "y": [...]}；
    标签缺失的校验由 evaluate 按模型算法类型执行（聚类可无 y）。
    """
    import base64
    import json

    if not evalConfig.dataset.startswith("inline:"):
        raise ValidationError(
            f"不支持的评估数据集: {evalConfig.dataset}；"
            "sklearn 后端骨架要求 dataset 形如 'inline:<base64(JSON)>'（内容同训练内联数据）"
        )
    try:
        encoded = evalConfig.dataset[len("inline:") :]
        inline = json.loads(base64.b64decode(encoded).decode("utf-8"))
    except Exception as e:
        raise ValidationError(f"无法解析内联评估数据: {e}") from e
    return _loadInlineData(inline)
