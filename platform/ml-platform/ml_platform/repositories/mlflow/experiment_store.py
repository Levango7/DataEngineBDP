"""MLflow ExperimentStore - 通过 MLflow Tracking REST API 管理实验.

对齐 MLflow Tracking 的 Experiment → Run → metrics/params 概念。
本实现将每个 ExperimentStore 实验映射到一个 MLflow experiment，
log_metrics / log_params 在 MLflow experiment 下创建新 run 并记录。
"""

from __future__ import annotations

import asyncio
from typing import Optional
import uuid

from ml_platform.interfaces.experiment_store import ExperimentStore
from ml_platform.models import (
    ExperimentConfig,
    ExperimentInfo,
    ExperimentStatus,
    utcNow,
)
from ml_platform.repositories import (
    ExperimentAlreadyExistsError,
    ExperimentNotFoundError,
    MlPlatformError,
)


class MLflowExperimentStore(ExperimentStore):
    """对接真实 MLflow Tracking Server 的实验管理.

    - create_experiment: 在 MLflow 创建 experiment
    - get_experiment:    从 MLflow 拉取 experiment 元信息
    - list_experiments:  列出 MLflow 所有 experiment
    - delete_experiment: 标记 MLflow experiment 为 deleted
    - log_metrics:       在 experiment 下创建新 run，记录 metrics
    - log_params:        在 experiment 下创建新 run，记录 params

    Args:
        trackingUri: MLflow Tracking URI，默认 http://localhost:5000
    """

    def __init__(self, trackingUri: str = "http://localhost:5000") -> None:
        self.trackingUri = trackingUri
        # 本地索引：experimentId -> ExperimentInfo（含累计 metrics/params/runCount）
        self._experiments: dict[str, ExperimentInfo] = {}
        self._nameIndex: dict[str, str] = {}
        self._client = None

    def _getClient(self):
        """延迟初始化 MlflowClient."""
        if self._client is None:
            try:
                import mlflow
                from mlflow.tracking import MlflowClient
            except ImportError as e:  # pragma: no cover
                raise RuntimeError(f"mlflow 未安装: {e}。请安装 mlflow>=2.0") from e
            mlflow.set_tracking_uri(self.trackingUri)
            self._client = MlflowClient(tracking_uri=self.trackingUri)
        return self._client

    # ---------- 实验管理 ----------

    async def create_experiment(self, config: ExperimentConfig) -> str:
        if config.name in self._nameIndex:
            raise ExperimentAlreadyExistsError(config.name)
        client = self._getClient()
        # 在 MLflow 创建 experiment
        try:
            mlflowExpId = await asyncio.to_thread(client.create_experiment, config.name)
        except Exception as e:
            # MLflow 已存在同名 experiment 时，MlflowClient 会抛 MlflowException
            # 转为本地已存在错误
            if "already exists" in str(e).lower() or "RESOURCE_ALREADY_EXISTS" in str(e):
                raise ExperimentAlreadyExistsError(config.name) from e
            raise
        # 本地索引
        experimentId = mlflowExpId  # 用 MLflow experiment_id 作为本地 ID
        now = utcNow()
        info = ExperimentInfo(
            id=experimentId,
            name=config.name,
            status=ExperimentStatus.ACTIVE,
            config=config,
        )
        info.createdAt = now
        info.updatedAt = now
        self._experiments[experimentId] = info
        self._nameIndex[config.name] = experimentId
        return experimentId

    async def get_experiment(self, experimentId: str) -> ExperimentInfo:
        # 优先本地索引
        if experimentId in self._experiments:
            return self._experiments[experimentId]
        # 尝试从 MLflow 拉取
        try:
            client = self._getClient()
            mlflowExp = await asyncio.to_thread(client.get_experiment, experimentId)
        except Exception as e:
            raise ExperimentNotFoundError(experimentId) from e
        if mlflowExp is None:
            raise ExperimentNotFoundError(experimentId)
        # 同步到本地索引
        info = ExperimentInfo(
            id=mlflowExp.experiment_id,
            name=mlflowExp.name,
            status=ExperimentStatus.ACTIVE if mlflowExp.lifecycle_stage == "active" else ExperimentStatus.DELETED,
            config=ExperimentConfig(name=mlflowExp.name),
        )
        self._experiments[mlflowExp.experiment_id] = info
        self._nameIndex[mlflowExp.name] = mlflowExp.experiment_id
        return info

    async def list_experiments(self) -> list[ExperimentInfo]:
        """列出 MLflow 所有 active experiment（真实数据）."""
        client = self._getClient()
        try:
            mlflowExps = await asyncio.to_thread(
                client.search_experiments,
                None,
                "lifecycle_stage = 'active'",
            )
        except Exception:
            # 兜底：返回本地索引
            return sorted(
                self._experiments.values(),
                key=lambda e: e.createdAt,
                reverse=True,
            )
        result: list[ExperimentInfo] = []
        for exp in mlflowExps:
            local = self._experiments.get(exp.experiment_id)
            if local is not None:
                local.name = exp.name
                local.status = ExperimentStatus.ACTIVE
                info = local
            else:
                info = ExperimentInfo(
                    id=exp.experiment_id,
                    name=exp.name,
                    status=ExperimentStatus.ACTIVE,
                    config=ExperimentConfig(name=exp.name),
                )
                self._experiments[exp.experiment_id] = info
            self._nameIndex[info.name] = exp.experiment_id
            result.append(info)
        return sorted(result, key=lambda e: e.name)

    async def delete_experiment(self, experimentId: str) -> None:
        if experimentId not in self._experiments:
            # 尝试从 MLflow 拉取，确认存在
            await self.get_experiment(experimentId)
        client = self._getClient()
        try:
            await asyncio.to_thread(client.delete_experiment, experimentId)
        except Exception as e:
            raise MlPlatformError(f"MLflow 删除实验失败: {experimentId} ({e})") from e
        info = self._experiments.pop(experimentId, None)
        if info:
            self._nameIndex.pop(info.name, None)

    # ---------- 指标 / 参数 ----------

    async def log_metrics(self, experimentId: str, metrics: dict[str, float]) -> None:
        info = await self.get_experiment(experimentId)
        client = self._getClient()
        # 在 MLflow experiment 下创建新 run，记录 metrics
        run = await asyncio.to_thread(client.create_run, experimentId, run_name=f"metrics-{uuid.uuid4().hex[:8]}")
        runId = run.info.run_id

        def _logMetrics(client, runId, metrics):
            for k, v in metrics.items():
                client.log_metric(runId, k, float(v))
            client.set_terminated(runId, "FINISHED")

        await asyncio.to_thread(_logMetrics, client, runId, metrics)
        # 更新本地累计
        info.metrics.update(metrics)
        info.runCount += 1
        info.updatedAt = utcNow()

    async def log_params(self, experimentId: str, params: dict) -> None:
        info = await self.get_experiment(experimentId)
        client = self._getClient()
        run = await asyncio.to_thread(client.create_run, experimentId, run_name=f"params-{uuid.uuid4().hex[:8]}")
        runId = run.info.run_id

        def _logParams(client, runId, params):
            for k, v in params.items():
                client.log_param(runId, k, str(v))
            client.set_terminated(runId, "FINISHED")

        await asyncio.to_thread(_logParams, client, runId, params)
        info.params.update(params)
        info.updatedAt = utcNow()

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空本地索引（测试用，不删 MLflow 数据）."""
        self._experiments.clear()
        self._nameIndex.clear()

    def __len__(self) -> int:
        return len(self._experiments)

    # ---------- MLflow 专属查询（供 business-portal 使用） ----------

    async def countExperiments(self) -> int:
        """返回 MLflow active experiment 数量（真实数据）."""
        experiments = await self.list_experiments()
        return len(experiments)

    async def getBestMetricAcrossExperiments(
        self,
        metricKey: str = "accuracy",
        mode: str = "max",
    ) -> Optional[float]:
        """跨所有 experiment 寻找某指标的最优值（真实数据）.

        Args:
            metricKey: 指标名，如 accuracy
            mode:      max / min
        Returns:
            最优值；若没有任何 run 记录该指标，返回 None。
        """
        client = self._getClient()
        try:
            experiments = await asyncio.to_thread(
                client.search_experiments,
                None,
                "lifecycle_stage = 'active'",
            )
        except Exception:
            return None
        allValues: list[float] = []
        for exp in experiments:
            try:
                runs = await asyncio.to_thread(client.search_runs, [exp.experiment_id])
            except Exception:
                continue
            for r in runs:
                if metricKey in r.data.metrics:
                    allValues.append(r.data.metrics[metricKey].value)
        if not allValues:
            return None
        if mode == "max":
            return max(allValues)
        return min(allValues)

    async def getTotalRunCount(self) -> int:
        """返回所有 active experiment 下的 run 总数（真实数据）."""
        client = self._getClient()
        try:
            experiments = await asyncio.to_thread(
                client.search_experiments,
                None,
                "lifecycle_stage = 'active'",
            )
        except Exception:
            return 0
        total = 0
        for exp in experiments:
            try:
                runs = await asyncio.to_thread(client.search_runs, [exp.experiment_id])
            except Exception:
                continue
            total += len(runs)
        return total
