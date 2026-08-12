"""ML Platform 抽象接口定义.

三大接口对应 L4.5.6 机器学习的三个核心能力：
    MLBackend       - 训练 / 预测 / 评估 / 模型管理
    FeatureStore    - 特征组 / 特征读写
    ExperimentStore - 实验 / 参数 / 指标追踪

所有接口均为 async，便于底层对接 Spark MLlib / sklearn / MLflow SDK 异步客户端。
"""

from ml_platform.interfaces.backend import MLBackend
from ml_platform.interfaces.experiment_store import ExperimentStore
from ml_platform.interfaces.feature_store import FeatureStore

__all__ = ["MLBackend", "FeatureStore", "ExperimentStore"]
